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

BRIDGE_SHARED_CHILD=1 常驻共享模式（并发场景推荐）：
  全部 HTTP 会话复用一个长生命周期子进程——id 重写（"b<n>"）+ 常驻
  reader 按id分发 + 信号量限流（BRIDGE_MAX_INFLIGHT，默认 6，须 < 子进程
  stdio frontend 队列上限 8）。每会话冷启子进程的旧模式在 ≥10 并发下
  会因冷启风暴 + zombie 回收缺失崩溃。

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
import sys
import time
import urllib.request
import uuid

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, Response

CHILD_CMD = os.environ.get("BRIDGE_CHILD_CMD", "").strip()
PORT = int(os.environ.get("BRIDGE_PORT", "9800"))
HOST = os.environ.get("BRIDGE_HOST", "0.0.0.0")
IDLE_TIMEOUT = int(os.environ.get("BRIDGE_IDLE_TIMEOUT", "1800"))   # 秒
CALL_TIMEOUT = int(os.environ.get("BRIDGE_CALL_TIMEOUT", "900"))    # 单次调用硬上限（秒）
LOG = os.environ.get("BRIDGE_LOG", "/var/log/mcp-bridge.log")
SERVER_NAME = os.environ.get("BRIDGE_SERVER_NAME", "").strip()      # 对外服务名（改写 serverInfo.name）
UPSTREAM = os.environ.get("BRIDGE_UPSTREAM_URL", "").strip()        # HTTP 上游代理模式（替代子进程）
# 常驻共享子进程模式：全部 HTTP 会话复用一个长生命周期子进程（id 重写 demux），
# 不再每会话冷启一个（≥10 并发冷启风暴 + zombie 是旧模式崩点）。stdio frontend
# 队列上限 8 项（溢出即停机），故在途并发用信号量压到 MAX_INFLIGHT。
SHARED_CHILD = os.environ.get("BRIDGE_SHARED_CHILD", "").strip() == "1"
MAX_INFLIGHT = int(os.environ.get("BRIDGE_MAX_INFLIGHT", "6"))
POOL_SIZE = int(os.environ.get("BRIDGE_POOL_SIZE", "1"))
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

# 扫描 G.TYP.03：重复字典 key 常量化
_K_RESULT = "result"
_K_PARAMS = "params"
_K_ID = "id"
_K_JSONRPC = "jsonrpc"
_K_JSONRPC_VER = "2.0"
_K_NAME = "name"
_K_TOOLS = "tools"
_K_DESCRIPTION = "description"
_K_CODE = "code"
_K_MESSAGE = "message"
_K_ERROR = "error"
_K_METHOD = "method"

_log = logging.getLogger("mcp_bridge")
_log.propagate = False
_log_h = logging.StreamHandler(sys.stdout)  # 原 print(flush=True) 等价：逐条 flush
_log_h.setFormatter(logging.Formatter("%(message)s"))
_log.addHandler(_log_h)
_diag = logging.getLogger("mcp_bridge.diag")
_diag.propagate = False
_diag_h = logging.StreamHandler(sys.stderr)
_diag_h.setFormatter(logging.Formatter("%(message)s"))
_diag.addHandler(_diag_h)


def _rename_server_info(resp, method):
    """initialize 响应里的 serverInfo.name 换成 SERVER_NAME（如 hisi-docs-mcp）。"""
    if not (SERVER_NAME and isinstance(resp, dict) and method == "initialize"):
        return
    try:
        resp[_K_RESULT]["serverInfo"]["name"] = SERVER_NAME
    except (KeyError, TypeError):
        pass


