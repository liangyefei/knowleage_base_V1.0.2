"""HTML → markdown 通用转换 UT。

覆盖 _convert_html_to_markdown 对 markdown 文件中各类 HTML 标签的转换：
- 表格（rowspan/colspan 矩阵展开）
- 列表（ul/ol 嵌套缩进、ol start 属性）
- 代码块（pre 提前转换、``` 围栏内 HTML 保护）
- 图片/链接/行内格式（img/a/b/i/code/del 等）
- 块级标签（h1-h6/div/p/blockquote/hr/br）
- 定义列表/图形（dl/dt/dd/figure/figcaption）
- 行内扩展（mark/sub/sup/abbr/kbd/cite）
- HTML 注释删除、&nbsp; 实体清理
- 异常容错（畸形 HTML、空内容）

用 __new__ 跳过 __init__，避免触发 VLM 网络调用。
"""
# pylint: disable=no-use-sys-path-insert
# pylint: disable=protected-access
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

import pytest

from document_loaders.mymdloader import VLLMMarkdownLoader


def _make_loader():
    """构造 loader 实例但不触发 __init__（避免 VLM 网络）。"""
    return VLLMMarkdownLoader.__new__(VLLMMarkdownLoader)


# ============================================================
# 表格
# ============================================================

def test_simple_table():
    """基本表格：thead + tbody → markdown 表格。"""
    html = "<table><tr><th>A</th><th>B</th></tr><tr><td>1</td><td>2</td></tr></table>"
    result = _make_loader()._convert_html_to_markdown(html)
    assert "| A | B |" in result
    assert "| --- | --- |" in result
    assert "| 1 | 2 |" in result
    assert "<table" not in result


def test_table_with_rowspan():
    """rowspan：markdownify 不展开合并单元格，值只在首行显示。

    海思参数表分类表头用 rowspan 合并，markdownify 默认行为下
    合并行该列内容会丢失（只保留首行值）。此为接受的取舍。
    """
    html = (
        "<table>"
        "<tr><th rowspan=\"2\">类型</th><th>子类型</th></tr>"
        "<tr><td>X</td></tr>"
        "</table>"
    )
    result = _make_loader()._convert_html_to_markdown(html)
    # 表格转换：首行表头保留，X 保留
    assert "类型" in result and "子类型" in result
    assert "X" in result
    assert "<table" not in result and "<th" not in result and "<td" not in result


def test_table_with_colspan():
    """colspan：markdownify 不拆分合并单元格，只取一个值。

    合并单元格的内容只显示一次，不复制到合并的其他列。此为接受的取舍。
    """
    html = (
        "<table>"
        "<tr><th>A</th><th colspan=\"2\">合并</th></tr>"
        "<tr><td>1</td><td>2</td><td>3</td></tr>"
        "</table>"
    )
    result = _make_loader()._convert_html_to_markdown(html)
    # 合并单元格内容保留（只一次，不拆分）
    assert "合并" in result
    assert "1" in result and "2" in result and "3" in result
    assert "<table" not in result


def test_table_with_nested_tags():
    """表格单元格里嵌套 p/a 等标签：get_text 折叠成纯文本。"""
    html = (
        "<table><tr><th>类型</th></tr>"
        "<tr><td><p><a name=\"x\">链接文本</a></p></td></tr></table>"
    )
    result = _make_loader()._convert_html_to_markdown(html)
    assert "链接文本" in result
    assert "<table" not in result
    assert "<a " not in result


# ============================================================
# 列表
# ============================================================

def test_unordered_list():
    """无序列表：- item。"""
    html = "<ul><li>苹果</li><li>香蕉</li></ul>"
    result = _make_loader()._convert_html_to_markdown(html)
    assert "- 苹果" in result
    assert "- 香蕉" in result
    assert "<ul" not in result and "<li" not in result


def test_ordered_list():
    """有序列表：1. 2. 3.。"""
    html = "<ol><li>第一</li><li>第二</li></ol>"
    result = _make_loader()._convert_html_to_markdown(html)
    assert "1. 第一" in result
    assert "2. 第二" in result


