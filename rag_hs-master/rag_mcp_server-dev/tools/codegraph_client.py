#!/usr/bin/env python3
"""
CodeGraph MCP 客户端（JSON-RPC over stdio）

用法:
    from codegraph_client import CodeGraphClient
    cg = CodeGraphClient("/data/deploy_code/codegraph/dist/bin/codegraph.js")
    src = cg.get_node_source("uart_init", project_path="/data/hunan/aiutil")
    # 或
    flow = cg.explore("uart_init main", project_path="/data/hunan/aiutil")
    cg.close()
"""

import json
import logging
import os
import shutil
import subprocess
import sys
import time

_NODE_BIN = shutil.which("node") or "node"  # 扫描 G.EDV.05：外部可执行用绝对路径

log = logging.getLogger("codegraph_client")
log.propagate = False
_h = logging.StreamHandler(sys.stderr)
_h.setFormatter(logging.Formatter("%(message)s"))
log.addHandler(_h)


class CodeGraphClient:
    """Python 客户端，通过 stdio JSON-RPC 调用 CodeGraph MCP 服务。"""

    def __init__(self, binary: str = None):
        # Priority: explicit arg > $CODEGRAPH_BINARY > common container paths > legacy deploy_code path
        candidates = [
            "/opt/codegraph/dist/bin/codegraph.js",
            "/data/codegraph/dist/bin/codegraph.js",
            "/data/deploy_code/codegraph/dist/bin/codegraph.js",
        ]
        env_path = os.environ.get("CODEGRAPH_BINARY")
        if env_path:
            self.binary = env_path
        elif binary:
            self.binary = binary
        else:
            self.binary = next(
                (p for p in candidates if os.path.exists(p)),
                candidates[-1],  # fall back to legacy path (will fail loudly downstream)
            )
        self._next_id = 1
        self.proc: subprocess.Popen | None = None

    def start(self):
        """启动 CodeGraph MCP 子进程，完成 initialize 握手。"""
        if self.proc:
            return
        self.proc = subprocess.Popen(
            [_NODE_BIN, self.binary, "serve", "--mcp"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL, bufsize=0, text=True,
        )
        # MCP initialize
        resp = self._call("initialize", {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "py-codegraph-client", "version": "1.0"},
        })
        if resp.get("serverInfo", {}).get("name") != "codegraph":
            raise RuntimeError(f"CodeGraph 握手失败: {resp}")
        # 发送 initialized 通知
        self._notify("notifications/initialized")

    # ── 公开方法 ──    # ── 公开方法 ──

    def explore(self, query: str, project_path: str, max_files: int = 6) -> str:
        """codegraph_explore — 一次调用返回符号源码 + 调用路径。"""
        return self._tool("codegraph_explore", {
            "query": query, "projectPath": project_path,
            "maxFiles": max_files,
        })

    def get_source(self, symbol: str, project_path: str, file: str = None) -> str:
        """codegraph_node — 获取符号源码体（includeCode=true）。"""
        args = {"symbol": symbol, "projectPath": project_path, "includeCode": True}
        if file:
            args["file"] = file
        return self._tool("codegraph_node", args)

    def close(self):
        if self.proc:
            try:
                self.proc.stdin.close()
            except Exception as e:
                log.debug("stdin close: %s", e)
            try:
                self.proc.wait(timeout=5)
            except Exception:
                self.proc.kill()
            self.proc = None

    def _send(self, msg: dict):
        line = json.dumps(msg, ensure_ascii=False) + "\n"
        self.proc.stdin.write(line)
        self.proc.stdin.flush()

    def _recv(self) -> dict | None:
        while True:
            line = self.proc.stdout.readline()
            if not line:
                return None
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                continue
            if "id" in obj:
                return obj

    def _call(self, method: str, params: dict | None = None) -> dict:
        rid = self._next_id
        self._next_id += 1
        msg = {"jsonrpc": "2.0", "id": rid, "method": method}
        if params:
            msg["params"] = params
        self._send(msg)
        resp = self._recv()
        if resp is None:
            raise RuntimeError("CodeGraph 进程断开")
        if "error" in resp:
            raise RuntimeError(f"CodeGraph error: {resp['error']}")
        return resp.get("result", {})

    def _notify(self, method: str, params: dict | None = None):
        msg = {"jsonrpc": "2.0", "method": method}
        if params:
            msg["params"] = params
        self._send(msg)

    def _tool(self, name: str, args: dict) -> str:
        result = self._call("tools/call", {"name": name, "arguments": args})
        for item in result.get("content", []):
            if item.get("type") == "text":
                return item.get("text", "")
        return ""


# ── 全局单例（进程复用）─────────────────────────────────────────────


_g_client: CodeGraphClient | None = None


def get_client() -> CodeGraphClient:
    """获取全局 CodeGraph 客户端（首次调用时启动进程，后续复用）。"""
    global _g_client
    if _g_client is None:
        _g_client = CodeGraphClient()
        t0 = time.time()
        _g_client.start()
        log.info(f"[CodeGraph] 服务已连接 ({(time.time()-t0)*1000:.0f}ms)")
    return _g_client


def close_client():
    global _g_client
    if _g_client:
        _g_client.close()
        _g_client = None
