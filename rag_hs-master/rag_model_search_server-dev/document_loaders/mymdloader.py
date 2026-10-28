#!/usr/bin/env python3
# @File : mymdloader.py
# @Function: Markdown 加载器 - 解析图片引用 + VLM 识别图片内容 + 层级分块

import os
import re
import urllib.parse
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

from langchain.document_loaders.unstructured import UnstructuredFileLoader
from langchain_core.documents import Document
from markdownify import markdownify as md_convert

from configs.logger_manager import logger

from .base_loader import HierarchyChunker, _save_chunks_to_json
from .myimgloader import _MIN_IMAGE_PIXELS_DEFAULT, VLLMImageExtractor

# markdown 图片引用：![alt](path) 或 ![alt](path "title") 或 ![alt](path 'title')
# group(1)=alt, group(2)=path（不含空白，避免把 title 当成路径一部分），group(3)=title（含引号，可选）
_MD_IMAGE_PATTERN = re.compile(r'!\[([^\]]*)\]\(([^)\s]+)(?:\s+("[^"]*"|\'[^\']*\'))?\)')
# HTML img 标签：<img src="path" ...>
_HTML_IMG_PATTERN = re.compile(r'<img\s+[^>]*?src=["\']([^"\']+)["\'][^>]*>', re.IGNORECASE)
# markdown 标题：# title, ## title, ### title, #### title
_HEADING_PATTERN = re.compile(r'^(#{1,4})\s+(.*)')
# 代码块边界：```lang
_CODE_FENCE_PATTERN = re.compile(r'^```')
# 代码块保护：``` 围栏代码块（多行）+ 行内 `code`，转换 HTML 时用占位符保护，
# 避免代码/行内 code 内的 HTML 被误转（如 `<div>` 被当标签删掉破坏语义）
# 注意：[\s\S]*? 不要求结束 ``` 前换行，旧正则 \n``` 漏匹配会导致
# .*? 跨过整段 HTML 段误把它们当代码块保护，HTML 标签全部残留
_CODE_BLOCK_RE = re.compile(r'```[\s\S]*?```|`[^`\n]+`', re.DOTALL)
# <pre> 标签预处理：markdownify 不识别 <code class="language-X"> 的语言 class，
# 代码块会丢失语言标识。预处理把 <pre><code class="language-X">...</code></pre>
# 转成 ```X 围栏，后续 _CODE_BLOCK_RE 会保护它，语言标识得以保留
_PRE_TAG_RE = re.compile(r'<pre([^>]*)>(.*?)</pre>', re.DOTALL | re.IGNORECASE)
_PRE_CODE_TAG_RE = re.compile(r'<code([^>]*?)>(.*?)</code>', re.DOTALL | re.IGNORECASE)
_LANG_CLASS_RE = re.compile(r'class="[^"]*\blanguage-([\w-]+)\b[^"]*"')
# [L1]-[L5] 层级标记清理（分块后删除）
_LEVEL_MARKER_PATTERN = re.compile(r'\[L[1-5]\]\s*')
# 与后端 sanitizeFileName 对齐的非法字符集（路径段级）
# 后端：[\/:*?"<>|\p{Cntrl}]，段内 / 不会有，\ 也删
_SANITIZER_INVALID_CHARS = re.compile(r'[\\:*?"<>|\x00-\x1f\x7f]')


def _pre_to_code_block(m) -> str:
    """把 <pre> 标签转成 ```lang\\ncode\\n``` 围栏，保留 language class 标识。

    markdownify 不识别 <code class="language-X"> 的语言 class，代码块会丢失
    语言标识。预处理把 <pre> 转成围栏代码块，后续 _CODE_BLOCK_RE 会保护它，
    语言标识得以保留。
    """
    pre_attrs = m.group(1)
    inner = m.group(2)
    lang = ''
    # 先从 pre 标签的 class 找 language-X
    lang_match = _LANG_CLASS_RE.search(pre_attrs)
    if lang_match:
        lang = lang_match.group(1)
    # 再从 <code> 标签找
    code_match = _PRE_CODE_TAG_RE.search(inner)
    if code_match:
        code_attrs = code_match.group(1)
        code_text = code_match.group(2)
        if not lang:
            lang_match = _LANG_CLASS_RE.search(code_attrs)
            if lang_match:
                lang = lang_match.group(1)
    else:
        code_text = inner
    # <br> → \n，其他 HTML 标签删除
    code_text = re.sub(r'<br\s*/?>', '\n', code_text, flags=re.IGNORECASE)
    code_text = re.sub(r'<[^>]+>', '', code_text)
    # HTML 实体还原（&lt; → < 等），代码块内应保留原始字符
    code_text = (code_text
                 .replace('&lt;', '<')
                 .replace('&gt;', '>')
                 .replace('&amp;', '&')
                 .replace('&nbsp;', ' '))
    code_text = code_text.strip('\n')
    return f"```{lang}\n{code_text}\n```"


