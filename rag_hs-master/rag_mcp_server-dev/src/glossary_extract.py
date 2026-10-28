#!/usr/bin/env python3
"""glossary_extract.py — 项目级 CN→EN 查询词表自动抽取（LLM）

用法:
    DASHSCOPE_API_KEY=xxx python3 glossary_extract.py /projects/<kbId> [--force]

流程:
    1. 采样项目源码的标识符词汇（snake/camel 切分，按频次取 top N）
    2. 调 qwen-flash 抽取「用户可能的中文能力词 → 本项目实际英文标识词」
    3. 校验英文词确实存在于项目词汇表（防 LLM 幻觉），全部落空的词条丢弃
    4. 写 <项目>/.cbm-glossary.yaml（供 CBM 的 search_graph 中文展开合并）

幂等: 已有 .cbm-glossary.yaml 时跳过，--force 覆盖。失败非致命（退出码 1）。
生成的文件是纯 YAML 映射，可人工校订后继续使用。
"""
import argparse
import json
import logging
import os
import re
import sys
import time
import urllib.parse
import urllib.request

logging.basicConfig(level=logging.INFO, format="%(message)s", stream=sys.stdout)
log = logging.getLogger("glossary_extract")
_K_CONTENT = "content"  # 扫描 G.TYP.03：重复字典 key 常量化
from collections import Counter

CODE_EXTS = {".c", ".h", ".cpp", ".hpp", ".cc", ".py", ".java", ".js", ".ts",
             ".go", ".rs", ".sh", ".mk", "Makefile"}
SKIP_DIRS = {".git", ".codegraph", "node_modules", "build", "output", ".cbm-cache"}
MAX_FILES = 3000
MAX_VOCAB = 240
GLOSSARY_NAME = ".cbm-glossary.yaml"

SPLIT_CAMEL = re.compile(r"(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")
TOKEN = re.compile(r"[A-Za-z_][A-Za-z0-9_]{2,}")
STOP = {"the", "and", "for", "int", "char", "void", "static", "const", "return",
        "include", "define", "struct", "unsigned", "size_t", "uint32_t", "NULL"}


def sample_vocab(root: str) -> list[str]:
    """Walk code files, split identifiers into stems, return top-N by frequency."""
    counter: Counter = Counter()
    scanned = 0
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for fn in filenames:
            ext = os.path.splitext(fn)[1]
            if ext not in CODE_EXTS and fn not in CODE_EXTS:
                continue
            path = os.path.join(dirpath, fn)
            try:
                if os.path.getsize(path) > 1_000_000:
                    continue
                with open(path, errors="replace") as f:
                    text = f.read(400_000)
            except OSError:
                continue
            for tok in TOKEN.findall(text):
                for stem in SPLIT_CAMEL.split(tok):
                    stem = stem.strip("_").lower()
                    if len(stem) >= 3 and stem not in STOP:
                        counter[stem] += 1
            scanned += 1
            if scanned >= MAX_FILES:
                return [w for w, _ in counter.most_common(MAX_VOCAB)]
    return [w for w, _ in counter.most_common(MAX_VOCAB)]


def call_llm(vocab: list[str], model: str, api_key: str, base_url: str) -> dict:
    prompt = (
        "以下是一个代码项目的标识符词汇表（snake/camel 切分后的词干，按出现频次排序）：\n"
        + ", ".join(vocab)
        + "\n\n请推测用户会用哪些中文词查询这个项目的代码，产出 JSON 对象："
        '键为中文能力/功能词（如"拍照""抓流"），值为数组，元素是英文词干，'
        "**必须从上面词汇表中选取**。规则：\n"
        "1. 10~30 条，只收用户查询会用到的功能词，不收无意义词\n"
        "2. 每条 1~3 个英文词干，选最可能与该中文词相关的\n"
        "3. 只输出 JSON 对象，不要其它内容"
    )
    body = json.dumps({
        "model": model,
        "temperature": 0.2,
        "messages": [
            {"role": "system", _K_CONTENT: "你是嵌入式/软件项目的代码检索助手，输出纯 JSON。"},
            {"role": "user", _K_CONTENT: prompt},
        ],
    }).encode()
    req = urllib.request.Request(
        urllib.parse.urljoin(base_url.rstrip("/") + "/", "chat/completions"),
        data=body, method="POST",
        headers={"Content-Type": "application/json",
                 "Authorization": "Bearer " + api_key})
    with urllib.request.urlopen(req, timeout=120) as r:
        data = json.load(r)
    text = data["choices"][0]["message"][_K_CONTENT]
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end <= start:
        raise ValueError("LLM 响应无 JSON: " + text[:120])
    return json.loads(text[start:end + 1])


def validate(mapping: dict, vocab: list[str]) -> dict:
    """Drop entries whose EN tokens are all absent from the project vocabulary."""
    vocab_set = set(vocab)
    out = {}
    for cn, ens in mapping.items():
        if not isinstance(cn, str) or not isinstance(ens, list):
            continue
        keep = sorted({str(e).lower() for e in ens if str(e).lower() in vocab_set})
        if keep and len(cn) <= 16:
            out[cn] = keep
    return out


def write_glossary(path: str, mapping: dict, model: str) -> None:
    lines = [
        "# .cbm-glossary.yaml — CBM search_graph 中文查询展开词表",
        f"# 由 glossary_extract.py 自动生成（model={model}, {time.strftime('%Y-%m-%d %H:%M')}），"
        "可人工校订",
        "# 键=中文查询词，值=展开的英文词干（BM25 OR 检索）；删除整块即禁用该词",
    ]
    for cn in sorted(mapping):
        lines.append(f"{cn}:")
        for en in mapping[cn]:
            lines.append(f"  - {en}")
    with open(path, "w") as f:
        f.write("\n".join(lines) + "\n")


def main() -> int:
    ap = argparse.ArgumentParser(description="项目 CN→EN 查询词表抽取")
    ap.add_argument("project_path", help="项目根目录，如 /projects/<kbId>")
    ap.add_argument("--force", action="store_true", help="覆盖已存在的词表")
    args = ap.parse_args()

    root = args.project_path.rstrip("/")
    out_path = os.path.join(root, GLOSSARY_NAME)
    if os.path.exists(out_path) and not args.force:
        log.info(f"[glossary] 已存在 {out_path}，跳过（--force 覆盖）")
        return 0

    api_key = os.environ.get("DASHSCOPE_API_KEY", "")
    if not api_key:
        log.info("[glossary] 缺 DASHSCOPE_API_KEY，跳过抽取")
        return 1
    model = os.environ.get("GLOSSARY_LLM_MODEL", "qwen-flash")
    base_url = os.environ.get(
        "DASHSCOPE_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")

    vocab = sample_vocab(root)
    if len(vocab) < 15:
        log.info(f"[glossary] {root}: 词汇量太少（{len(vocab)}），跳过")
        return 0
    log.info(f"[glossary] {root}: 采样词汇 {len(vocab)} 个，调用 {model} 抽取 ...")

    try:
        mapping = call_llm(vocab, model, api_key, base_url)
    except Exception as e:
        log.info(f"[glossary] LLM 调用失败: {type(e).__name__}: {e}")
        return 1
    mapping = validate(mapping, vocab)
    if len(mapping) < 5:
        log.info(f"[glossary] 校验后仅 {len(mapping)} 条有效，放弃写入")
        return 1
    write_glossary(out_path, mapping, model)
    log.info(f"[glossary] 写入 {out_path}（{len(mapping)} 条）:")
    for cn in sorted(mapping):
        log.info(f"    {cn}: {' '.join(mapping[cn])}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
