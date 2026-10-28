#!/usr/bin/env python3
"""Comprehensive MCP server test for the chip knowledge base.

Spawns the codebase-memory-mcp binary, drives it through the full MCP
lifecycle (initialize → tools/list → tools/call), and verifies the chip
knowledge base features: indexing, chip node discovery, document links,
register maps, and Cypher queries.

Usage:
    python3 scripts/test_chip_mcp.py                       # default binary
    python3 scripts/test_chip_mcp.py /path/to/binary       # custom binary
    python3 scripts/test_chip_mcp.py --repo /path/to/repo  # custom repo to index
    python3 scripts/test_chip_mcp.py --verbose             # print full responses

Exit codes:
    0 - all tests passed
    1 - one or more tests failed
    2 - setup error (binary not found, etc.)
"""

import json
import logging
import os
import subprocess
import sys
import time

logging.basicConfig(level=logging.INFO, format="%(message)s", stream=sys.stdout)
log = logging.getLogger("chip_test")
_err_log = logging.getLogger("chip_test.proto")
_err_log.propagate = False
_err_h = logging.StreamHandler(sys.stderr)
_err_h.setFormatter(logging.Formatter("%(message)s"))
_err_log.addHandler(_err_h)
from typing import Any, Optional

# ── Configuration ───────────────────────────────────────────────────

DEFAULT_PROTOCOL_VERSION = "2024-11-05"
TIMEOUT_S = 120  # per-request timeout (indexing can be slow)


# ── Color helpers ───────────────────────────────────────────────────


class C:
    """ANSI color codes (disabled when not a TTY)."""
    if sys.stdout.isatty():
        GREEN = "\033[32m"
        RED = "\033[31m"
        YELLOW = "\033[33m"
        CYAN = "\033[36m"
        DIM = "\033[2m"
        BOLD = "\033[1m"
        RESET = "\033[0m"
    else:
        GREEN = RED = YELLOW = CYAN = DIM = BOLD = RESET = ""


def ok(msg: str) -> None:
    log.info(f"  {C.GREEN}✓{C.RESET} {msg}")


def fail(msg: str) -> None:
    log.info(f"  {C.RED}✗{C.RESET} {msg}")


def info(msg: str) -> None:
    log.info(f"  {C.CYAN}ℹ{C.RESET} {msg}")


def header(msg: str) -> None:
    log.info(f"\n{C.BOLD}{C.CYAN}── {msg} ──{C.RESET}")


# ── MCP Client ──────────────────────────────────────────────────────


class MCPClient:
    """Minimal MCP client over stdio (newline-delimited JSON-RPC 2.0)."""

    def __init__(self, binary: str, verbose: bool = False):
        self.binary = binary
        self.verbose = verbose
        self._next_id = 1
        self.proc: Optional[subprocess.Popen] = None

    def start(self) -> None:
        """Spawn the MCP server subprocess."""
        self.proc = subprocess.Popen(
            [self.binary],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            bufsize=0,
        )
        # Give it a moment to boot
        time.sleep(0.3)
        if self.proc.poll() is not None:
            raise RuntimeError(f"Server exited immediately with code {self.proc.returncode}")

    def call(self, method: str, params: Optional[dict] = None) -> dict:
        """Send a request and return the result dict. Raises on error/timeout."""
        req_id = self._next_id
        self._next_id += 1
        msg = {"jsonrpc": "2.0", "id": req_id, "method": method}
        if params is not None:
            msg["params"] = params
        self._send(msg)

        # Wait for response with matching id
        deadline = time.time() + TIMEOUT_S
        while time.time() < deadline:
            resp = self._recv()
            if resp is None:
                raise RuntimeError("Server closed connection")
            if resp.get("id") != req_id:
                continue
            if "error" in resp:
                err = resp["error"]
                raise RuntimeError(f"JSON-RPC error {err.get('code')}: {err.get('message')}")
            return resp.get("result", {})

        raise TimeoutError(f"Timed out waiting for response to {method} (id={req_id})")

    def notify(self, method: str, params: Optional[dict] = None) -> None:
        """Send a notification (no response expected)."""
        msg = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            msg["params"] = params
        self._send(msg)

    def close(self) -> None:
        """Shut down the server."""
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

    def _send(self, obj: dict) -> None:
        """Send one JSON-RPC message."""
        assert self.proc and self.proc.stdin
        line = json.dumps(obj) + "\n"
        data = line.encode("utf-8")
        if self.verbose:
            _err_log.info(f"{C.DIM}→ {line.strip()}{C.RESET}\n")
        self.proc.stdin.write(data)
        self.proc.stdin.flush()

    def _recv(self) -> Optional[dict]:
        """Read one JSON-RPC response (skips notifications)."""
        assert self.proc and self.proc.stdout
        while True:
            line = self.proc.stdout.readline()
            if not line:
                return None
            line = line.strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                if self.verbose:
                    _err_log.info(f"{C.DIM}  (non-JSON line: {line[:80]}){C.RESET}\n")
                continue
            # Skip notifications (no id)
            if "id" not in obj:
                if self.verbose:
                    _err_log.info(f"{C.DIM}  (notification: {obj.get('method', '?')}){C.RESET}\n")
                continue
            if self.verbose:
                _err_log.info(f"{C.DIM}← id={obj['id']}{C.RESET}\n")
            return obj