def _sanitize_path_segment(segment: str) -> str:
    """与后端 sanitizeFileName 对齐：清洗单个文件名段

    - 去非法字符 \\ : * ? " < > | 和控制字符
    - trim 前后空格
    - 去掉结尾的点（Windows 不允许文件名以点结尾，后端也会去）
    """
    cleaned = _SANITIZER_INVALID_CHARS.sub('', segment).strip()
    while cleaned.endswith('.'):
        cleaned = cleaned[:-1]
    return cleaned


def _sanitize_image_path(path: str) -> str:
    """只清洗路径中的文件名部分（最后一段），目录部分保留原样

    与后端 sanitizeFileName 对齐：后端先 lastIndexOf(/) 取文件名段再清洗，
    loader 需要保留目录结构用于解析，所以只对最后的文件名段做清洗。
    """
    idx = path.rfind('/')
    if idx < 0:
        # 无目录分隔符，整个路径就是文件名
        return _sanitize_path_segment(path)
    # 目录部分保留，只清洗文件名段
    return path[:idx + 1] + _sanitize_path_segment(path[idx + 1:])


def _encode_path_for_md(path: str) -> str:
    """把路径中的空格编码为 %20，供 markdown 图片引用输出

    前端 markdown 渲染器解析 ![alt](url "title") 时，url 在第一个空格处
    截断（空格后被视为 title），含空格的绝对路径会被破坏成断裂的图片引用。
    编码空格为 %20 后，浏览器/HTTP 层请求时自动解码回空格，能正确访问
    含空格的真实文件。

    仅编码空格，保留中文等其他字符（现代浏览器可直接处理 UTF-8 URL）。
    解析阶段（isfile 检查）不编码，只在输出给前端的路径里编码。
    """
    return path.replace(' ', '%20')


def _replace_path_in_ref(ref: str, new_path: str) -> str:
    """替换单个图片引用字符串里的路径部分

    支持：
    - markdown: ![alt](path) 或 ![alt](path "title") → ![alt](new_path) 或 ![alt](new_path "title")
    - HTML: <img src="path" ...> → <img src="new_path" ...>（保留其他属性）

    输出路径用 _encode_path_for_md 编码空格，避免前端在空格处截断
    （.md 所在目录名可能含空格，约定不改目录名，故路径里会带空格）。
    """
    safe_path = _encode_path_for_md(new_path)
    m = _MD_IMAGE_PATTERN.match(ref)
    if m:
        alt = m.group(1)
        title = m.group(3)  # 含引号，可选
        if title:
            return f"![{alt}]({safe_path} {title})"
        return f"![{alt}]({safe_path})"
    m = _HTML_IMG_PATTERN.match(ref)
    if m:
        return ref.replace(m.group(1), safe_path, 1)
    return ref


