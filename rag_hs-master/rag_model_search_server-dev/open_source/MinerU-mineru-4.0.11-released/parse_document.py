"""MinerU API 客户端：上传文档 -> 解析 -> 下载结果"""

import argparse
import json
import re
import sys
import time
from pathlib import Path
import requests


def clean_html_tags(md_text: str) -> str:
    """去除 markdown 中的 HTML 标签，保留纯文本内容"""
    # 去除 <table> 块，提取纯文本内容
    def table_to_text(match):
        inner = match.group(1)
        # 去除所有 HTML 标签，把 </td></tr> 等替换为空格或换行
        inner = re.sub(r"</tr>", "\n", inner)
        inner = re.sub(r"</td>", " | ", inner)
        inner = re.sub(r"<[^>]+>", "", inner)
        lines = [ln.strip() for ln in inner.strip().split("\n") if ln.strip()]
        return "\n".join(lines)

    md_text = re.sub(r"<table[^>]*>(.*?)</table>", table_to_text, md_text, flags=re.DOTALL)

    # 去除其他常见 HTML 标签（sub/sup/span/div 等），保留内容
    md_text = re.sub(r"</?(?:sub|sup|span|div|font|b|i|strong|em|u|s|small|big|code|pre|br|hr|p|a|img|figure|figcaption|label|input|select|option|button|form|ul|ol|li|h[1-6])[^>]*>", "", md_text)

    # 清理多余空行（3 个以上连续空行压缩为 2 个）
    md_text = re.sub(r"\n{3,}", "\n\n", md_text)

    return md_text.strip()

# 默认待解析文件路径
DEFAULT_INPUT_FILE = r"D:/任务/海思知识库项目/解析文档/社区验收硬件资料包/社区资料/HardWare/硬件设计资料-原理图&PCB&BOM/Sunniwell CV610 DevBoard/Sunniwell CV610 DevBoard_SCH.pdf"

# 默认保存目录
DEFAULT_OUTPUT_DIR = r"D:/任务/海思知识库项目/代码/mineru结果/Sunniwell CV610 DevBoard_SCH"