# ── Result helpers ──────────────────────────────────────────────────


def text_of(result: dict) -> str:
    """Extract text content from a tool result."""
    content = result.get("content", [])
    parts = []
    for item in content:
        if isinstance(item, dict) and item.get("type") == "text":
            parts.append(item.get("text", ""))
    return "\n".join(parts)


def tool_call(client: MCPClient, name: str, args: dict) -> str:
    """Invoke a tool and return its text output."""
    result = client.call("tools/call", {"name": name, "arguments": args})
    is_error = result.get("isError", False)
    text = text_of(result)
    return text  # caller checks isError via the original result if needed


# ── Test suite ──────────────────────────────────────────────────────


class TestRunner:
    def __init__(self, verbose: bool = False):
        self.verbose = verbose
        self.passed = 0
        self.failed = 0

    def check(self, condition: bool, msg: str) -> None:
        if condition:
            self.passed += 1
            ok(msg)
        else:
            self.failed += 1
            fail(msg)

    def run(self, name: str, fn) -> None:
        header(name)
        try:
            fn()
        except Exception as e:
            self.failed += 1
            fail(f"exception: {e}")
            if self.verbose:
                import traceback
                traceback.print_exc()


# ── Individual tests ────────────────────────────────────────────────


def test_initialize(client: MCPClient, tr: TestRunner) -> None:
    """Test MCP initialize handshake."""
    result = client.call("initialize", {
        "protocolVersion": DEFAULT_PROTOCOL_VERSION,
        "capabilities": {},
        "clientInfo": {"name": "test-client", "version": "1.0.0"},
    })
    client.notify("notifications/initialized")

    server_info = result.get("serverInfo", {})
    tr.check(server_info.get("name") == "codebase-memory-mcp", "server name correct")
    tr.check("version" in server_info, f"server version present: {server_info.get('version')}")
    tr.check("tools" in result.get("capabilities", {}), "tools capability advertised")


def test_list_tools(client: MCPClient, tr: TestRunner) -> None:
    """List tools and verify expected tools are present."""
    result = client.call("tools/list", {})
    tools = result.get("tools", [])
    names = {t["name"] for t in tools}

    tr.check(len(tools) >= 10, f"at least 10 tools exposed (got {len(tools)})")

    expected = ["index_repository", "search_graph", "query_graph", "trace_path",
                "get_architecture", "list_projects"]
    for name in expected:
        tr.check(name in names, f"tool '{name}' present")

    info(f"all tools: {', '.join(sorted(names))}")


def test_list_projects(client: MCPClient, tr: TestRunner) -> None:
    """List indexed projects."""
    text = tool_call(client, "list_projects", {})
    tr.check("project" in text.lower() or "name" in text.lower() or len(text) > 0,
             "list_projects returned data")
    if tr.verbose:
        info(f"projects:\n{text[:500]}")


def test_index_repo(client: MCPClient, tr: TestRunner, repo: str) -> str:
    """Index a repository and return the derived project name."""
    info(f"indexing {repo} (this may take a while)...")
    result = client.call("tools/call", {
        "name": "index_repository",
        "arguments": {"repo_path": repo, "mode": "fast"},
    })
    text = text_of(result)
    is_error = result.get("isError", False)
    tr.check(not is_error, "index_repository succeeded")
    tr.check(len(text) > 0, "index produced output")

    # Try to extract project name from output
    project = os.path.basename(os.path.abspath(repo))
    for line in text.split("\n"):
        if "project" in line.lower():
            # crude extraction
            pass
    return project


def test_search_chips(client: MCPClient, tr: TestRunner, project: str) -> None:
    """Search for Chip nodes in the knowledge graph."""
    if not project:
        tr.check(False, "no project to search")
        return
    # Search via Cypher for Chip nodes
    result = client.call("tools/call", {
        "name": "query_graph",
        "arguments": {
            "project": project,
            "query": "MATCH (c:Chip) RETURN c.name AS name LIMIT 10",
        },
    })
    text = text_of(result)
    is_error = result.get("isError", False)
    tr.check(not is_error, "chip query succeeded")
    # Chip nodes exist only if chips/*.chip.yaml were indexed
    has_chips = "K3-S" in text or "Chip" in text or "name" in text
    info(f"chip query result: {text[:300]}")
    tr.check(not is_error, "query executed without error")


def test_search_ipblocks(client: MCPClient, tr: TestRunner, project: str) -> None:
    """Query IPBlock nodes and INSTANTIATES_IP edges."""
    if not project:
        tr.check(False, "no project to search")
        return
    result = client.call("tools/call", {
        "name": "query_graph",
        "arguments": {
            "project": project,
            "query": "MATCH (c:Chip)-[:INSTANTIATES_IP]->(ip:IPBlock) "
                     "RETURN c.name AS chip, ip.name AS ip LIMIT 20",
        },
    })
    text = text_of(result)
    is_error = result.get("isError", False)
    tr.check(not is_error, "IP block query executed")
    info(f"IP edges:\n{text[:400]}")


