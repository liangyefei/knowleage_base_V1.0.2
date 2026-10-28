"""语言检测：基于 langdetect，带 LRU 缓存、CJK 快速通道与代码主导识别。"""
import os
import re
from functools import lru_cache
from typing import Literal

from configs.logger_manager import logger


def _auto_switch_enabled() -> bool:
    """kill-switch：HAISI_LANG_AUTO_SWITCH 默认启用；设为 "0"/"false" 时关闭，
    resolve_language 短路返回 zh（=今天行为，零变更、无需重启）。"""
    return os.environ.get("HAISI_LANG_AUTO_SWITCH", "1") not in ("0", "false", "False", "")


# langdetect 默认带随机性，固定 seed 保证可复现
try:
    from langdetect import detect_langs, DetectorFactory
    DetectorFactory.seed = 0
    _HAS_LANGDETECT = True
except ImportError:  # 依赖缺失时 fail-open，按中文处理
    _HAS_LANGDETECT = False

Lang = Literal["zh", "en"]

# ASCII 字母占比阈值：英文短句（如 "hi"）langdetect 不稳，用字符统计兜底
_EN_ASCII_RATIO = 0.6
_MIN_LEN_FOR_DETECT = 3  # 短于此长度走字符统计快速通道

# 代码符号集（散文中罕见、代码中密集）：用于识别"纯代码/代码主导"输入
_CODE_SYMBOLS = set("{}()[];=<>+-*/\\|&!@#$%^~:`")
# 代码符号占 (符号+字母) 比例阈值：≥ 此值判定为代码主导
_CODE_SYMBOL_RATIO = 0.15
# 代码关键词 + 紧邻代码标点：要求关键词像代码那样被使用（def foo( / return x;）
# 单独出现在散文里的 "return"/"class" 不算（避免 "How to use return" 误判）
_CODE_KW_WITH_PUNCT = re.compile(
    r'\b(?:def|class|import|from|return|public|private|protected|void|'
    r'int|float|double|char|bool|var|let|const|async|await|new|try|catch|'
    r'finally|throw|lambda|fn|func|struct|enum|interface|extends|implements|'
    r'package|namespace|using|#include)\b[^a-zA-Z]{0,3}[(:;{=]'
)
# markdown 代码块 / 行内代码，检测前剥离，用剩余自然语言片段判定语言
_FENCED_CODE = re.compile(r"```[a-zA-Z]*\n?.*?\n```", re.DOTALL)
_INLINE_CODE = re.compile(r"`[^`\n]+`")


def _strip_code_blocks(q: str) -> str:
    """剥离 markdown 代码块（```...```）与行内代码（`...`），返回自然语言片段。"""
    q = _FENCED_CODE.sub(" ", q)
    q = _INLINE_CODE.sub(" ", q)
    return q.strip()


def _is_code_dominant(q: str) -> bool:
    """判断输入是否为代码主导（缺少可识别的自然语言信号）。

    判据（满足任一）：
      1. 代码符号密度 ≥ _CODE_SYMBOL_RATIO
      2. 代码关键词以代码方式使用（紧邻 ( : ; { = 等标点）
    """
    if not q:
        return False
    code_syms = sum(1 for c in q if c in _CODE_SYMBOLS)
    letters = sum(1 for c in q if c.isalpha())
    total = code_syms + letters
    if total > 0 and code_syms / total >= _CODE_SYMBOL_RATIO:
        return True
    if _CODE_KW_WITH_PUNCT.search(q):
        return True
    return False


def _is_cjk_dominant(q: str) -> bool:
    """CJK 字符数 ≥ ASCII 字母数 → 判为中文主导，直接返回 zh 绕过 langdetect。"""
    cjk = sum(1 for c in q if "\u4e00" <= c <= "\u9fff")
    ascii_letters = sum(1 for c in q if c.isalpha() and ord(c) < 128)
    return cjk > 0 and cjk >= ascii_letters


def _by_ascii_ratio(q: str) -> Lang:
    """按 ASCII 字母占比判定：≥_EN_ASCII_RATIO 判英文，否则中文。"""
    letters = [c for c in q if c.isalpha()]
    if not letters:
        return "zh"
    ascii_letters = [c for c in letters if ord(c) < 128]
    return "en" if len(ascii_letters) / len(letters) >= _EN_ASCII_RATIO else "zh"


def detect_language(query: str) -> Lang:
    """检测 query 主语言，返回 _ZH_KEY 或 'en'。

    优先级：
    1. 空/纯标点 → _ZH_KEY
    2. 剥离代码块后自然语言为空 → 纯代码 → _ZH_KEY
    3. CJK 占优 → _ZH_KEY，绕过 langdetect（不阻塞 async 事件循环）
    4. 代码主导 → _ZH_KEY
    5. 极短文本 → ASCII 占比判定
    6. langdetect 检测
    7. langdetect 异常/非中英 → 字符占比兜底
    8. 全部失败 → _ZH_KEY
    """
    _ZH_KEY = 'zh'
    if not query or not query.strip():
        return _ZH_KEY
    q = query.strip()

    prose = _strip_code_blocks(q)
    if not prose:
        return _ZH_KEY

    if _is_cjk_dominant(prose):
        return _ZH_KEY

    if _is_code_dominant(prose):
        logger.debug(f"[lang] code-dominant input, keep default zh | query={q[:40]}")
        return _ZH_KEY

    if len(prose) <= _MIN_LEN_FOR_DETECT or not _HAS_LANGDETECT:
        return _by_ascii_ratio(prose)

    try:
        langs = detect_langs(prose)
        if langs:
            top = langs[0].lang  # 如 'en', 'zh-cn', 'zh-tw'
            if top.startswith("en"):
                return "en"
            if top.startswith(_ZH_KEY):
                return _ZH_KEY
        return _by_ascii_ratio(prose)
    except Exception as e:
        logger.debug(f"[lang] detect 失败 fail-open=zh: {e} | query={q[:40]}")
        return _by_ascii_ratio(prose)


@lru_cache(maxsize=2048)
def detect_language_cached(query: str) -> Lang:
    """带 LRU 缓存的检测：同一 query 二次请求 O(1) 命中。"""
    return detect_language(query)


def resolve_language(query: str, explicit: str = "auto") -> Lang:
    """融合显式参数与自动检测：explicit != 'auto' 时直接采用，否则检测。

    kill-switch 优先：HAISI_LANG_AUTO_SWITCH=0 时直接返回 zh，忽略显式覆盖。
    """
    _ZH_KEY = 'zh'
    if not _auto_switch_enabled():
        return _ZH_KEY
    if explicit == "en":
        return "en"
    if explicit == _ZH_KEY:
        return _ZH_KEY
    return detect_language_cached(query)