def _rewrite_tools_list(resp, method):
    """tools/list 响应改写：工具名前缀 + 描述前缀/覆写（路由信号）。"""
    if method != "tools/list" or not isinstance(resp, dict):
        return
    try:
        for t in resp[_K_RESULT].get(_K_TOOLS, []):
            if (TOOL_PREFIX_FROM and TOOL_PREFIX_TO
                    and t.get(_K_NAME, "").startswith(TOOL_PREFIX_FROM + "_")):
                t[_K_NAME] = TOOL_PREFIX_TO + t[_K_NAME][len(TOOL_PREFIX_FROM):]
            if TOOL_DESC_PREFIX and isinstance(t.get(_K_DESCRIPTION), str):
                t[_K_DESCRIPTION] = TOOL_DESC_PREFIX + t[_K_DESCRIPTION]
            if t.get(_K_NAME) in TOOL_DESC_OVERRIDES:
                t[_K_DESCRIPTION] = TOOL_DESC_OVERRIDES[t[_K_NAME]]
        if TOOL_ALLOWLIST:
            resp[_K_RESULT][_K_TOOLS] = [
                t for t in resp[_K_RESULT].get(_K_TOOLS, [])
                if t.get(_K_NAME) in TOOL_ALLOWLIST]
    except (KeyError, TypeError):
        pass


async def _proxy_upstream(obj: dict):
    """HTTP 上游代理模式：把请求转发给上游 MCP HTTP 服务（如 mempalace
    原生 HTTP——其 stdio 传输在管道环境下挂死），改写服务名/工具名。"""
    method = obj.get(_K_METHOD)
    # 出站：tools/call 的参数名改回上游内部名
    is_tools_call = method == "tools/call"
    prefix_rewrite_on = bool(TOOL_PREFIX_FROM and TOOL_PREFIX_TO)
    if (is_tools_call and prefix_rewrite_on
            and isinstance(obj.get(_K_PARAMS), dict)):
        name = obj[_K_PARAMS].get("name", "")
        if name.startswith(TOOL_PREFIX_TO + "_"):
            obj = json.loads(json.dumps(obj))
            obj[_K_PARAMS]["name"] = TOOL_PREFIX_FROM + name[len(TOOL_PREFIX_TO):]
    def _fwd():
        req = urllib.request.Request(UPSTREAM, data=json.dumps(obj).encode(),
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=CALL_TIMEOUT) as r:
            return json.loads(r.read().decode())
    try:
        resp = await asyncio.to_thread(_fwd)
    except Exception as e:
        resp = {_K_JSONRPC: _K_JSONRPC_VER, _K_ID: obj.get(_K_ID),
                _K_ERROR: {_K_CODE: -32000, _K_MESSAGE: f"上游错误: {type(e).__name__}: {e}"}}
    _rename_server_info(resp, method)
    _rewrite_tools_list(resp, method)
    return JSONResponse(resp)

_sessions: dict = {}


def log(msg: str):
    line = f"[bridge {time.strftime('%H:%M:%S')}] {msg}"
    try:
        with open(LOG, "a") as f:
            f.write(line + "\n")
    except OSError as e:
        _diag.warning(f"[bridge] 日志文件写入失败({LOG}): {e}")
    _log.info(line)


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
                _diag.debug(f"[bridge] kill proc 失败: {e}")
            # 必须 wait() 回收，否则 zombie 堆积（"Unknown child process pid"）
            try:
                await self.proc.wait()
            except Exception:
                pass
        self.proc = None

    async def call(self, obj: dict) -> dict | None:
        """发送一条 JSON-RPC 并读取子进程响应。

        通知（无 id）不等待响应，返回 None。
        响应按 id 精确匹配；途中的通知/非 JSON 行一律跳过。
        """
        async with self.lock:
            self.last_used = time.time()
            await self._ensure_proc()
            rid = obj.get(_K_ID)
            is_notification = rid is None

            try:
                self.proc.stdin.write((json.dumps(obj) + "\n").encode())
                await self.proc.stdin.drain()
            except Exception as e:
                await self.kill()
                if is_notification:
                    return None
                return {_K_JSONRPC: _K_JSONRPC_VER, _K_ID: rid,
                        _K_ERROR: {_K_CODE: -32000,
                                  _K_MESSAGE: f"子进程管道写入失败（已重置）: {e}"}}

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
                    return {_K_JSONRPC: _K_JSONRPC_VER, _K_ID: rid,
                            _K_ERROR: {_K_CODE: -32000,
                                      _K_MESSAGE: f"子进程退出（rc={rc}），下次调用将自动重启"}}
                line = raw.decode("utf-8", errors="replace").strip()
                if not line:
                    continue
                try:
                    resp = json.loads(line)
                except json.JSONDecodeError:
                    continue  # 非日志/横幅行直接跳过
                if not isinstance(resp, dict):
                    continue
                if resp.get(_K_ID) == rid:
                    return resp
                # 其它 id 的响应（不应出现）→ 丢弃，绝不转发
            await self.kill()
            return {_K_JSONRPC: _K_JSONRPC_VER, _K_ID: rid,
                    _K_ERROR: {_K_CODE: -32000, _K_MESSAGE: f"调用超时（>{CALL_TIMEOUT}s），会话已重置"}}

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
        log(f"session {self.id[:8]} 子进程已启动 pid={self.proc.pid}")

