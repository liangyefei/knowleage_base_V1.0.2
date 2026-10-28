"""pipeline 子包共享常量：路由模型名、RRF 平滑常数、Reranker/代码路预算、关键词词表。"""
from configs.kb_loader import get_config as _get_kb_config

# 路由 LLM 使用的模型名（从 ONLINE_LLM_MODEL 配置中读取）
_ROUTER_MODEL_NAME = "qwen-flash"

# RRF 平滑常数，常用 60
RRF_K = 60

# reranker top_n 按需放宽的上限（配置值为下限，请求 top_k 可放宽到此值，防止 API 成本无界）
_RERANK_TOP_N_MAX = 30

# dual-mcp /query include_source：前 N 个命中附源码
_CODE_SOURCE_TOP = 5

# 代码路保底：最终列表中 code 内容的目标下限（字符）
_CODE_CTX_GOAL = 3600

# 问候语词表：从 kb_config.yaml 的 GREETING_WORDS 加载，支持热加载
_GREETING_WORDS = set(_get_kb_config("GREETING_WORDS", [
    "你好", "您好", "嗨", "哈喽", "早上好", "下午好", "晚上好",
    "谢谢", "感谢", "谢了", "多谢", "再见", "拜拜", "拜", "晚安",
    "在吗", "在么", "在不在", "你是谁", "你是啥", "你是",
    "好的", "好", "嗯", "哦", "OK",
    "hi", "hello", "hey", "thanks", "thx", "ty",
    "bye", "goodbye", "ok",
]))

# 技术关键词词表：命中则直接判定 need_rag=True，跳过 route LLM（省 ~1.7s/请求）
# 从 kb_config.yaml 的 TECH_KEYWORDS 加载，支持热加载
_TECH_KEYWORDS = set(_get_kb_config("TECH_KEYWORDS", [
    # 芯片/型号（完整型号，避免短缩写误判）
    "CV610", "CV620", "HI3516", "HI3518", "海思", "芯片", "型号", "SOC", "MCU",
    # 硬件术语
    "DDR", "寄存器", "地址", "时序", "引脚", "时钟", "电源", "中断", "复位",
    "GPIO", "I2C", "SPI", "UART", "PCIe", "USB", "JTAG", "ADC", "DAC", "PWM",
    # 软件/SDK
    "SDK", "驱动", "内核", "编译", "烧录", "启动", "配置", "初始化",
    # 文档
    "手册", "规格", "datasheet", "文档", "说明",
]))

# 闲聊关键词词表：命中则直接判定 need_rag=False，跳过 route LLM
# 从 kb_config.yaml 的 CHITCHAT_KEYWORDS 加载，支持热加载
_CHITCHAT_KEYWORDS = set(_get_kb_config("CHITCHAT_KEYWORDS", [
    "讲个笑话", "今天天气", "你是男是女", "你几岁", "讲个故事",
    "唱首歌", "无聊", "陪我聊", "你是AI", "你是机器人",
]))

# 无法回答检测 marker：中文"无法回答"4字特异性高；英文收紧为模板 mandated 措辞
# （大小写不敏感，调用方对 full_response.lower() 做 in 判定），避免 "cannot be answered"
# 常见短语在正常英文答案中误判并清空 docs。流式/非流式两处共用此常量。
_UNANSWERABLE_MARKERS = ("无法回答", "this question cannot be answered")


def _lang_text(zh: str, en: str, language: str) -> str:
    """中英文案二选一：language=='en' 返回 en，否则降级 zh。

    用于 route_reasoning 事件 content 字段等"非答案正文"的状态文案 i18n，
    把语言切换收敛到一处调用，避免散落的三元表达式。
    """
    return en if language == "en" else zh
