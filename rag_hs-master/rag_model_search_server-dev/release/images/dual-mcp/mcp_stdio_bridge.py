#!/usr/bin/env python3
"""mcp_stdio_bridge.py — 轻量 stdio ↔ streamable-http MCP 桥（替代 supergateway）

设计（针对 supergateway 的崩溃根因）：
  一个 HTTP 请求 = 子进程一次完整往返（每会话一个子进程 + 会话级锁）。
  不做跨请求响应路由 → 慢调用/乱序响应无从触发 "No connection established" 崩溃。

协议面（MCP Streamable HTTP 最小实现）：
  POST   /mcp   无 Mcp-Session-Id 头 → 新建会话（子进程），响应头带回 session id
  POST   /mcp   带头 → 复用会话子进程；JSON-RPC 响应以 application/json 返回
  DELETE /mcp   结束会话，杀子进程
  GET    /mcp   405（不做服务端主动推送）

子进程崩溃 → 该次调用返回 JSON-RPC error，下次调用自动重拉子进程。
空闲会话超时自动回收。所有异常都有兜底，桥进程本身不退出。

用法:
  BRIDGE_CHILD_CMD="/opt/.../codebase-memory-mcp" BRIDGE_PORT=9800 \
  python3 mcp_stdio_bridge.py
  BRIDGE_CHILD_CMD 为空时可用 BRIDGE_CHILD_CMD_ARGS 传带参数命令（空格分隔）。
"""
import asyncio
import json
import logging
import os
import shlex
import time
import urllib.request
import uuid

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, Response

logger = logging.getLogger(__name__)

_RESULT_KEY = 'result'
_PARAMS_KEY = 'params'
_ID_KEY = 'id'
_JSONRPC_KEY = 'jsonrpc'
_TWO_POINT_OH = '2.0'
_ERROR_KEY = 'error'
_CODE_KEY = 'code'
_MESSAGE_KEY = 'message'
_TOOLS_KEY = 'tools'
_NAME_KEY = 'name'
_DESCRIPTION_KEY = 'description'
_METHOD_KEY = 'method'

CHILD_CMD = os.environ.get("BRIDGE_CHILD_CMD", "").strip()
PORT = int(os.environ.get("BRIDGE_PORT", "9800"))
HOST = os.environ.get("BRIDGE_HOST", "0.0.0.0")
IDLE_TIMEOUT = int(os.environ.get("BRIDGE_IDLE_TIMEOUT", "1800"))   # 秒
CALL_TIMEOUT = int(os.environ.get("BRIDGE_CALL_TIMEOUT", "900"))    # 单次调用硬上限（秒）
LOG = os.environ.get("BRIDGE_LOG", "/var/log/mcp-bridge.log")
SERVER_NAME = os.environ.get("BRIDGE_SERVER_NAME", "").strip()      # 对外服务名（改写 serverInfo.name）
UPSTREAM = os.environ.get("BRIDGE_UPSTREAM_URL", "").strip()        # HTTP 上游代理模式（替代子进程）
# 工具名前缀改写（如 mempalace_* → hisi_docs_*）：让 Agent 按服务路由
TOOL_PREFIX_FROM = os.environ.get("BRIDGE_TOOL_PREFIX_FROM", "").strip()
TOOL_PREFIX_TO = os.environ.get("BRIDGE_TOOL_PREFIX_TO", "").strip()
TOOL_DESC_PREFIX = os.environ.get("BRIDGE_TOOL_DESC_PREFIX", "").strip()
# 工具描述整体覆写：{"外部工具名": "新描述"}——强路由信号（如把 mempalace
# 的 "drawer content" 描述改成"芯片文档库"）
def _load_desc_overrides():
    raw = os.environ.get("BRIDGE_TOOL_DESC_OVERRIDES", "").strip()
    if not raw:
        return {}
    try:
        d = json.loads(raw)
        return d if isinstance(d, dict) else {}
    except ValueError:
        return {}
TOOL_DESC_OVERRIDES = _load_desc_overrides()
# 工具白名单（逗号分隔的对外工具名）：只暴露名单内工具，减少 Agent
# 工具选择噪声；tools/list 过滤 + tools/call 拦截
TOOL_ALLOWLIST = {t.strip() for t in os.environ.get("BRIDGE_TOOL_ALLOWLIST", "").split(",")
                  if t.strip()}


def _rename_server_info(resp, method):
    """initialize 响应里的 serverInfo.name 换成 SERVER_NAME（如 hisi-docs-mcp）。"""
    if not (SERVER_NAME and isinstance(resp, dict) and method == "initialize"):
        return
    try:
        resp["result"]["serverInfo"]["name"] = SERVER_NAME
    except (KeyError, TypeError):
        pass