class SharedChild:
    """常驻共享子进程：所有 HTTP 会话复用一个长生命周期 stdio 子进程。

    并发模型：id 重写（全局 "b<n>" 字符串 id）+ 常驻 reader demux +
    信号量限流（MAX_INFLIGHT < stdio frontend 队列上限 8，防溢出停机）。
    子进程退出 → pending 全部以 -32603 失败 → 下次调用自动重启。
    """

    def __init__(self):
        self.proc = None
        self.pending: dict = {}
        self.sem = asyncio.Semaphore(MAX_INFLIGHT)
        self._spawn_lock = asyncio.Lock()
        self._counter = 0
        self._stderr_fh = None

    def _argv(self):
        return Session._argv()

    def _bid(self):
        self._counter += 1
        return f"b{self._counter}"

    async def _ensure(self):
        if self.proc is not None and self.proc.returncode is None:
            return
        async with self._spawn_lock:
            if self.proc is not None and self.proc.returncode is None:
                return
            argv = self._argv()
            if not argv:
                raise RuntimeError("BRIDGE_CHILD_CMD 未配置")
            if self._stderr_fh is None:
                try:
                    self._stderr_fh = open(LOG, "a", buffering=1)
                except OSError:
                    self._stderr_fh = asyncio.subprocess.DEVNULL
            self.proc = await asyncio.create_subprocess_exec(
                *argv,
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE,
                stderr=self._stderr_fh,
            )
            asyncio.get_event_loop().create_task(self._reader(self.proc))
            log(f"shared child 已启动 pid={self.proc.pid}")

    async def _reader(self, proc):
        while True:
            try:
                raw = await proc.stdout.readline()
            except Exception:
                raw = b""
            if not raw:
                break
            line = raw.decode("utf-8", errors="replace").strip()
            if not line:
                continue
            try:
                resp = json.loads(line)
            except json.JSONDecodeError:
                continue  # 非 JSON 行（日志/横幅）跳过
            if not isinstance(resp, dict) or _K_ID not in resp:
                continue  # 服务端通知等
            fut = self.pending.get(resp[_K_ID])
            if fut is not None and not fut.done():
                fut.set_result(resp)
        # EOF：子进程退出，回收并 fail 全部 pending
        try:
            await proc.wait()
        except Exception:
            pass
        if proc is not self.proc:
            return  # 已被新子进程取代，pending 归属新 reader
        log(f"shared child 退出 rc={proc.returncode}，pending={len(self.pending)} 失败")
        self.proc = None
        err = {_K_CODE: -32603, _K_MESSAGE: f"共享子进程退出（rc={proc.returncode}），下次调用将自动重启"}
        for fut in list(self.pending.values()):
            if not fut.done():
                fut.set_result({_K_JSONRPC: _K_JSONRPC_VER, _K_ID: None,
                                _K_ERROR: err})

    async def call(self, obj: dict) -> dict | None:
        """请求经共享子进程转发；通知（无 id）只写 stdin 不等待。"""
        rid = obj.get(_K_ID)
        if rid is None:
            await self._ensure()
            try:
                self.proc.stdin.write((json.dumps(obj) + "\n").encode())
                await self.proc.stdin.drain()
            except Exception:
                pass  # 通知尽力而为
            return None

        async with self.sem:
            await self._ensure()
            bid = self._bid()
            fut = asyncio.get_event_loop().create_future()
            self.pending[bid] = fut
            out = dict(obj)
            out[_K_ID] = bid
            try:
                self.proc.stdin.write((json.dumps(out) + "\n").encode())
                await self.proc.stdin.drain()
            except Exception as e:
                self.pending.pop(bid, None)
                return {_K_JSONRPC: _K_JSONRPC_VER, _K_ID: rid,
                        _K_ERROR: {_K_CODE: -32000,
                                  _K_MESSAGE: f"共享子进程管道写入失败: {e}"}}
            try:
                resp = await asyncio.wait_for(fut, timeout=CALL_TIMEOUT)
            except asyncio.TimeoutError:
                return {_K_JSONRPC: _K_JSONRPC_VER, _K_ID: rid,
                        _K_ERROR: {_K_CODE: -32000,
                                  _K_MESSAGE: f"调用超时（>{CALL_TIMEOUT}s）"}}
            finally:
                self.pending.pop(bid, None)
            if isinstance(resp, dict):
                resp = dict(resp)
                resp[_K_ID] = rid
            return resp


