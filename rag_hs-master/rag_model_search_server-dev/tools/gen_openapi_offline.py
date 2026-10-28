#!/usr/bin/env python3
"""
离线导出 FastAPI 接口文档为单文件 HTML（无需启动 uvicorn）。

用法（在项目根目录或容器内运行）：
  python3 tools/gen_openapi_offline.py -o docs/models_api.html

原理：
  1. 调用 create_app() 构建 FastAPI 实例（不启动 uvicorn，不触发 startup 钩子）
  2. 调用 app.openapi() 拿到 OpenAPI 3.1 spec dict
  3. 复用 tools/export_openapi.py 的 build_html() 内联 swagger-ui 静态资源
  4. 写出单文件 HTML，浏览器双击即可查看

与 tools/export_openapi.py 的区别：
  - export_openapi.py 需要服务在运行（拉 /openapi.json）
  - 本脚本不依赖运行中的服务，仅依赖模块能 import 成功
"""
# pylint: disable=no-use-sys-path-insert
import argparse
import logging
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))


def build_spec(title_override: str = None) -> dict:
    from server.api import create_app
    app = create_app()
    spec = app.openapi()
    if title_override:
        spec.setdefault("info", {})["title"] = title_override
    return spec


def main():
    ap = argparse.ArgumentParser(description="离线导出 FastAPI 接口文档为单文件 HTML（无需启动服务）")
    ap.add_argument("-o", "--out", default="docs/models_api.html", help="输出文件路径，默认 docs/models_api.html")
    ap.add_argument("--title", default=None, help="覆盖文档标题（默认用 spec 里的 info.title）")
    args = ap.parse_args()

    logging.info("[1/3] 构建 FastAPI app 并生成 OpenAPI spec（不启动 uvicorn）")
    spec = build_spec(args.title)
    paths = spec.get("paths", {})
    logging.info("     成功，标题=%s，版本=%s", spec.get('info', {}).get('title', '?'), spec.get('info', {}).get('version', '?'))
    logging.info("     共 %s 个路径", len(paths))
    logging.info("     共 %s 个 schema", len(spec.get('components', {}).get('schemas', {})))

    from tools.export_openapi import build_html
    logging.info("[2/3] 内联 Swagger UI 静态资源（server/static/swagger-ui-*）")
    html = build_html(spec, args.title)

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(html, encoding="utf-8")
    logging.info("[3/3] 写出: %s（%s KB）", out, len(html) // 1024)
    logging.info("\n完成。把 %s 发给同事，浏览器双击即可查看。", out)


if __name__ == "__main__":
    main()
