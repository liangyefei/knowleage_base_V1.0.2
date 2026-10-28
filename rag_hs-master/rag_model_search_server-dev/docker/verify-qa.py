"""qa 镜像验证：env + 依赖 + startup import + 不该有的依赖"""
# pylint: disable=no-use-sys-path-insert
import importlib
import os
import sys

# 镜像 WORKDIR 是 /workspace/haisi_rag_models_server，startup_qa.py 在那里
sys.path.insert(0, "/workspace/haisi_rag_models_server")

from configs.logger_manager import logger

logger.info("=" * 60)
logger.info("qa 镜像验证")
logger.info("=" * 60)

# 1. HAISI_RUN_MODE
mode = os.environ.get("HAISI_RUN_MODE")
logger.info(f"[1] HAISI_RUN_MODE = {mode}")
if mode != "qa":
    raise ValueError(f"期望 qa，实际 {mode}")

# 2. qa 必需依赖
logger.info("\n[2] 检查 qa 必需依赖")
import streamlit
logger.info("  streamlit {} OK", streamlit.__version__)

import uvicorn
logger.info("  uvicorn {} OK", uvicorn.__version__)

import uvloop
logger.info("  uvloop OK")

# 3. qa 不该有的依赖（瘦身验证）
logger.info("\n[3] 检查 qa 不该有的依赖（瘦身验证）")
for pkg in ["unstructured", "kafka", "tree_sitter_language_pack", "xlrd"]:
    try:
        importlib.import_module(pkg)
        logger.info("  WARN: {} 仍在 qa 镜像（应已移除）", pkg)
    except ImportError:
        logger.info("  OK: {} 不在 qa 镜像（已瘦身）", pkg)

# torch 在 qa 里吗？qa 不装 unstructured，但 requirements-qa.txt 可能间接拉 torch
try:
    import torch
    logger.info("  INFO: torch {} 在 qa 镜像（可能 langchain 拉的）", torch.__version__)
except ImportError:
    logger.info("  OK: torch 不在 qa 镜像")

# 4. startup_qa 可 import
logger.info("\n[4] 检查 startup_qa import")
import startup_qa
logger.info("  startup_qa import OK")

logger.info("\n[5] startup_qa 设的 sys.argv: {}", sys.argv)

logger.info("\n" + "=" * 60)
logger.info("qa 镜像验证通过")
logger.info("=" * 60)