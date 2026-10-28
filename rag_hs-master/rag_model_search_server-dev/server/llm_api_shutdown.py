"""
调用示例：
python llm_api_shutdown.py --serve all
可选"all","api","webui"， all表示停止所有服务
"""
import argparse
import os
import shutil
import sys
import subprocess

from configs.logger_manager import logger

sys.path.append(os.path.dirname(os.path.dirname(__file__)))


def main():
    _ALL_KEY = 'all'
    parser = argparse.ArgumentParser()
    parser.add_argument("--serve", choices=[_ALL_KEY, "api", "webui"], default=_ALL_KEY)
    args = parser.parse_args()

    # Run ps to get all processes, filter in Python to avoid shell=True
    # This eliminates shell injection risk while maintaining the same functionality
    ps_bin = shutil.which("ps") or "ps"
    result = subprocess.run(
        [ps_bin, "-eo", "user,pid,cmd"],
        capture_output=True,
        text=True,
        check=True
    )

    # Filter processes matching the pattern
    targets = []
    patterns = []
    if args.serve == _ALL_KEY:
        patterns = ["server/api.py", "webui.py"]
    elif args.serve == "api":
        patterns = ["server/api.py"]
    elif args.serve == "webui":
        patterns = ["webui.py"]

    for line in result.stdout.splitlines():
        line = line.strip()
        if not line:
            continue
        # Split into fields (user, pid, cmd)
        parts = line.split(None, 2)
        if len(parts) < 3:
            continue
        pid = parts[1]
        cmd = parts[2]
        # Check if any pattern matches
        if any(p in cmd for p in patterns):
            # Exclude ourselves
            if str(os.getpid()) == pid:
                continue
            targets.append(pid)

    if not targets:
        logger.info("No matching processes found to shutdown")
        return 0

    logger.info("Found {:d} processes to shutdown: {}", len(targets), ", ".join(targets))

    # Kill the processes
    kill_bin = shutil.which("kill") or "kill"
    for pid in targets:
        try:
            subprocess.run([kill_bin, "-9", pid], check=True)
            logger.debug("Killed pid {}", pid)
        except subprocess.CalledProcessError:
            logger.warning("Failed to kill pid {} (already exited?)", pid)

    logger.info("llm api sever --{} has been shutdown!", args.serve)
    return 0


if __name__ == "__main__":
    sys.exit(main())
