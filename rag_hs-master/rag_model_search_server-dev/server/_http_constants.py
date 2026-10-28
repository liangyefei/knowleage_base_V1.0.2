"""
HTTP / 安全相关常量

本文件集中管理业务代码中高频复用的字面量，避免在多个业务文件中重复定义。
仅为 CodeArts H3103（G.TYP.03 难以理解的字面量）规则抽常量使用，
不包含任何业务逻辑。
"""

# HTTP 状态码
HTTP_OK = 200                     # 请求成功
HTTP_BAD_REQUEST = 400            # 参数错误 / 请求格式错误
HTTP_FORBIDDEN = 403              # 拒绝访问（安全过滤命中）
HTTP_NOT_FOUND = 404              # 资源不存在
HTTP_CONFLICT = 409               # 资源已存在（覆盖重建提示）
HTTP_INTERNAL_ERROR = 500         # 服务器内部错误

# HTTP 方法
HTTP_METHOD_GET = "GET"
HTTP_METHOD_POST = "POST"

# 安全提示消息（跨文件复用，避免散落各处）
SECURITY_ATTACK_MSG = "Don't attack me"
SUCCESS_MSG = "success"

# HTTP 客户端超时（秒）
MILVUS_SEARCH_TIMEOUT_SEC = 5.0          # Milvus 检索超时（/search_docs 端点防挂起）
HTTP_UPLOAD_TIMEOUT_SEC = 30             # HTTP 文件上传超时
HTTP_INDEX_TIMEOUT_SEC = 60              # 代码索引请求超时
HTTP_DOWNLOAD_TIMEOUT_SEC = 120          # HTTP 文件下载超时
HTTP_MCP_PROJECTS_TIMEOUT_SEC = 5.0      # MCP /projects 列表超时
HTTP_MCP_QUERY_TIMEOUT_SEC = 15.0        # MCP /query 检索超时
HTTP_MCP_QUERY_FALLBACK_TIMEOUT_SEC = 3.0  # MCP /query 兜底超时
HTTP_MCP_CONNECT_TIMEOUT_SEC = 2.0       # MCP 连接超时
HTTP_MCP_FALLBACK_TOTAL_TIMEOUT_SEC = 10.0  # MCP 兜底总超时
HTTP_ASYNC_SHIELD_TIMEOUT_SEC = 5.0      # asyncio.shield 兜底超时
LLM_ROUTE_TIMEOUT_SEC = 15.0             # LLM 路由决策超时（fail-open RAG）