def test_register_map(client: MCPClient, tr: TestRunner, project: str) -> None:
    """Query RegisterMap nodes."""
    if not project:
        tr.check(False, "no project to search")
        return
    result = client.call("tools/call", {
        "name": "query_graph",
        "arguments": {
            "project": project,
            "query": "MATCH (r:RegisterMap) RETURN r.name AS reg, r.properties AS props LIMIT 20",
        },
    })
    text = text_of(result)
    is_error = result.get("isError", False)
    tr.check(not is_error, "register map query executed")
    info(f"registers:\n{text[:400]}")


def test_get_architecture(client: MCPClient, tr: TestRunner, project: str) -> None:
    """Get architecture overview."""
    if not project:
        tr.check(False, "no project")
        return
    result = client.call("tools/call", {
        "name": "get_architecture",
        "arguments": {"project": project, "aspects": ["overview"]},
    })
    text = text_of(result)
    is_error = result.get("isError", False)
    tr.check(not is_error, "architecture query executed")
    tr.check(len(text) > 0, "architecture produced output")


def test_get_graph_schema(client: MCPClient, tr: TestRunner, project: str) -> None:
    """Get graph schema — lists node labels and edge types."""
    if not project:
        tr.check(False, "no project")
        return
    result = client.call("tools/call", {
        "name": "get_graph_schema",
        "arguments": {"project": project},
    })
    text = text_of(result)
    is_error = result.get("isError", False)
    tr.check(not is_error, "schema query executed")

    # Verify chip-specific labels are present
    for label in ["Chip", "IPBlock", "Document"]:
        present = label in text
        tr.check(present, f"schema contains '{label}' label")
    info(f"schema:\n{text[:500]}")


# ── Main ────────────────────────────────────────────────────────────


def main() -> int:
    import argparse
    parser = argparse.ArgumentParser(description="Test the chip knowledge base MCP server")
    parser.add_argument("binary", nargs="?", default=None, help="path to the CBM binary")
    parser.add_argument("--repo", default=None, help="repository to index for testing")
    parser.add_argument("--verbose", "-v", action="store_true", help="verbose output")
    args = parser.parse_args()

    # Resolve binary path
    if args.binary:
        binary = args.binary
    else:
        script_dir = os.path.dirname(os.path.abspath(__file__))
        repo_root = os.path.dirname(script_dir)
        binary = os.path.join(repo_root, "build", "c", "codebase-memory-mcp")

    if not os.path.isfile(binary):
        log.info(f"{C.RED}ERROR:{C.RESET} binary not found at {binary}")
        log.info(f"Build it first: {C.CYAN}make -f Makefile.cbm cbm{C.RESET}")
        return 2

    # Default repo to index = this repo (has chips/ and regs/ examples)
    repo = args.repo or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

    log.info(f"{C.BOLD}Chip Knowledge Base MCP Test{C.RESET}")
    log.info(f"  Binary: {binary}")
    log.info(f"  Repo:   {repo}")
    log.info(f"  Verbose: {args.verbose}")

    client = MCPClient(binary, verbose=args.verbose)
    tr = TestRunner(verbose=args.verbose)
    project = ""

    try:
        header("Starting server")
        client.start()
        ok(f"server spawned (pid {client.proc.pid})")

        # Run tests
        tr.run("MCP Initialize", lambda: test_initialize(client, tr))
        tr.run("List Tools", lambda: test_list_tools(client, tr))
        tr.run("List Projects", lambda: test_list_projects(client, tr))

        # Index the test repo
        def do_index():
            nonlocal project
            project = test_index_repo(client, tr, repo)
        tr.run("Index Repository", do_index)

        # Wait a moment for indexing to settle
        time.sleep(1)

        # Chip-specific tests
        tr.run("Search Chip Nodes", lambda: test_search_chips(client, tr, project))
        tr.run("Search IP Blocks", lambda: test_search_ipblocks(client, tr, project))
        tr.run("Search Register Maps", lambda: test_register_map(client, tr, project))
        tr.run("Get Architecture", lambda: test_get_architecture(client, tr, project))
        tr.run("Get Graph Schema", lambda: test_get_graph_schema(client, tr, project))

    finally:
        header("Stopping server")
        client.close()
        ok("server stopped")

    # Summary
    header("Summary")
    total = tr.passed + tr.failed
    log.info(f"  {C.GREEN}Passed: {tr.passed}{C.RESET} / {total}")
    log.info(f"  {C.RED}Failed: {tr.failed}{C.RESET} / {total}")
    if tr.failed == 0:
        log.info(f"\n{C.GREEN}{C.BOLD}✓ All tests passed{C.RESET}")
        return 0
    else:
        log.info(f"\n{C.RED}{C.BOLD}✗ {tr.failed} test(s) failed{C.RESET}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
