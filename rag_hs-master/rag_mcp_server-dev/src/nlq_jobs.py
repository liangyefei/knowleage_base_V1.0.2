"""nlq_jobs — 项目导入/重建索引的异步 job 执行。"""
import datetime
import json
import os
import signal
import subprocess
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor

from nlq_constants import (
    log, CBM_BIN, CODEGRAPH_BIN, LOG_DIR, _PROC, _K_COMPLETED_AT, _K_JOB_ID,
    _K_ENC_IGNORE, _K_RB, _K_STAGES, _K_STATUS, _ST_DONE, _ST_FAILED,
    _ST_PENDING, _ST_RUNNING,
)

# ── Job 异步执行（项目导入/重建索引）────────────────────────────────

# CBM 派生规则：<parent_dir_basename>-<project_basename>
def derive_project_name(path: str) -> str:
    parent = os.path.basename(os.path.dirname(os.path.abspath(path)))
    base = os.path.basename(os.path.abspath(path))
    return f"{parent}-{base}"


_jobs: dict[str, dict] = {}
_jobs_lock = threading.Lock()
_executor = ThreadPoolExecutor(max_workers=2, thread_name_prefix="dual-mcp-job")

_CBM_INDEX_TIMEOUT = int(os.environ.get("CBM_INDEX_TIMEOUT", "1800"))   # 30 min
_CODEGRAPH_INIT_TIMEOUT = int(os.environ.get("CODEGRAPH_INIT_TIMEOUT", "1800"))


def _now_iso() -> str:
    return datetime.datetime.now(datetime.timezone.utc).astimezone().isoformat(timespec="seconds")


def _new_job(job_type: str, project_name: str, project_path: str) -> dict:
    job_id = uuid.uuid4().hex[:12]
    job = {
        _K_JOB_ID: job_id,
        "type": job_type,
        "project_name": project_name,
        "project_path": project_path,
        _K_STATUS: "queued",
        "created_at": _now_iso(),
        "started_at": None,
        "completed_at": None,
        "error": None,
        "log_path": os.path.join(LOG_DIR, f"job-{job_id}.log"),
        _K_STAGES: {
            "cbm_index": _ST_PENDING,
            "codegraph_init": _ST_PENDING,
            "codegraph_daemon": _ST_PENDING,
        },
    }
    with _jobs_lock:
        _jobs[job_id] = job
    return job


def _set_stage(job: dict, stage: str, status: str):
    job[_K_STAGES][stage] = status


def _has_running_job_for(project_name: str) -> bool:
    with _jobs_lock:
        for j in _jobs.values():
            if j["project_name"] == project_name and j[_K_STATUS] in ("queued", "running"):
                return True
    return False


def _run_add_project_job(job_id: str, path: str, force_reindex: bool = False):
    """后台三阶段：CBM 索引 → codegraph init → codegraph daemon。"""
    with _jobs_lock:
        job = _jobs.get(job_id)
        if not job:
            return
        job[_K_STATUS] = _ST_RUNNING
        job["started_at"] = _now_iso()

    log_path = job["log_path"]
    project_name = job["project_name"]
    env = {**os.environ, "CODEGRAPH_NO_WATCHDOG": "1"}

    try:
        with open(log_path, "w") as logf:
            logf.write(f"[{ _now_iso()}] start job for {project_name} @ {path}\n")
            logf.flush()

            # Stage 1: CBM index
            _set_stage(job, "cbm_index", _ST_RUNNING)
            already_indexed = False
            if not force_reindex:
                list_check = subprocess.run(
                    [CBM_BIN, "cli", "--json", "list_projects"],
                    capture_output=True, text=True, timeout=30,
                )
                if f'"name":"{project_name}"' in (list_check.stdout or ""):
                    already_indexed = True

            if already_indexed:
                logf.write(f"[{_now_iso()}] CBM: already indexed, skipping\n")
            else:
                logf.write(f"[{_now_iso()}] CBM: indexing (timeout={_CBM_INDEX_TIMEOUT}s)...\n")
                logf.flush()
                r = subprocess.run(
                    [CBM_BIN, "cli", "--json", "index_repository",
                     json.dumps({"repo_path": path})],
                    capture_output=True, text=True,
                    timeout=_CBM_INDEX_TIMEOUT,
                )
                logf.write(r.stdout or "")
                if r.stderr:
                    logf.write("\n--- stderr ---\n")
                    logf.write(r.stderr)
                logf.write("\n")
                logf.flush()
                if r.returncode != 0:
                    raise RuntimeError(
                        f"CBM index_repository failed (rc={r.returncode})"
                    )
            _set_stage(job, "cbm_index", _ST_DONE)

            # Stage 2: codegraph init (skip if .codegraph/codegraph.db exists)
            _set_stage(job, "codegraph_init", _ST_RUNNING)
            cg_db = os.path.join(path, ".codegraph", "codegraph.db")
            if os.path.exists(cg_db) and not force_reindex:
                logf.write(f"[{_now_iso()}] codegraph: already indexed, skipping\n")
            else:
                logf.write(f"[{_now_iso()}] codegraph: init (timeout={_CODEGRAPH_INIT_TIMEOUT}s)...\n")
                logf.flush()
                r = subprocess.run(
                    [CODEGRAPH_BIN, "init", "."],
                    cwd=path, env=env,
                    capture_output=True, text=True,
                    timeout=_CODEGRAPH_INIT_TIMEOUT,
                )
                logf.write(r.stdout or "")
                if r.stderr:
                    logf.write("\n--- stderr ---\n")
                    logf.write(r.stderr)
                logf.write("\n")
                logf.flush()
                if r.returncode != 0:
                    raise RuntimeError(
                        f"codegraph init failed (rc={r.returncode})"
                    )
            _set_stage(job, "codegraph_init", _ST_DONE)

            # Stage 3: codegraph daemon (idempotent)
            _set_stage(job, "codegraph_daemon", _ST_RUNNING)
            daemon_running = False
            for p in os.listdir(_PROC) if os.path.isdir(_PROC) else []:
                if not p.isdigit():
                    continue
                try:
                    with open(f"{_PROC}/{p}/cmdline", _K_RB) as f:
                        cmd = f.read().replace(b"\x00", b" ").decode(errors=_K_ENC_IGNORE)
                    if _is_project_codegraph(cmd, path):
                        daemon_running = True
                        break
                except (OSError, PermissionError):
                    continue
            if daemon_running:
                logf.write(f"[{_now_iso()}] codegraph daemon already running for {path}\n")
            else:
                logf.write(f"[{_now_iso()}] codegraph: starting daemon...\n")
                logf.flush()
                daemon_log = os.path.join(LOG_DIR, f"cg-{os.path.basename(path)}.log")
                daemon_log_fp = open(daemon_log, "ab")
                _track_daemon(subprocess.Popen(
                    [CODEGRAPH_BIN, "serve", "--mcp", "--path", path],
                    stdin=subprocess.DEVNULL,
                    stdout=daemon_log_fp, stderr=daemon_log_fp,
                    env=env, close_fds=True,
                ))
                # 子进程已继承 fd 副本，父进程立即关闭自己的句柄（G.PRM.03）
                daemon_log_fp.close()
                time.sleep(2)
            _set_stage(job, "codegraph_daemon", _ST_DONE)

            job[_K_STATUS] = _ST_DONE
            job[_K_COMPLETED_AT] = _now_iso()
            logf.write(f"[{_now_iso()}] job done\n")

    except subprocess.TimeoutExpired as e:
        job[_K_STATUS] = _ST_FAILED
        job[_K_COMPLETED_AT] = _now_iso()
        job["error"] = f"timeout: {e}"
        # mark the in-progress stage as failed
        for s, st in job[_K_STAGES].items():
            if st == _ST_RUNNING:
                job[_K_STAGES][s] = _ST_FAILED
    except Exception as e:
        job[_K_STATUS] = _ST_FAILED
        job[_K_COMPLETED_AT] = _now_iso()
        job["error"] = str(e)
        for s, st in job[_K_STAGES].items():
            if st == _ST_RUNNING:
                job[_K_STAGES][s] = _ST_FAILED