class SharedPool:
    """共享子进程池：BRIDGE_POOL_SIZE 个常驻子进程，按最闲路由。

    codegraph 的 stdio server 实测完全串行（6 并发耗时 = 6×单次），
    单子进程吞吐锁死 ~1/latency；池化后聚合吞吐 = POOL_SIZE × 单进程。
    CBM 子进程内部并发良好，POOL_SIZE=1 即够（默认）。
    惰性扩容：现有子进程全忙且未满编时才起新的，空闲零开销。
    """

    def __init__(self, size: int):
        self.size = max(1, size)
        self.children: list = []
        self._grow_lock = asyncio.Lock()

    def _alive(self) -> list:
        self.children = [c for c in self.children
                         if c.proc is not None and c.proc.returncode is None]
        return self.children

    async def _obtain(self) -> SharedChild:
        async with self._grow_lock:
            alive = self._alive()
            idle = [c for c in alive if len(c.pending) == 0]
            if idle or len(alive) >= self.size:
                return idle[0] if idle else min(alive, key=lambda c: len(c.pending))
            c = SharedChild()
            c._stderr_fh = _shared_stderr()
            self.children.append(c)
            await c._ensure()
            return c

    async def call(self, obj: dict) -> dict | None:
        return await (await self._obtain()).call(obj)


_shared_stderr_fh = None


def _shared_stderr():
    global _shared_stderr_fh
    if _shared_stderr_fh is None:
        try:
            _shared_stderr_fh = open(LOG, "a", buffering=1)
        except OSError:
            _shared_stderr_fh = asyncio.subprocess.DEVNULL
    return _shared_stderr_fh


_shared: SharedPool | None = None


def _get_shared() -> SharedPool:
    global _shared
    if _shared is None:
        _shared = SharedPool(POOL_SIZE)
    return _shared


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
    if SHARED_CHILD:
        log(f"桥启动 port={PORT} mode=shared pool={POOL_SIZE} max_inflight={MAX_INFLIGHT} "
            f"child={' '.join(shlex.split(os.environ.get('BRIDGE_CHILD_CMD_ARGS', CHILD_CMD)))[:80]}")
    else:
        log(f"桥启动 port={PORT} child={' '.join(shlex.split(os.environ.get('BRIDGE_CHILD_CMD_ARGS', CHILD_CMD)))[:80]}")