class VLLMMarkdownLoader(UnstructuredFileLoader):
    """
    Markdown 加载器 - 解析图片引用 + VLM 识别图片内容 + 层级分块

    流程：
    1. 读取 .md 原文
    2. 正则匹配 ![alt](path) 和 <img src="path">
    3. 按 resolved_path 去重，并发调用 VLM 识别（同图多引用只识别一次）
    4. 在图片引用下方插入 blockquote 描述
    5. #/##/###/#### 转成 [L1]/[L2]/[L3]/[L4]（代码块和 blockquote 内不转）
    6. 用 HierarchyChunker 分块
    7. 返回 Document 列表 + 保存 JSON
    """

    def __init__(
            self,
            file_path: str,
            model_name: str = "qwen3.7-plus",
            max_tokens_per_chunk: int = 2048,
            max_workers: int = 3,
            min_image_pixels: int = _MIN_IMAGE_PIXELS_DEFAULT,
            **kwargs
    ):
        """
        初始化 Markdown 加载器

        Args:
            file_path: .md 文件路径
            model_name: VLM 模型名称（从配置加载）
            max_tokens_per_chunk: 每个分块的最大 token 数
            max_workers: VLM 调用并发度
            min_image_pixels: 像素数（宽×高）小于此值的图片视为小图标，
                              由 VLLMImageExtractor 直接返回 "null" 不调用 VLM
            **kwargs: 传递给父类的参数
        """
        super().__init__(file_path, **kwargs)
        self.model_name = model_name
        self.max_tokens_per_chunk = max_tokens_per_chunk
        self.max_workers = max_workers
        self.extractor = VLLMImageExtractor(
            model_name=model_name, min_image_pixels=min_image_pixels
        )
        self.chunker = HierarchyChunker(max_tokens_per_chunk=self.max_tokens_per_chunk)

    # ============================================================
    # 图片引用解析
    # ============================================================

    @staticmethod
    def _resolve_image_path(img_path: str, md_dir: str) -> str:
        """解析图片路径（相对路径基于 .md 文件目录）

        处理：
        - 网络图片（http/https/data/file 协议）直接跳过
        - 去除 URL 参数（?v=1）和锚点（#anchor）
        - URL 解码（%20 → 空格等）：markdown 路径可能用 URL 编码表示
          空格等特殊字符，磁盘上文件名是真实字符，需解码才能 isfile 匹配。
          与 sfs_path.py / kb_doc_api.py 的 unquote 处理保持一致。
        - strip 前后空格
        - 与后端 sanitizeFileName 对齐：对路径每段清洗非法字符 + 去结尾点
          （后端会清洗上传文件名，markdown 里原始引用需做同样清洗才能匹配磁盘文件）

        Returns:
            解析后的绝对路径；网络图片或 data URI 返回空字符串
        """
        # 跳过网络图片和 data URI
        if img_path.startswith(('http://', 'https://', 'data:', 'file://')):
            return ""
        # strip 前后空格
        img_path = img_path.strip()
        # 去除 URL 参数和锚点（先于解码，避免解码后把真实的 ? # 误当分隔符截断）
        img_path = img_path.split('?')[0].split('#')[0].strip()
        if not img_path:
            return ""
        # URL 解码：%20 → 空格，%E4%B8%AD → 中 等。
        # markdown 图片路径常把含空格的文件名编码成 %20，
        # 磁盘真实文件名是空格，不解码会导致 isfile 失败 → 路径不替换。
        img_path = urllib.parse.unquote(img_path)
        # 与后端 sanitizeFileName 对齐：对路径每段清洗非法字符 + 去结尾点
        img_path = _sanitize_image_path(img_path)
        if not img_path or img_path in ('.', '/'):
            return ""
        # 绝对路径直接用，相对路径基于 .md 目录
        if os.path.isabs(img_path):
            return os.path.normpath(img_path)
        return os.path.normpath(os.path.join(md_dir, img_path))

    @staticmethod
    def _find_all_images(content: str) -> list[tuple[int, int, str]]:
        """找出所有图片引用

        Returns:
            [(start, end, img_path), ...] 按 start 升序
        """
        images = []
        for m in _MD_IMAGE_PATTERN.finditer(content):
            images.append((m.start(), m.end(), m.group(2)))
        for m in _HTML_IMG_PATTERN.finditer(content):
            images.append((m.start(), m.end(), m.group(1)))
        images.sort(key=lambda x: x[0])
        return images

    @staticmethod
    def _replace_image_paths(content: str, images: list[dict]) -> str:
        """把图片引用里的相对路径替换成解析后的真实路径（绝对路径）

        从后往前替换，避免位置偏移；同步更新 images[i][_END_KEY] 供后续
        _insert_image_descriptions 使用。只替换真实存在的图片（img["exists"]），
        不存在的图片（文件缺失/网络图片）保持原样，避免把不存在的相对路径
        换成同样不存在的绝对路径造成误导。
        """
        _END_KEY = 'end'
        result = content
        for i in range(len(images) - 1, -1, -1):
            img = images[i]
            if not img["exists"]:
                continue
            resolved = img["resolved_path"]
            start, end = img["start"], img[_END_KEY]
            original_ref = result[start:end]
            new_ref = _replace_path_in_ref(original_ref, resolved)
            if new_ref != original_ref:
                result = result[:start] + new_ref + result[end:]
                delta = len(new_ref) - (end - start)
                img[_END_KEY] = start + len(new_ref)
                # 从后往前处理时，j > i 的图片已处理过，但前方图片(i)的替换
                # 改变了内容长度，后方图片的 start/end 需要同步偏移，
                # 否则 _insert_image_descriptions 会插到图片引用中间
                for j in range(i + 1, len(images)):
                    images[j]["start"] += delta
                    images[j][_END_KEY] += delta
        return result

    @staticmethod
    def _format_description_as_blockquote(description: str) -> str:
        """把 VLM 描述格式化为 blockquote 块

        格式：
        > [图片描述] 第一行
        > 第二行
        >
        > 空行后的内容
        """
        if not description:
            return ""
        lines = description.strip().split('\n')
        if not lines:
            return ""
        quoted = [f"> [图片描述] {lines[0].strip()}"]
        for line in lines[1:]:
            stripped = line.strip()
            if stripped:
                quoted.append(f"> {stripped}")
            else:
                # 空行只输出 >，避免 "> " 带空格的格式问题
                quoted.append(">")
        return '\n'.join(quoted)

    @staticmethod
    def _convert_headings_to_level_markers(content: str) -> str:
        """把 markdown #/##/###/#### 转成 [L1]/[L2]/[L3]/[L4]

        跳过两类区域，避免误转：
        - 代码块内的 #（如 bash 注释 # comment）
        - blockquote 内的 #（如 VLM 描述里可能含 # 标题文本）
        """
        lines = content.split('\n')
        converted = []
        in_code_block = False
        for line in lines:
            if _CODE_FENCE_PATTERN.match(line.strip()):
                in_code_block = not in_code_block
                converted.append(line)
                continue
            if in_code_block:
                converted.append(line)
                continue
            # 跳过 blockquote 内的行（VLM 描述插入的 blockquote 不转 #）
            if line.lstrip().startswith('>'):
                converted.append(line)
                continue
            m = _HEADING_PATTERN.match(line)
            if m:
                level = len(m.group(1))
                title = m.group(2).strip()
                converted.append(f"[L{level}] {title}")
            else:
                converted.append(line)
        return '\n'.join(converted)

    @staticmethod
    def _convert_html_to_markdown(content: str) -> str:
        """把 markdown 文件里的 HTML 标签转成 markdown 等价物，减少标签噪声对向量检索的干扰。

        使用 markdownify 库（基于 BeautifulSoup）转换，支持：
        - <table> → markdown 表格（rowspan/colspan 由 markdownify 展开）
        - <ul>/<ol> → markdown 列表（嵌套缩进）
        - <pre> → 围栏代码块
        - <img src alt> → ![alt](src)
        - <a href>text</a> → [text](href)
        - <br> → \\n，<hr> → ---
        - <h1>-<h6> → #-######
        - <b>/<strong> → **text**，<i>/<em> → *text*
        - <del>/<s> → ~~text~~，<code> → `text`
        - <script>/<style> → 删除（strip 参数）
        - 其他行内/未知标签 → 删标签保留内容

        代码块（``` 围栏）和行内 `code` 内的 HTML 原样保留；&nbsp; 等实体在解析时
        转成 unicode（如 \\xa0），最后统一替换成普通空格。

        兜底：整个转换流程包在 try/except 内，任何异常都返回原始 content，
        确保入库主流程不中断。
        """
        if not content or '<' not in content:
            return content

        # 原文备份：转换任何阶段失败都用它兜底，保证入库不中断
        original = content
        code_blocks = []

        def _save_code(m):
            code_blocks.append(m.group(0))
            return f"\x00CB{len(code_blocks) - 1}\x00"

        try:
            # 1. 预处理 <pre> 标签：转成 ```lang\ncode\n``` 围栏
            #    markdownify 不识别 <code class="language-X"> 的语言 class，
            #    预处理保留语言标识；生成的围栏会被步骤 2 的 _CODE_BLOCK_RE 保护
            content = _PRE_TAG_RE.sub(_pre_to_code_block, content)

            # 2. 保护代码块（避免代码块内 HTML 被误转）
            content = _CODE_BLOCK_RE.sub(_save_code, content)

            # 3. markdownify 转换 HTML 标签为 markdown
            #    heading_style='ATX'：# 风格标题（后续 _convert_headings 统一转 [L*]）
            #    bullets='-'：无序列表统一用 - 符号（markdownify 默认嵌套层
            #      用不同符号，统一成 - 更符合项目现有 markdown 风格）
            #    strip=['script', 'style']：删除 script/style 标签
            #    escape_asterisks=False / escape_underscores=False：
            #      不转义 * 和 _，避免 markdown 语法被破坏
            result = md_convert(
                content,
                heading_style='ATX',
                bullets='-',
                strip=['script', 'style'],
                escape_asterisks=False,
                escape_underscores=False,
            )

            # 4. 兜底：清除 markdownify 未解析的残留 HTML 标签
            #    大文件/复杂嵌套时部分 HTML 可能残留，此时代码块还是
            #    占位符（\x00CB{n}\x00，不含 <），不会被误删；
            #    markdown 行内 < 字符（如 a < b）后面非字母/!/，不匹配正则，
            #    不会被误删。
            result = re.sub(r'<[a-zA-Z/!?][^>]*>', '', result)

            # 5. 列表缩进规整：markdownify 用 \t 做嵌套缩进，统一替换成
            #    2 空格（项目 markdown 风格）。代码块还是占位符（不含 \t），
            #    替换不会破坏代码块内的制表符
            result = result.replace('\t', '  ')

            # 6. 恢复代码块
            for i, block in enumerate(code_blocks):
                result = result.replace(f"\x00CB{i}\x00", block)

            # 7. HTML 实体清理（&nbsp; → \xa0）+ 多余空行压缩
            result = result.replace('\xa0', ' ')
            result = re.sub(r'\n{3,}', '\n\n', result)

            return result
        except Exception as e:
            # 兜底：任何异常（正则/解析/替换/实体清理）都返回原文，
            # 保证 markdown 入库主流程不因 HTML 转换失败而中断
            logger.warning(
                f"HTML→markdown 转换失败，使用原文入库: {e}", exc_info=True
            )
            return original

    def load(self) -> list[Document]:
        """重写 load 方法，直接返回 _get_elements 的结果，避免父类合并"""
        return self._get_elements()

    def _resolve_all_images(self, content: str, md_dir: str) -> list[dict]:
        """解析所有图片引用，返回详细信息"""
        result = []
        for start, end, img_path in self._find_all_images(content):
            resolved = self._resolve_image_path(img_path, md_dir)
            # 用 isfile 而非 exists：避免路径指向目录时误判为可用
            exists = bool(resolved) and os.path.isfile(resolved)
            result.append({
                "start": start,
                "end": end,
                "img_path": img_path,
                "resolved_path": resolved,
                "exists": exists
            })
        return result

    # ============================================================
    # VLM 图片识别（并发 + 去重）
    # ============================================================

    def _safe_extract_image(self, image_path: str) -> str:
        """安全调用 VLM 识别图片（失败返回空字符串）"""
        try:
            return self.extractor.extract_image(image_path)
        except Exception as e:
            logger.error(f"VLM 识别图片失败 {image_path}: {e}")
            return ""

    def _extract_all_images_concurrent(self, images: list[dict]) -> dict[int, str]:
        """并发调用 VLM 识别所有本地存在的图片（按 resolved_path 去重）

        小图片过滤已下沉到 VLLMImageExtractor.extract_image：
        像素数过小的图片 extract_image 直接返回 "null" 不调用 VLM。
        本方法把 "null" 视为无描述，不插入 blockquote。

        同一图片被多次引用时只识别一次，多个引用共享同一描述。

        Returns:
            {image_index: description}（只包含成功识别且非 "null" 的）
        """
        if not images:
            return {}

        # 按 resolved_path 去重：path -> [引用索引列表]
        unique_paths: dict[str, list[int]] = {}
        for idx, img in enumerate(images):
            if not img["exists"]:
                continue
            path = img["resolved_path"]
            unique_paths.setdefault(path, []).append(idx)

        if not unique_paths:
            missing = [img["img_path"] for img in images if not img["exists"]]
            logger.warning(f"无可识别的本地图片，跳过 {len(missing)} 个引用：{missing[:5]}...")
            return {}

        total_refs = sum(len(v) for v in unique_paths.values())
        logger.info(
            f"开始并发识别 {len(unique_paths)} 张唯一图片"
            f"（共 {total_refs} 个引用），并发度 {self.max_workers}"
        )

        # 并发识别唯一图片（小图片由 extract_image 返回 "null" 过滤）
        path_to_desc: dict[str, str] = {}
        skipped_small = 0
        with ThreadPoolExecutor(max_workers=self.max_workers) as executor:
            future_to_path = {
                executor.submit(self._safe_extract_image, path): path
                for path in unique_paths
            }
            completed = 0
            total = len(unique_paths)
            for future in as_completed(future_to_path):
                path = future_to_path[future]
                completed += 1
                try:
                    desc = future.result()
                    if desc and desc != "null":
                        path_to_desc[path] = desc
                        logger.info(f"图片识别完成 {completed}/{total}: {path}")
                    elif desc == "null":
                        skipped_small += 1
                except Exception as e:
                    logger.error(f"图片识别异常 {path}: {e}")

        # 把描述映射回所有引用（同一路径的多个引用共享同一描述）
        descriptions: dict[int, str] = {}
        for path, idxs in unique_paths.items():
            desc = path_to_desc.get(path, "")
            if not desc:
                continue
            for idx in idxs:
                descriptions[idx] = desc

        logger.info(
            f"成功识别 {len(path_to_desc)}/{len(unique_paths)} 张唯一图片"
            + (f"，跳过 {skipped_small} 个小图标" if skipped_small else "")
        )
        return descriptions

    def _insert_image_descriptions(
            self,
            content: str,
            images: list[dict],
            descriptions: dict[int, str]
    ) -> str:
        """在图片引用下方插入 VLM 描述（blockquote 格式）

        从后往前插入，避免位置偏移
        """
        result = content
        for i in range(len(images) - 1, -1, -1):
            desc = descriptions.get(i, "")
            if not desc:
                continue
            blockquote = self._format_description_as_blockquote(desc)
            if not blockquote:
                continue
            # 在图片引用后面插入：空行 + blockquote + 空行
            insert_text = f"\n\n{blockquote}\n"
            insert_pos = images[i]["end"]
            result = result[:insert_pos] + insert_text + result[insert_pos:]
        return result

    # ============================================================
    # 主流程
    # ============================================================

    def _read_markdown_file(self) -> str:
        """读取 .md 文件，自动识别编码 + 去除 BOM，避免非 UTF-8 文件 UnicodeDecodeError。

        优先级：utf-8-sig（兼容 UTF-8 + 去 BOM）→ chardet 检测 → utf-8 errors=replace 兜底。
        参考 utils.py CSVLoader 的 chardet.detect 处理。
        """
        with open(self.file_path, 'rb') as f:
            raw = f.read()
        if not raw:
            logger.warning(f"Markdown 文件为空：{self.file_path}")
            return ""
        # 1. utf-8-sig 优先（utf-8 兼容 + 自动去 BOM）
        try:
            return raw.decode('utf-8-sig')
        except UnicodeDecodeError:
            logger.debug("编码检测 utf-8-sig 不匹配，使用 chardet")
            pass
        # 2. chardet 检测
        try:
            import chardet
            detect = chardet.detect(raw)
            encoding = (detect or {}).get("encoding")
            if encoding:
                content = raw.decode(encoding, errors='replace')
                logger.warning(
                    f"Markdown 非 UTF-8 编码，chardet 检测为 {encoding}"
                    f"（置信度 {detect.get('confidence')}）：{self.file_path}"
                )
                return content
        except Exception as e:
            logger.warning(f"chardet 检测编码失败：{e}")
        # 3. 兜底：utf-8 errors=replace，不中断解析
        logger.warning(f"Markdown 编码识别失败，用 utf-8 errors=replace 兜底：{self.file_path}")
        return raw.decode('utf-8', errors='replace')

    def _get_elements(self) -> list:
        """重写父类方法 - 返回分块后的元素列表"""
        try:
            logger.info(f"{'=' * 60}")
            logger.info(f"开始解析 Markdown: {self.file_path}")
            logger.info(f"{'=' * 60}")

            file_path = Path(self.file_path)
            file_name = file_path.stem.replace(" ", "")
            md_dir = str(file_path.parent)

            # 1. 读取 .md 原文（自动识别编码 + 去 BOM）
            content = self._read_markdown_file()
            logger.info(f"Markdown 原文长度: {len(content)} 字符")

            # 2. 解析所有图片引用
            images = self._resolve_all_images(content, md_dir)
            local_count = sum(1 for i in images if i["exists"])
            logger.info(f"找到 {len(images)} 个图片引用，其中 {local_count} 个本地存在")

            # 3. 并发 VLM 识别图片（按 resolved_path 去重）
            descriptions = self._extract_all_images_concurrent(images)

            # 4. 替换图片路径为真实路径 + 插入图片描述
            content = self._replace_image_paths(content, images)
            content_with_desc = self._insert_image_descriptions(content, images, descriptions)
            logger.info(f"替换路径并插入图片描述后长度: {len(content_with_desc)} 字符")

            # 5. HTML 标签转 markdown（图片处理完成后、标题转换前）
            #    <img src> 已是真实路径，转成 ![alt](src) 可复用图片描述；
            #    <table>/<ul>/<ol>/<pre>/<a>/<b>/<code> 等统一转 markdown 等价物，
            #    减少标签噪声对向量检索的干扰
            #    双保险：_convert_html_to_markdown 内部已全量 try/except，
            #    此处再加一层，确保 HTML 转换任何异常都不中断入库
            try:
                converted = self._convert_html_to_markdown(content_with_desc)
                if converted:
                    content_with_desc = converted
                logger.info(f"HTML→markdown 转换后长度: {len(content_with_desc)} 字符")
            except Exception as e:
                logger.warning(
                    f"HTML→markdown 转换异常，使用原文入库: {e}", exc_info=True
                )

            # 6. 转换标题为层级标记（跳过代码块和 blockquote）
            content_with_markers = self._convert_headings_to_level_markers(content_with_desc)

            # 7. 用 HierarchyChunker 分块
            chunks = self.chunker.chunk_markdown(
                content_with_markers, file_name, source_type="markdown"
            )
            logger.info(f"分块完成，共 {len(chunks)} 个块")

            # 8. 构建 Document 列表
            elements = []
            for chunk in chunks:
                chunk_content = chunk["content"]
                metadata = chunk["metadata"]

                # 删除 [L*] 标记（保留在 metadata 里）
                chunk_content = _LEVEL_MARKER_PATTERN.sub('', chunk_content)

                # 统计该 chunk 实际包含的图片引用数
                chunk_image_count = (
                    len(_MD_IMAGE_PATTERN.findall(chunk_content))
                    + len(_HTML_IMG_PATTERN.findall(chunk_content))
                )

                doc = Document(
                    page_content=chunk_content,
                    metadata={
                        "source": self.file_path,
                        "file_name": file_name,
                        "file_type": "md",
                        "source_type": "markdown",
                        "chunk_index": len(elements) + 1,
                        "total_chunks": len(chunks),
                        "titles": metadata.get('hierarchy_titles', []),
                        "chunk_type": metadata.get('block_type', 'text'),
                        # 文档级：整个文档成功识别的图片数
                        "total_images_in_doc": len(descriptions),
                        # chunk 级：该 chunk 实际包含的图片引用数
                        "chunk_image_count": chunk_image_count,
                    }
                )
                elements.append(doc)

            # 保存 JSON（副作用产物，失败不影响向量化主流程）
            # 修复：elements 已生成可用于入库，JSON 仅供调试/查看，
            # 磁盘满/权限/路径不存在时不应中断解析丢弃 elements
            try:
                _save_chunks_to_json(self.file_path, elements)
            except Exception as json_err:
                logger.warning(
                    f"保存 chunk JSON 失败（不影响向量化主流程）: {json_err}",
                    exc_info=True,
                )

            logger.info(f"Markdown 解析完成，生成 {len(elements)} 个元素")
            return elements

        except Exception as e:
            logger.error(f"Markdown 解析失败: {e}")
            import traceback
            traceback.print_exc()
            raise
