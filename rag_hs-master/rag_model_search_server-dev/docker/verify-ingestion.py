"""ingestion 镜像验证：env + 依赖 + startup import"""
# pylint: disable=no-use-sys-path-insert
import os
import sys

# 镜像 WORKDIR 是 /workspace/haisi_rag_models_server，startup_ingestion.py 在那里
sys.path.insert(0, "/workspace/haisi_rag_models_server")

from configs.logger_manager import logger

logger.info("=" * 60)
logger.info("ingestion 镜像验证")
logger.info("=" * 60)

# 1. HAISI_RUN_MODE
mode = os.environ.get("HAISI_RUN_MODE")
logger.info("[1] HAISI_RUN_MODE = {}", mode)
if mode != "ingest":
    raise ValueError(f"期望 ingest，实际 {mode}")

# 2. ingestion 必需依赖
logger.info("\n[2] 检查 ingestion 必需依赖")
import unstructured
from importlib.metadata import version as _pkg_version
logger.info("  unstructured {} OK", _pkg_version('unstructured'))

import torch
logger.info("  torch {} cuda_available={} OK", torch.__version__, torch.cuda.is_available())

import kafka
logger.info("  kafka-python OK")

import tree_sitter_language_pack
logger.info("  tree-sitter-language-pack OK")

import xlrd
logger.info("  xlrd {} OK", xlrd.__version__)

# 3. startup_ingestion 可 import
logger.info("\n[3] 检查 startup_ingestion import")
import startup_ingestion
logger.info("  startup_ingestion import OK")

# 4. 检查 startup_ingestion 设的 argv
logger.info("\n[4] startup_ingestion 设的 sys.argv: {}", sys.argv)

logger.info("\n" + "=" * 60)
logger.info("ingestion 镜像验证通过")
logger.info("=" * 60)