def _rewrite_tools_list(resp, method):
    """tools/list 响应改写：工具名前缀 + 描述前缀/覆写（路由信号）。"""
    if method != "tools/list" or not isinstance(resp, dict):
        return
    try:
        for t in resp[_RESULT_KEY].get(_TOOLS_KEY, []):
            if (TOOL_PREFIX_FROM and TOOL_PREFIX_TO
                    and t.get(_NAME_KEY, "").startswith(TOOL_PREFIX_FROM + "_")):
                t[_NAME_KEY] = TOOL_PREFIX_TO + t[_NAME_KEY][len(TOOL_PREFIX_FROM):]
            if TOOL_DESC_PREFIX and isinstance(t.get(_DESCRIPTION_KEY), str):
                t[_DESCRIPTION_KEY] = TOOL_DESC_PREFIX + t[_DESCRIPTION_KEY]
            if t.get(_NAME_KEY) in TOOL_DESC_OVERRIDES:
                t[_DESCRIPTION_KEY] = TOOL_DESC_OVERRIDES[t[_NAME_KEY]]
        if TOOL_ALLOWLIST:
            resp[_RESULT_KEY][_TOOLS_KEY] = [
                t for t in resp[_RESULT_KEY].get(_TOOLS_KEY, [])
                if t.get(_NAME_KEY) in TOOL_ALLOWLIST]
    except (KeyError, TypeError):
        pass


async def _proxy_upstream(obj: dict):
    """HTTP 上游代理模式：把请求转发给上游 MCP HTTP 服务（如 mempalace
    原生 HTTP——其 stdio 传输在管道环境下挂死），改写服务名/工具名。"""
    method = obj.get("method")
    # 出站：tools/call 的参数名改回上游内部名
    _is_tools_call = method == "tools/call"
    _has_prefix_config = bool(TOOL_PREFIX_FROM and TOOL_PREFIX_TO)
    if _is_tools_call and _has_prefix_config and isinstance(obj.get(_PARAMS_KEY), dict):
        name = obj[_PARAMS_KEY].get("name", "")
        if name.startswith(TOOL_PREFIX_TO + "_"):
            obj = json.loads(json.dumps(obj))
            obj[_PARAMS_KEY]["name"] = TOOL_PREFIX_FROM + name[len(TOOL_PREFIX_TO):]
    def _fwd():
        req = urllib.request.Request(UPSTREAM, data=json.dumps(obj).encode(),
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=CALL_TIMEOUT) as r:
            return json.loads(r.read().decode())
    try:
        resp = await asyncio.to_thread(_fwd)
    except Exception as e:
        resp = {_JSONRPC_KEY: _TWO_POINT_OH, _ID_KEY: obj.get(_ID_KEY),
                _ERROR_KEY: {_CODE_KEY: -32000, _MESSAGE_KEY: f"上游错误: {type(e).__name__}: {e}"}}
    _rename_server_info(resp, method)
    _rewrite_tools_list(resp, method)
    return JSONResponse(resp)

_sessions: dict = {}


def log(msg: str):
    line = f"[bridge {time.strftime('%H:%M:%S')}] {msg}"
    try:
        with open(LOG, "a") as f:
            f.write(line + "\n")
    except Exception as e:
        logger.warning(f"[bridge] failed to write log to {LOG}: {e}")
    logger.info(line)


class Session:
    def __init__(self):
        self.id = uuid.uuid4().hex
        self.proc = None
        self.lock = asyncio.Lock()
        self.last_used = time.time()

    @staticmethod
    def _argv():
        if CHILD_CMD:
            return [CHILD_CMD]
        return shlex.split(os.environ.get("BRIDGE_CHILD_CMD_ARGS", ""))

    async def kill(self):
        if self.proc and self.proc.returncode is None:
            try:
                self.proc.kill()
            except Exception as e:
                logger.warning(f"[bridge session {self.id[:8]}] failed to kill subprocess {self.proc.pid}: {e}")
        self.proc = None

    async def call(self, obj: dict) -> dict | None:
        """发送一条 JSON-RPC 并读取子进程响应。

        通知（无 id）不等待响应，返回 None。
        响应按 id 精确匹配；途中的通知/非 JSON 行一律跳过。
        """
        async with self.lock:
            self.last_used = time.time()
            await self._ensure_proc()
            rid = obj.get(_ID_KEY)
            is_notification = rid is None

            try:
                self.proc.stdin.write((json.dumps(obj) + "\n").encode())
                await self.proc.stdin.drain()
            except Exception as e:
                await self.kill()
                if is_notification:
                    return None
                return {_JSONRPC_KEY: _TWO_POINT_OH, _ID_KEY: rid,
                        _ERROR_KEY: {_CODE_KEY: -32000,
                                     _MESSAGE_KEY: f"子进程管道写入失败（已重置）: {e}"}}

            if is_notification:
                return None

            deadline = time.time() + CALL_TIMEOUT
            while time.time() < deadline:
                try:
                    raw = await asyncio.wait_for(
                        self.proc.stdout.readline(), timeout=max(1, deadline - time.time()))
                except asyncio.TimeoutError:
                    break
                except Exception:
                    break
                if not raw:
                    # 子进程退出
                    rc = self.proc.returncode
                    await self.kill()
                    return {_JSONRPC_KEY: _TWO_POINT_OH, _ID_KEY: rid,
                            _ERROR_KEY: {_CODE_KEY: -32000,
                                          _MESSAGE_KEY: f"子进程退出（rc={rc}），下次调用将自动重启"}}
                line = raw.decode("utf-8", errors="replace").strip()
                if not line:
                    continue
                try:
                    resp = json.loads(line)
                except json.JSONDecodeError:
                    continue  # 非日志/横幅行直接跳过
                if not isinstance(resp, dict):
                    continue
                if resp.get(_ID_KEY) == rid:
                    return resp
                # 其它 id 的响应（不应出现）→ 丢弃，绝不转发
            await self.kill()
            return {_JSONRPC_KEY: _TWO_POINT_OH, _ID_KEY: rid,
                    _ERROR_KEY: {_CODE_KEY: -32000, _MESSAGE_KEY: f"调用超时（>{CALL_TIMEOUT}s），会话已重置"}}

    async def _ensure_proc(self):
        if self.proc is not None and self.proc.returncode is None:
            return
        argv = self._argv()
        if not argv:
            raise RuntimeError("BRIDGE_CHILD_CMD 未配置")
        self.proc = await asyncio.create_subprocess_exec(
            *argv,
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.DEVNULL,
        )
        logger.info(f"[bridge] session {self.id[:8]} 子进程已启动 pid={self.proc.pid}")