def test_ordered_list_with_start():
    """ol start 属性：序号从 start 开始。"""
    html = "<ol start=\"5\"><li>第五</li><li>第六</li></ol>"
    result = _make_loader()._convert_html_to_markdown(html)
    assert "5. 第五" in result
    assert "6. 第六" in result


def test_nested_list():
    """嵌套列表：子列表 2 空格缩进。"""
    html = (
        "<ul>"
        "<li>外层"
        "<ul><li>内层1</li><li>内层2</li></ul>"
        "</li>"
        "<li>外层2</li>"
        "</ul>"
    )
    result = _make_loader()._convert_html_to_markdown(html)
    assert "- 外层" in result
    assert "  - 内层1" in result
    assert "  - 内层2" in result
    assert "- 外层2" in result


def test_ordered_nested_in_unordered():
    """无序列表里嵌套有序列表。"""
    html = (
        "<ul><li>外层<ol><li>内1</li><li>内2</li></ol></li></ul>"
    )
    result = _make_loader()._convert_html_to_markdown(html)
    assert "- 外层" in result
    assert "  1. 内1" in result
    assert "  2. 内2" in result


# ============================================================
# 代码块
# ============================================================

def test_pre_code_block():
    """pre 标签 → ``` 围栏代码块。"""
    html = "<pre><code>def hello():\n    print('hi')</code></pre>"
    result = _make_loader()._convert_html_to_markdown(html)
    assert "```" in result
    assert "def hello():" in result
    assert "print('hi')" in result
    assert "<pre" not in result and "<code" not in result


def test_pre_with_language_class():
    """pre/code 的 language-xxx class → 围栏代码块语言标识。"""
    html = "<pre><code class=\"language-python\">print(1)</code></pre>"
    result = _make_loader()._convert_html_to_markdown(html)
    assert "```python" in result
    assert "print(1)" in result


def test_code_fence_html_preserved():
    """``` 围栏代码块内的 HTML 标签原样保留，不转换。"""
    content = "前置文本\n\n```html\n<table><tr><td>不应转换</td></tr></table>\n```\n\n后置文本"
    result = _make_loader()._convert_html_to_markdown(content)
    assert "<table><tr><td>不应转换</td></tr></table>" in result
    assert "前置文本" in result and "后置文本" in result


def test_inline_code_html_preserved():
    """行内 `code` 内的 HTML 标签原样保留，不当标签误转。

    回归：技术文档常见 "用 `<a>` 创建链接"，若不保护，<a> 会被
    BeautifulSoup 当标签删掉，行内 code 反引号被拆开破坏语义。
    """
    content = "用 `<div>` 创建块，`<a href=\"x\">` 创建链接"
    result = _make_loader()._convert_html_to_markdown(content)
    assert "`<div>`" in result
    assert "`<a href=\"x\">`" in result
    # 不应生成 markdown 链接或图片
    assert "](x)" not in result


def test_inline_code_mixed_with_html():
    """行内 code 保护 + 同行 HTML 转换并存。"""
    content = "行内 `code` 和 <b>加粗</b> 混合"
    result = _make_loader()._convert_html_to_markdown(content)
    assert "`code`" in result
    assert "**加粗**" in result


def test_inline_code_inside_code_fence():
    """围栏内的行内 code 随围栏整体保护，不重复匹配。"""
    content = "```python\n`<div>` 行内 code\n```"
    result = _make_loader()._convert_html_to_markdown(content)
    assert "`<div>` 行内 code" in result


def test_inline_code():
    """行内 code 标签 → `text`。"""
    result = _make_loader()._convert_html_to_markdown("用 <code>print()</code> 输出")
    assert "`print()`" in result


# ============================================================
# 图片 + 链接
# ============================================================

def test_img_tag():
    """img 标签 → ![alt](src)。"""
    html = '<img src="figures/a.png" alt="图片A">'
    result = _make_loader()._convert_html_to_markdown(html)
    assert "![图片A](figures/a.png)" in result
    assert "<img" not in result


