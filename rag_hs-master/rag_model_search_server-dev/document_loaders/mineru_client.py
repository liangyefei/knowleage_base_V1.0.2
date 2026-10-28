"""MinerU API 客户端模块：上传文档 -> 解析 -> 下载结果

从 parse_document.py 重构而来，改为可被 MinerUDocumentLoader 调用的函数模块。
- 配置从 kb_config.yaml 的 MINERU_CONFIG 读取
- 不做 HTML 清洗，保留原始 markdown（交给 VLLMMarkdownLoader 处理）
- 结果存到 KB_ROOT_PATH/mineru_output/{doc_hash}/ 下
"""

import hashlib
import io
import json
import time
import zipfile
from pathlib import Path

import requests

from configs.kb_loader import KB_ROOT_PATH, MINERU_CONFIG
from configs.logger_manager import logger

# ==================== MIME 类型映射 ====================

_MIME_TYPES = {
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


# MinerU API 返回 JSON 中文件 ID / 上传 ID 的字段名
_FIELD_ID = "id"


# ==================== 工具函数 ====================

def compute_doc_hash(file_path: str) -> str:
    """流式计算文件内容 SHA256 前16位，用于缓存目录定位"""
    h = hashlib.sha256()
    with open(file_path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()[:16]


def get_mineru_output_dir(file_path: str) -> Path:
    """获取 MinerU 输出目录：KB_ROOT_PATH/mineru_output/{doc_hash}/"""
    doc_hash = compute_doc_hash(file_path)
    return Path(KB_ROOT_PATH) / "mineru_output" / doc_hash


def is_mineru_cached(file_path: str) -> bool:
    """检查 MinerU 解析结果是否已缓存"""
    output_dir = get_mineru_output_dir(file_path)
    if not output_dir.exists():
        return False
    # 至少要有 markdown 文件和 meta.json
    meta_path = output_dir / "meta.json"
    if not meta_path.exists():
        return False
    # 检查是否有 .md 文件
    md_files = list(output_dir.glob("*.md"))
    return len(md_files) > 0


# ==================== MinerU 解析主函数 ====================

def parse_with_mineru(
    file_path: str,
    output_dir: str | None = None,
) -> Path:
    """
    调用 MinerU API 解析文档（带重试）。

    MinerU 服务端可能因大文件 OOM 重启，重启期间所有在途任务失败。
    本函数在外层包重试逻辑，遇到连接错误/5xx/解析失败时按指数退避重试。

    重试策略：
    - 最大重试次数：3（从配置 MAX_RETRIES 读取，默认 3）
    - 退避基数：30s（从配置 RETRY_BACKOFF_BASE 读取），指数退避 30/60/120s
    - 重试范围：requests.ConnectionError / Timeout / 5xx HTTP / MinerU 返回 failed
    - 不重试的情况：文件不存在、4xx 客户端错误（请求格式错误）、用户主动取消

    Args:
        file_path: 待解析的文件路径（PDF）
        output_dir: 输出目录，默认为 KB_ROOT_PATH/mineru_output/{doc_hash}/

    Returns:
        markdown 文件路径（md_path）
    """
    max_retries = MINERU_CONFIG.get("MAX_RETRIES", 3)
    backoff_base = MINERU_CONFIG.get("RETRY_BACKOFF_BASE", 30)

    last_err = None
    for attempt in range(1, max_retries + 1):
        try:
            logger.info(f"MinerU 调用 attempt {attempt}/{max_retries}: {Path(file_path).name}")
            return _parse_with_mineru_once(file_path, output_dir)
        except FileNotFoundError:
            # 文件不存在，不重试
            raise
        except Exception as e:
            last_err = e
            if attempt < max_retries:
                backoff = backoff_base * (2 ** (attempt - 1))
                logger.warning(
                    f"MinerU 调用失败（attempt {attempt}/{max_retries}），"
                    f"{backoff}s 后重试: {Path(file_path).name}, error={e}"
                )
                time.sleep(backoff)
            else:
                logger.error(
                    f"MinerU 调用失败，已重试 {max_retries} 次仍失败: "
                    f"{Path(file_path).name}, error={e}"
                )
    raise RuntimeError(
        f"MinerU 调用重试 {max_retries} 次仍失败: {Path(file_path).name}, last_error={last_err}"
    )


def _parse_with_mineru_once(
    file_path: str,
    output_dir: str | None = None,
) -> Path:
    """
    单次调用 MinerU API 解析文档（不带重试）。

    流程：上传文件 → 提交解析任务 → 轮询状态 → 下载 zip → 解压

    Args:
        file_path: 待解析的文件路径（PDF）
        output_dir: 输出目录，默认为 KB_ROOT_PATH/mineru_output/{doc_hash}/

    Returns:
        markdown 文件路径（md_path）
    """
    _STATUS_KEY = 'status'
    _FILES_KEY = 'files'
    _FILE_ID_KEY = 'file_id'
    _ZIP_KEY = 'zip'
    _UTF8_KEY = 'utf-8'
    file_path = Path(file_path)

    if not file_path.exists():
        raise FileNotFoundError(f"文件不存在: {file_path}")

    # 输出目录
    if output_dir is None:
        output_dir = get_mineru_output_dir(str(file_path))
    else:
        output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    # 从配置读取参数
    api_url = MINERU_CONFIG.get("API_URL", "http://192.168.0.106:8000")
    tier = MINERU_CONFIG.get("TIER", "advanced")
    ocr_mode = MINERU_CONFIG.get("OCR_MODE", "ocr")
    poll_interval = MINERU_CONFIG.get("POLL_INTERVAL", 5)
    timeout = MINERU_CONFIG.get("TIMEOUT", 7200)

    file_size = file_path.stat().st_size
    file_name = file_path.name
    logger.info(f"MinerU 解析开始: {file_name} ({file_size} bytes)")

    # MIME 类型
    ext = file_path.suffix.lower()
    mime_type = _MIME_TYPES.get(ext, "application/octet-stream")

    # 1. 创建上传会话
    logger.info("[1/5] 创建上传会话...")
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
    upload_id = upload_data[_FIELD_ID]
    file_info = upload_data.get("file") or {}
    file_id = file_info.get(_FIELD_ID)
    logger.info(f"  upload_id={upload_id}, file_id={file_id}")

    # 2. 上传文件内容（如未秒传）
    if upload_data.get(_STATUS_KEY) != "completed":
        upload_url = upload_data.get("upload_url", "")
        if upload_url.startswith("/"):
            upload_url = f"{api_url}{upload_url}"

        logger.info(f"[2/5] 上传文件内容到 {upload_url}...")
        with file_path.open("rb") as f:
            put_resp = requests.put(upload_url, data=f.read())
        put_resp.raise_for_status()

        complete_resp = requests.post(f"{api_url}/v1/uploads/{upload_id}/complete")
        complete_resp.raise_for_status()
        file_id = (complete_resp.json().get("file") or {}).get(_FIELD_ID)
        logger.info(f"  file_id={file_id}")
    else:
        logger.info("[2/5] 文件已秒传（服务器已有相同文件）")

    # 3. 提交解析任务
    logger.info(f"[3/5] 提交解析任务 (tier={tier}, ocr_mode={ocr_mode})...")
    job_resp = requests.post(
        f"{api_url}/v1/parse/jobs",
        headers={"Content-Type": "application/json"},
        json={
            _FILES_KEY: [{"source": {"type": _FILE_ID_KEY, _FILE_ID_KEY: file_id}}],
            "output_formats": ["markdown", _ZIP_KEY],
            "tier": tier,
            "ocr_mode": ocr_mode,
        },
    )
    job_resp.raise_for_status()
    job_data = job_resp.json()
    job_id = job_data["job_id"]
    logger.info(f"  job_id={job_id}")

    # 4. 轮询状态
    logger.info(f"[4/5] 等待解析完成 (超时 {timeout}s)...")
    start_time = time.time()
    status = ""
    status_data = {}
    while True:
        elapsed = int(time.time() - start_time)
        if elapsed > timeout:
            raise TimeoutError(f"MinerU 解析超时，已等待 {elapsed}s")

        status_resp = requests.get(f"{api_url}/v1/parse/jobs/{job_id}")
        status_resp.raise_for_status()
        status_data = status_resp.json()
        status = status_data.get(_STATUS_KEY, "")
        progress = status_data.get("progress", {})
        logger.info(
            f"  [{elapsed}s] status={status}, "
            f"completed={progress.get('completed', 0)}/{progress.get('total', 1)}"
        )

        if status in ("completed", "partial", "failed", "canceled"):
            break

        time.sleep(poll_interval)

    if status == "failed":
        error = status_data.get(_FILES_KEY, [{}])[0].get("error", {})
        raise RuntimeError(
            f"MinerU 解析失败: {error.get('code', 'unknown')} - {error.get('message', '')}"
        )

    if status == "canceled":
        raise RuntimeError("MinerU 解析任务被取消")

    # 5. 下载结果
    logger.info("[5/5] 下载结果...")
    output_files = status_data.get(_FILES_KEY, [{}])[0].get("output_files", {})

    if _ZIP_KEY not in output_files:
        raise RuntimeError(
            f"MinerU 未返回 zip 文件，output_files={json.dumps(output_files, ensure_ascii=False)}"
        )

    zip_file_id = output_files[_ZIP_KEY][_FILE_ID_KEY]
    zip_resp = requests.get(f"{api_url}/v1/files/{zip_file_id}/content")
    zip_resp.raise_for_status()

    # 解压，只保留 .md 文件和 images/ 下的文件
    images_dir = output_dir / "images"
    images_dir.mkdir(parents=True, exist_ok=True)

    md_path = None

    with zipfile.ZipFile(io.BytesIO(zip_resp.content), "r") as zin:
        for item in zin.infolist():
            name = item.filename
            if name.endswith(".md"):
                md_text = zin.read(name).decode(_UTF8_KEY, errors="replace")
                # 不做 HTML 清洗，保留原始 markdown（交给 VLLMMarkdownLoader 处理）
                md_filename = f"{file_path.stem}.md"
                md_path = output_dir / md_filename
                md_path.write_text(md_text, encoding=_UTF8_KEY)
                logger.info(f"  markdown 已保存: {md_path}")
            elif name.startswith("images/") and not name.endswith("/"):
                img_data = zin.read(name)
                img_name = Path(name).name
                (images_dir / img_name).write_bytes(img_data)

    if md_path is None:
        md_path = output_dir / f"{file_path.stem}.md"
        raise RuntimeError(f"MinerU zip 中未找到 .md 文件，output_dir={output_dir}")

    # 保存元信息
    meta = {
        "original_filename": file_path.name,
        "source_path": str(file_path),
        "mineru_job_id": job_id,
        "parsed_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
        _STATUS_KEY: status,
    }
    (output_dir / "meta.json").write_text(
        json.dumps(meta, ensure_ascii=False, indent=2), encoding=_UTF8_KEY
    )

    # 统计图片数量
    img_count = len(list(images_dir.glob("*"))) if images_dir.exists() else 0
    logger.info(
        f"MinerU 解析完成！"
        f"markdown={md_path.name}, images={img_count}, output_dir={output_dir}"
    )

    return md_path


def get_cached_md_path(file_path: str) -> Path | None:
    """获取已缓存的 markdown 文件路径（不调用 MinerU）"""
    output_dir = get_mineru_output_dir(file_path)
    if not output_dir.exists():
        return None
    md_files = list(output_dir.glob("*.md"))
    if not md_files:
        return None
    return md_files[0]


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="MinerU 文档解析客户端")
    parser.add_argument("file", help="待解析的文件路径")
    parser.add_argument("-o", "--output", default=None, help="结果保存目录（默认自动）")
    args = parser.parse_args()

    result_md = parse_with_mineru(args.file, args.output)
    logger.info(f"完成！markdown 路径: {result_md}")
