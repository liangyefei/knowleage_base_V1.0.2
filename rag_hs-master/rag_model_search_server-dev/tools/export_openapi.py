#!/usr/bin/env python3
"""
导出 FastAPI 接口文档为单文件离线 HTML。

用法（在项目根目录运行）：
  python3 tools/export_openapi.py --base http://192.168.80.250:37861 -o docs/models_api.html

原理：
  1. 从运行中的服务拉 /openapi.json（FastAPI 自带，MakeFastAPIOffline 未改此路径）
  2. 把 spec + server/static 下的 swagger-ui JS/CSS 全部内联进一个 HTML
  3. 同事浏览器双击即可查看，零 CDN 依赖、零网络
"""
import argparse
import json
import logging
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STATIC_DIR = ROOT / "server" / "static"

# 用 __TOKEN__ 占位 + str.replace，避免 .format 把 JS/CSS/spec 里的 {} 当占位符解析
HTML_TEMPLATE = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>__TITLE__</title>
<style>
__CSS__
body { margin: 0; }
.topbar { display: none !important; }
</style>
</head>
<body>
<div id="swagger-ui"></div>
<script>
__JS__
</script>
<script>
window.onload = function () {
  SwaggerUIBundle({
    spec: __SPEC__,
    dom_id: "#swagger-ui",
    deepLinking: true,
    presets: [SwaggerUIBundle.presets.apis],
    layout: "BaseLayout",
    defaultModelsExpandDepth: 1
  });
};
</script>
</body>
</html>
"""


def fetch_spec(base_url: str, openapi_path: str) -> dict:
    url = base_url.rstrip("/") + openapi_path
    logging.info("[1/3] 拉取 OpenAPI spec: %s", url)
    try:
        req = urllib.request.Request(url, headers={"Accept": "application/json"})
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = resp.read().decode("utf-8")
    except Exception as e:
        raise RuntimeError(
            f"拉取失败: {e}\n请确认服务在运行且地址正确（能访问 {base_url.rstrip('/')}/docs 就能访问 /openapi.json）。"
        ) from e
    spec = json.loads(data)
    paths = spec.get("paths", {})
    logging.info("     成功，标题=%s，共 %s 个路径", spec.get('info', {}).get('title', '?'), len(paths))
    return spec


def read_static(name: str) -> str:
    p = STATIC_DIR / name
    if not p.exists():
        raise FileNotFoundError(
            f"找不到静态资源: {p}\n该项目应自带 server/static/swagger-ui-bundle.js 和 swagger-ui.css。"
        )
    return p.read_text(encoding="utf-8")


def build_html(spec: dict, title_override: str = None) -> str:
    css = read_static("swagger-ui.css")
    js = read_static("swagger-ui-bundle.js")
    title = title_override or spec.get("info", {}).get("title", "API 文档")
    # 防止 spec 字符串值里的 </script> 断 HTML；JSON 中 <\/ 等价 </，JS 解析正常
    spec_json = json.dumps(spec, ensure_ascii=False).replace("</", "<\\/")
    return (HTML_TEMPLATE
            .replace("__TITLE__", title)
            .replace("__CSS__", css)
            .replace("__JS__", js)
            .replace("__SPEC__", spec_json))


def main():
    ap = argparse.ArgumentParser(description="导出 FastAPI 接口文档为单文件离线 HTML")
    ap.add_argument("--base", required=True, help="服务基地址，如 http://192.168.80.250:37861")
    ap.add_argument("--openapi-path", default="/openapi.json", help="OpenAPI 路径，默认 /openapi.json")
    ap.add_argument("-o", "--out", default="docs/api.html", help="输出文件路径，默认 docs/api.html")
    ap.add_argument("--title", default=None, help="覆盖文档标题（默认用 spec 里的 info.title）")
    args = ap.parse_args()

    spec = fetch_spec(args.base, args.openapi_path)

    logging.info("[2/3] 内联 Swagger UI 静态资源（server/static/swagger-ui-*）")
    html = build_html(spec, args.title)

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(html, encoding="utf-8")
    logging.info("[3/3] 写出: %s（%s KB）", out, len(html) // 1024)
    logging.info("\n完成。把 %s 发给同事，浏览器双击即可查看。", out)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, FileNotFoundError) as e:
        sys.exit(str(e))