async def _reaper():
    while True:
        await asyncio.sleep(60)
        now = time.time()
        for sid in list(_sessions):
            s = _sessions[sid]
            if now - s.last_used > IDLE_TIMEOUT:
                log(f"session {sid[:8]} 空闲超时回收")
                await s.kill()
                _sessions.pop(sid, None)


app = App = FastAPI(openapi_url=None, docs_url=None, redoc_url=None)


@app.on_event("startup")
async def _start():
    asyncio.get_event_loop().create_task(_reaper())
    logger.info(f"[bridge] bridge port={PORT} child={' '.join(shlex.split(os.environ.get('BRIDGE_CHILD_CMD_ARGS', CHILD_CMD)))[:80]}")


@app.post("/mcp")
async def mcp_post(request: Request):
    try:
        obj = json.loads(await request.body())
    except Exception:
        return JSONResponse({_JSONRPC_KEY: _TWO_POINT_OH, _ID_KEY: None,
                             _ERROR_KEY: {_CODE_KEY: -32700, _MESSAGE_KEY: "请求体不是合法 JSON"}},
                            status_code=400)
    if not isinstance(obj, dict) or _METHOD_KEY not in obj:
        return JSONResponse({_JSONRPC_KEY: _TWO_POINT_OH, _ID_KEY: obj.get(_ID_KEY) if isinstance(obj, dict) else None,
                             _ERROR_KEY: {_CODE_KEY: -32600, _MESSAGE_KEY: "不是合法 JSON-RPC 请求"}},
                            status_code=400)

    _is_tools_call_with_params = (obj.get(_METHOD_KEY) == "tools/call"
                                  and isinstance(obj.get(_PARAMS_KEY), dict))
    if (TOOL_ALLOWLIST and _is_tools_call_with_params
            and obj[_PARAMS_KEY].get(_NAME_KEY) not in TOOL_ALLOWLIST):
        return JSONResponse({_JSONRPC_KEY: _TWO_POINT_OH, _ID_KEY: obj.get(_ID_KEY),
                             _ERROR_KEY: {_CODE_KEY: -32601,
                                          _MESSAGE_KEY: f"工具 {obj[_PARAMS_KEY].get(_NAME_KEY)} 不在允许列表"}})

    if UPSTREAM:
        return await _proxy_upstream(obj)

    sid = request.headers.get("mcp-session-id")
    session = _sessions.get(sid)
    if session is None:
        session = Session()
        _sessions[session.id] = session
        log(f"session {session.id[:8]} 创建（method={obj.get(_METHOD_KEY)}）")

    try:
        resp = await session.call(obj)
    except Exception as e:
        log(f"session {session.id[:8]} 调用异常: {type(e).__name__} {e}")
        await session.kill()
        resp = {_JSONRPC_KEY: _TWO_POINT_OH, _ID_KEY: obj.get(_ID_KEY),
                _ERROR_KEY: {_CODE_KEY: -32000, _MESSAGE_KEY: f"桥内部错误: {e}"}}

    # 对外服务名改写：initialize 响应里的 serverInfo.name 换成
    # BRIDGE_SERVER_NAME（如 hisi-code-mcp），子进程内部名不再暴露。
    _rename_server_info(resp, obj.get(_METHOD_KEY))
    _rewrite_tools_list(resp, obj.get(_METHOD_KEY))

    headers = {"Mcp-Session-Id": session.id}
    if resp is None:  # 通知
        return Response(status_code=202, headers=headers)
    return JSONResponse(resp, headers=headers)


@app.delete("/mcp")
async def mcp_delete(request: Request):
    sid = request.headers.get("mcp-session-id")
    session = _sessions.pop(sid, None)
    if session:
        await session.kill()
        log(f"session {sid[:8]} 已结束")
    return Response(status_code=204)


@app.get("/mcp")
async def mcp_get():
    return Response(status_code=405)


@app.get("/healthz")
async def healthz():
    return {"ok": True, "sessions": len(_sessions)}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host=HOST, port=PORT, log_level="warning")