def parse_document(
    file_path,
    output_dir,
    api_url: str = "http://192.168.80.250:16601",
    tier: str = "advanced",
    ocr_mode: str = "ocr",
    poll_interval: int = 3,
    timeout: int = 1200,
) -> None:
    file_path = Path(file_path)
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    if not file_path.exists():
        print(f"文件不存在: {file_path}")
        sys.exit(1)

    file_size = file_path.stat().st_size
    file_name = file_path.name
    print(f"开始处理: {file_name} ({file_size} bytes)")

    # 根据扩展名推断 MIME 类型
    ext = file_path.suffix.lower()
    mime_types = {
        ".pdf": "application/pdf",
        ".doc": "application/msword",
        ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        ".ppt": "application/vnd.ms-powerpoint",
        ".pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        ".xls": "application/vnd.ms-excel",
        ".xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        ".html": "text/html",
        ".htm": "text/html",
        ".csv": "text/csv",
        ".tsv": "text/tab-separated-values",
        ".epub": "application/epub+zip",
        ".rtf": "application/rtf",
        ".odt": "application/vnd.oasis.opendocument.text",
        ".ods": "application/vnd.oasis.opendocument.spreadsheet",
        ".odp": "application/vnd.oasis.opendocument.presentation",
        ".ofd": "application/ofd",
        ".png": "image/png",
        ".jpg": "image/jpeg",
        ".jpeg": "image/jpeg",
        ".webp": "image/webp",
        ".gif": "image/gif",
        ".bmp": "image/bmp",
        ".tiff": "image/tiff",
        ".tif": "image/tiff",
    }
    mime_type = mime_types.get(ext, "application/octet-stream")

    # 1. 创建上传
    print("[1/5] 创建上传会话...")
    resp = requests.post(
        f"{api_url}/v1/uploads",
        headers={"Content-Type": "application/json"},
        json={
            "filename": file_name,
            "bytes": file_size,
            "mime_type": mime_type,
            "purpose": "parse",
        },
    )
    resp.raise_for_status()
    upload_data = resp.json()
    upload_id = upload_data["id"]
    file_info = upload_data.get("file") or {}
    file_id = file_info.get("id")
    print(f"  upload_id={upload_id}, file_id={file_id}")

    # 如果上传未直接完成，需要上传文件内容
    if upload_data.get("status") != "completed":
        upload_url = upload_data.get("upload_url", "")
        if upload_url.startswith("/"):
            upload_url = f"{api_url}{upload_url}"

        print(f"[2/5] 上传文件内容到 {upload_url}...")
        with file_path.open("rb") as f:
            put_resp = requests.put(upload_url, data=f.read())
        put_resp.raise_for_status()

        # 完成上传
        print("  完成上传...")
        complete_resp = requests.post(f"{api_url}/v1/uploads/{upload_id}/complete")
        complete_resp.raise_for_status()
        file_id = (complete_resp.json().get("file") or {}).get("id")
        print(f"  file_id={file_id}")
    else:
        print("[2/5] 文件已秒传（服务器已有相同文件）")

    # 3. 提交解析任务
    print(f"[3/5] 提交解析任务 (tier={tier}, ocr_mode={ocr_mode})...")
    job_resp = requests.post(
        f"{api_url}/v1/parse/jobs",
        headers={"Content-Type": "application/json"},
        json={
            "files": [{"source": {"type": "file_id", "file_id": file_id}}],
            "output_formats": ["markdown", "zip"],
            "tier": tier,
            "ocr_mode": ocr_mode,
        },
    )
    job_resp.raise_for_status()
    job_data = job_resp.json()
    job_id = job_data["job_id"]
    print(f"  job_id={job_id}")

    # 4. 轮询状态
    print(f"[4/5] 等待解析完成 (超时 {timeout}s)...")
    start_time = time.time()
    while True:
        elapsed = int(time.time() - start_time)
        if elapsed > timeout:
            print(f"  超时！已等待 {elapsed}s")
            sys.exit(1)

        status_resp = requests.get(f"{api_url}/v1/parse/jobs/{job_id}")
        status_resp.raise_for_status()
        status_data = status_resp.json()
        status = status_data.get("status", "")
        progress = status_data.get("progress", {})
        print(f"  [{elapsed}s] status={status}, completed={progress.get('completed', 0)}/{progress.get('total', 1)}")

        if status in ("completed", "partial", "failed", "canceled"):
            break

        time.sleep(poll_interval)

    if status == "failed":
        error = status_data.get("files", [{}])[0].get("error", {})
        print(f"  解析失败: {error.get('code', 'unknown')} - {error.get('message', '')}")
        sys.exit(1)

    # 5. 下载结果
    print("[5/5] 下载结果...")
    output_files = status_data.get("files", [{}])[0].get("output_files", {})

    # 下载 zip，解压后只保留 .md 和 images/，直接放到输出目录
    if "zip" in output_files:
        import io
        import zipfile

        zip_file_id = output_files["zip"]["file_id"]
        zip_resp = requests.get(f"{api_url}/v1/files/{zip_file_id}/content")
        zip_resp.raise_for_status()

        # 解压，只保留 .md 文件和 images/ 目录，直接放到 DEFAULT_OUTPUT_DIR 下
        images_dir = output_dir / "images"
        images_dir.mkdir(parents=True, exist_ok=True)

        with zipfile.ZipFile(io.BytesIO(zip_resp.content), "r") as zin:
            for item in zin.infolist():
                name = item.filename
                # 只保留 .md 文件和 images/ 下的文件
                if name.endswith(".md"):
                    md_text = zin.read(name).decode("utf-8", errors="replace")
                    cleaned_md = clean_html_tags(md_text)
                    (output_dir / "markdown.md").write_text(cleaned_md, encoding="utf-8")
                elif name.startswith("images/") and not name.endswith("/"):
                    img_data = zin.read(name)
                    img_name = Path(name).name
                    (images_dir / img_name).write_bytes(img_data)

        print(f"  结果已保存到: {output_dir}")
        print(f"    - {output_dir}/markdown.md")
        print(f"    - {output_dir}/images/")

    if "markdown" not in output_files and "zip" not in output_files:
        print(f"  警告: 未找到输出文件，output_files={json.dumps(output_files, ensure_ascii=False)}")

    print("完成!")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="MinerU 文档解析客户端")
    parser.add_argument("file", nargs="?", default=DEFAULT_INPUT_FILE, help=f"待解析的文件路径 (默认: {DEFAULT_INPUT_FILE})")
    parser.add_argument("-o", "--output", default=DEFAULT_OUTPUT_DIR, help=f"结果保存目录 (默认: {DEFAULT_OUTPUT_DIR})")
    parser.add_argument("--api", default="http://192.168.80.250:16601", help="MinerU API 地址")
    parser.add_argument("--tier", default="advanced", choices=["flash", "basic", "standard", "advanced"], help="解析档位 (默认: advanced)")
    parser.add_argument("--ocr-mode", default="ocr", choices=["auto", "txt", "ocr"], help="OCR 模式 (默认: ocr，强制 OCR)")
    parser.add_argument("--timeout", type=int, default=1200, help="解析超时秒数 (默认: 1200)")
    args = parser.parse_args()

    parse_document(
        file_path=args.file,
        output_dir=args.output,
        api_url=args.api,
        tier=args.tier,
        ocr_mode=args.ocr_mode,
        timeout=args.timeout,
    )