@app.post("/mcp")
async def mcp_post(request: Request):
    try:
        obj = json.loads(await request.body())
    except Exception:
        return JSONResponse({_K_JSONRPC: _K_JSONRPC_VER, _K_ID: None,
                             _K_ERROR: {_K_CODE: -32700, _K_MESSAGE: "请求体不是合法 JSON"}},
                            status_code=400)
    if not isinstance(obj, dict) or _K_METHOD not in obj:
        return JSONResponse({_K_JSONRPC: _K_JSONRPC_VER, _K_ID: obj.get(_K_ID) if isinstance(obj, dict) else None,
                             _K_ERROR: {_K_CODE: -32600, _K_MESSAGE: "不是合法 JSON-RPC 请求"}},
                            status_code=400)

    params = obj.get(_K_PARAMS)
    tool_not_allowed = (isinstance(params, dict)
                        and params.get(_K_NAME) not in TOOL_ALLOWLIST)
    if (TOOL_ALLOWLIST and obj.get(_K_METHOD) == "tools/call"
            and tool_not_allowed):
        return JSONResponse({_K_JSONRPC: _K_JSONRPC_VER, _K_ID: obj.get(_K_ID),
                             _K_ERROR: {_K_CODE: -32601,
                                       _K_MESSAGE: f"工具 {obj[_K_PARAMS].get(_K_NAME)} 不在允许列表"}})

    if UPSTREAM:
        return await _proxy_upstream(obj)

    if SHARED_CHILD:
        # 共享模式：会话无服务端资源（子进程全局唯一），sid 仅作协议标识
        sid = request.headers.get("mcp-session-id") or uuid.uuid4().hex
        try:
            resp = await _get_shared().call(obj)
        except Exception as e:
            log(f"shared child 调用异常: {type(e).__name__} {e}")
            resp = {_K_JSONRPC: _K_JSONRPC_VER, _K_ID: obj.get(_K_ID),
                    _K_ERROR: {_K_CODE: -32000, _K_MESSAGE: f"桥内部错误: {e}"}}
        _rename_server_info(resp, obj.get(_K_METHOD))
        _rewrite_tools_list(resp, obj.get(_K_METHOD))
        headers = {"Mcp-Session-Id": sid}
        if resp is None:
            return Response(status_code=202, headers=headers)
        return JSONResponse(resp, headers=headers)

    sid = request.headers.get("mcp-session-id")
    session = _sessions.get(sid)
    if session is None:
        session = Session()
        _sessions[session.id] = session
        log(f"session {session.id[:8]} 创建（method={obj.get('method')}）")

    try:
        resp = await session.call(obj)
    except Exception as e:
        log(f"session {session.id[:8]} 调用异常: {type(e).__name__} {e}")
        await session.kill()
        resp = {_K_JSONRPC: _K_JSONRPC_VER, _K_ID: obj.get(_K_ID),
                _K_ERROR: {_K_CODE: -32000, _K_MESSAGE: f"桥内部错误: {e}"}}

    # 对外服务名改写：initialize 响应里的 serverInfo.name 换成
    # BRIDGE_SERVER_NAME（如 hisi-code-mcp），子进程内部名不再暴露。
    _rename_server_info(resp, obj.get(_K_METHOD))
    _rewrite_tools_list(resp, obj.get(_K_METHOD))

    headers = {"Mcp-Session-Id": session.id}
    if resp is None:  # 通知
        return Response(status_code=202, headers=headers)
    return JSONResponse(resp, headers=headers)


@app.delete("/mcp")
async def mcp_delete(request: Request):
    if SHARED_CHILD:
        return Response(status_code=204)  # 会话无服务端资源，无需清理
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
    if SHARED_CHILD:
        pool = _shared
        alive = sum(1 for c in pool._alive()) if pool else 0
        pending = sum(len(c.pending) for c in pool.children) if pool else 0
        return {"ok": True, "mode": "shared", "pool_size": POOL_SIZE,
                "children_alive": alive, "inflight_pending": pending}
    return {"ok": True, "sessions": len(_sessions)}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host=HOST, port=PORT, log_level="warning")
