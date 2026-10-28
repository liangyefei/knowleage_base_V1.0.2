#!/usr/bin/env python3
# pylint: disable=no-use-sys-path-insert
import sys
sys.path.insert(0, '/mnt/d/Code/haisi/haisi_rag_models_server')
from configs import KB_CONFIG
from configs.logger_manager import logger
config = KB_CONFIG.get("INTERNET_SEARCH", {})
logger.info("enable: {}", config.get('enable'))
logger.info("config: {}", config)