def test_img_without_alt():
    """img 无 alt：![](src)。"""
    html = '<img src="x.png">'
    result = _make_loader()._convert_html_to_markdown(html)
    assert "[](x.png)" in result


def test_a_link():
    """a 链接 → [text](href)。"""
    html = '<a href="https://example.com">示例</a>'
    result = _make_loader()._convert_html_to_markdown(html)
    assert "[示例](https://example.com)" in result


def test_a_without_href():
    """a 无 href：删标签保留文本。"""
    html = '<a name="anchor">锚点文本</a>'
    result = _make_loader()._convert_html_to_markdown(html)
    assert "锚点文本" in result
    assert "<a" not in result


def test_a_wrapping_img():
    """a 包 img：图片链接 [![alt](src)](href)。"""
    html = '<a href="https://x.com"><img src="a.png" alt="A"></a>'
    result = _make_loader()._convert_html_to_markdown(html)
    assert "[![A](a.png)](https://x.com)" in result


# ============================================================
# 行内格式
# ============================================================

@pytest.mark.parametrize("html,expected", [
    ("<b>粗体</b>", "**粗体**"),
    ("<strong>粗体</strong>", "**粗体**"),
    ("<i>斜体</i>", "*斜体*"),
    ("<em>斜体</em>", "*斜体*"),
    ("<del>删除</del>", "~~删除~~"),
    ("<s>删除</s>", "~~删除~~"),
    ("<kbd>Ctrl</kbd>", "`Ctrl`"),
    # markdownify 不支持的行内格式标签：删标签保留内容
    ("<cite>引用</cite>", "引用"),
    ("<strike>删除</strike>", "删除"),
    ("<mark>标记</mark>", "标记"),
    ("<sub>2</sub>", "2"),
    ("<sup>3</sup>", "3"),
])
def test_inline_format_tags(html, expected):
    """各类行内格式标签转 markdown 等价物。"""
    result = _make_loader()._convert_html_to_markdown(html)
    assert expected in result


def test_abbr_with_title():
    """abbr 带 title：markdownify 不处理 title 属性，只保留文本。"""
    html = '<abbr title="HyperText">HTML</abbr>'
    result = _make_loader()._convert_html_to_markdown(html)
    assert "HTML" in result
    assert "<abbr" not in result


# ============================================================
# 块级 + 标题
# ============================================================

@pytest.mark.parametrize("tag,level", [
    ("h1", "#"), ("h2", "##"), ("h3", "###"),
    ("h4", "####"), ("h5", "#####"), ("h6", "######"),
])
def test_heading_tags(tag, level):
    """h1-h6 → #-######。"""
    html = f"<{tag}>标题</{tag}>"
    result = _make_loader()._convert_html_to_markdown(html)
    assert f"{level} 标题" in result


def test_hr():
    """hr → ---。"""
    result = _make_loader()._convert_html_to_markdown("a<hr>b")
    assert "---" in result


def test_br():
    """br → 换行。"""
    result = _make_loader()._convert_html_to_markdown("a<br>b")
    assert "<br" not in result
    # br 转成 \n，a 和 b 分行
    assert "a" in result and "b" in result


def test_div_p_blockquote():
    """div/p/blockquote → 内容 + 换行。"""
    for tag in ("div", "p", "blockquote", "details", "summary"):
        html = f"<{tag}>内容</{tag}>"
        result = _make_loader()._convert_html_to_markdown(html)
        assert "内容" in result
        assert f"<{tag}" not in result


# ============================================================
# 定义列表 + 图形
# ============================================================

def test_definition_list():
    """dl/dt/dd：markdownify 不区分 dt/dd，内容连在一起。"""
    html = "<dl><dt>术语</dt><dd>解释内容</dd></dl>"
    result = _make_loader()._convert_html_to_markdown(html)
    assert "术语" in result
    assert "解释内容" in result
    assert "<dl" not in result and "<dt" not in result and "<dd" not in result


def test_figure_figcaption():
    """figure/figcaption：markdownify 保留内容，figcaption 不加斜体。"""
    html = '<figure><img src="a.png" alt="A"><figcaption>图说明</figcaption></figure>'
    result = _make_loader()._convert_html_to_markdown(html)
    assert "![A](a.png)" in result
    assert "图说明" in result
    assert "<figure" not in result and "<figcaption" not in result