def _is_project_codegraph(cmd: str, path: str) -> bool:
    """扫描 G.CTL.03：/proc cmdline 是否为指定 path 的 codegraph MCP daemon。"""
    return ("codegraph" in cmd and "serve" in cmd
            and "--mcp" in cmd and path in cmd)


# 长生命周期 codegraph daemon 子进程跟踪：fire-and-forget Popen 的引用一旦
# 丢弃，子进程退出（自然退出或被 _kill_codegraph_daemon_for 杀）后无人
# wait() 即成 zombie——nl_query_server 周期调 reap_daemon_procs() 收尸。
_daemon_procs: list = []
_daemon_procs_lock = threading.Lock()


def _track_daemon(proc) -> None:
    with _daemon_procs_lock:
        _daemon_procs.append(proc)


def reap_daemon_procs() -> int:
    """非阻塞回收已退出的 daemon 子进程。返回本次清出的数量。"""
    reaped = 0
    with _daemon_procs_lock:
        alive = []
        for p in _daemon_procs:
            if p.poll() is None:
                alive.append(p)
            else:
                reaped += 1
        _daemon_procs[:] = alive
    return reaped


def _kill_codegraph_daemon_for(path: str, timeout_sec: int = 5) -> int:
    """杀掉指定 path 的 codegraph daemon。返回杀掉的进程数。"""
    killed = 0
    if not os.path.isdir(_PROC):
        return 0
    for p in list(os.listdir(_PROC)):
        if not p.isdigit():
            continue
        try:
            with open(f"{_PROC}/{p}/cmdline", _K_RB) as f:
                cmd = f.read().replace(b"\x00", b" ").decode(errors=_K_ENC_IGNORE)
            if _is_project_codegraph(cmd, path):
                pid = int(p)
                os.kill(pid, signal.SIGTERM)
                killed += 1
        except (OSError, PermissionError, ProcessLookupError):
            continue
    # 给 5s 优雅退出
    deadline = time.time() + timeout_sec
    while time.time() < deadline:
        time.sleep(0.5)
        # 检查是否还有
        any_alive = False
        for p in list(os.listdir(_PROC)):
            if not p.isdigit():
                continue
            try:
                with open(f"{_PROC}/{p}/cmdline", _K_RB) as f:
                    cmd = f.read().replace(b"\x00", b" ").decode(errors=_K_ENC_IGNORE)
                if _is_project_codegraph(cmd, path):
                    any_alive = True
                    break
            except (OSError, PermissionError):
                continue
        if not any_alive:
            break
    # 还在就 SIGKILL
    for p in list(os.listdir(_PROC)):
        if not p.isdigit():
            continue
        try:
            with open(f"{_PROC}/{p}/cmdline", _K_RB) as f:
                cmd = f.read().replace(b"\x00", b" ").decode(errors=_K_ENC_IGNORE)
            if _is_project_codegraph(cmd, path):
                pid = int(p)
                os.kill(pid, signal.SIGKILL)
        except (OSError, PermissionError, ProcessLookupError):
            continue
    return killed