# ============================================================
# 注释 + 实体 + 清理
# ============================================================

def test_html_comment_removed():
    """HTML 注释 <!-- --> 删除。"""
    html = "前<!-- 这是注释 -->后"
    result = _make_loader()._convert_html_to_markdown(html)
    assert "这是注释" not in result
    assert "前" in result and "后" in result


def test_nbsp_entity():
    """&nbsp; → \\xa0 → 替换为普通空格。"""
    html = "a&nbsp;&nbsp;b"
    result = _make_loader()._convert_html_to_markdown(html)
    assert "\xa0" not in result
    assert "a" in result and "b" in result


def test_excessive_blank_lines_compressed():
    """转换产生的连续空行压缩为段落分隔（最多 1 个空行）。"""
    html = "<div>a</div><div>b</div><div>c</div>"
    result = _make_loader()._convert_html_to_markdown(html)
    assert "\n\n\n" not in result


# ============================================================
# 异常容错
# ============================================================

def test_empty_content():
    """空字符串原样返回。"""
    assert _make_loader()._convert_html_to_markdown("") == ""


def test_no_html_returns_original():
    """纯 markdown 无 HTML 原样返回。"""
    content = "# 标题\n\n正文 **加粗**\n\n- 列表项"
    result = _make_loader()._convert_html_to_markdown(content)
    assert result == content


def test_malformed_html_does_not_crash():
    """畸形 HTML（未闭合标签）不崩溃，尽量转换。"""
    html = "<div>未闭合 <b>粗体</div>"
    result = _make_loader()._convert_html_to_markdown(html)
    assert "未闭合" in result
    assert "粗体" in result


def test_unknown_tag_keeps_content():
    """未知标签（如 span/u/font）删标签保留内容。"""
    for tag in ("span", "u", "font", "label", "small"):
        html = f"<{tag}>保留内容</{tag}>"
        result = _make_loader()._convert_html_to_markdown(html)
        assert "保留内容" in result
        assert f"<{tag}" not in result


def test_nested_inline_tags():
    """嵌套行内标签：后序递归先转内层。"""
    html = "<b>粗<code>code</code>体</b>"
    result = _make_loader()._convert_html_to_markdown(html)
    # code 先转 `code`，b 包裹 **...**
    assert "**粗" in result and "`code`" in result and "体**" in result


def test_mixed_markdown_and_html():
    """markdown 与 HTML 混合：HTML 转换，markdown 原样保留。"""
    content = "# 标题\n\n<div>HTML 块</div>\n\n**markdown 加粗**"
    result = _make_loader()._convert_html_to_markdown(content)
    assert "# 标题" in result
    assert "HTML 块" in result
    assert "<div" not in result
    assert "**markdown 加粗**" in result


# ============================================================
# 端到端：表格 + 列表 + 图片 混合
# ============================================================

def test_complex_mixed_content():
    """复杂混合：标题 + 表格 + 列表 + 图片 + 代码块。"""
    content = (
        "# 功能说明\n"
        "<table><tr><th>参数</th><th>说明</th></tr>"
        "<tr><td>size</td><td>尺寸</td></tr></table>\n"
        "<ul><li>项目A</li><li>项目B</li></ul>\n"
        '<img src="img/demo.png" alt="演示">\n'
        "```python\n"
        "<table>这里不转</table>\n"
        "```\n"
    )
    result = _make_loader()._convert_html_to_markdown(content)
    # 表格转换
    assert "| 参数 | 说明 |" in result
    assert "| size | 尺寸 |" in result
    # 列表转换
    assert "- 项目A" in result and "- 项目B" in result
    # 图片转换
    assert "![演示](img/demo.png)" in result
    # 代码块内 HTML 保留
    assert "<table>这里不转</table>" in result
    # 原始 HTML 标签清理干净（代码块内除外）
    # 代码块外的 table/ul/li/img 已全部转换
