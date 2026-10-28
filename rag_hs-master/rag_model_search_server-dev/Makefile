# UD 测试门禁入口
# 测试环境在 Docker 容器 haisi_rag_models_server_dev (见 docker/run_wsl_all.md)
# 通过 docker exec 在容器内执行 pytest / pylint / ruff

DOCKER ?= sudo docker
CONTAINER := haisi_rag_models_server_dev
WORKDIR := /mnt/d/Code/haisi/haisi_rag_models_server
PYTEST := $(DOCKER) exec -w $(WORKDIR) $(CONTAINER) python3 -m pytest
PYLINT := $(DOCKER) exec -w $(WORKDIR) $(CONTAINER) python3 -m pylint
RUFF   := $(DOCKER) exec -w $(WORKDIR) $(CONTAINER) python3 -m ruff

# 业务代码目录（pylint 主检查范围）
LINT_TARGETS := server configs document_loaders webui_pages text_splitter tools startup.py init_database.py

.PHONY: test test-unit test-it test-mst cov collect install-dev \
        lint lint-tests lint-ruff lint-fix lint-baseline

test: test-unit

test-unit:
	$(PYTEST) tests/unit

test-it:
	$(PYTEST) tests/it

test-mst:
	$(PYTEST) tests/mst -k connectivity

cov:
	$(PYTEST) tests/unit --cov=server --cov=configs --cov-report=html --cov-report=term-missing

collect:
	$(PYTEST) --collect-only tests/

install-dev:
	$(DOCKER) exec $(CONTAINER) bash -c "cd $(WORKDIR) && pip install -r docker/requirements-dev.txt"

# ==================== 静态检查（B0 批次引入）====================
# 详见 docs/todo/代码扫描问题改造计划.md

# pylint：业务代码主检查（B0 后作为门禁，新代码不允许引入已禁用规则外的问题）
# B2 豁免 broad-exception-caught：239 条绝大多数为 FastAPI handler 兜底 500 / 外部 API 调用 / 批处理 fail-open，
# 改造代价高且 broad-exception-caught 本身为风格规则，参考 B5 protected-access 豁免模式
lint:
	$(PYLINT) --disable=broad-exception-caught $(LINT_TARGETS)

# pylint：测试代码单独检查（B5 豁免 protected-access，因测试访问 mock 对象的 protected 成员是 pytest 惯例）
# B2 豁免 broad-exception-caught：测试 fixture/teardown 常需捕获 Exception 防止清理失败拖垮后续用例
# B6 豁免测试惯例规则：
#   redefined-outer-name（151）：pytest fixture 函数名常与 import 模块同名，是测试惯例
#   unused-argument（146）：mock 装饰器要求签名与被 mock 函数一致，常有未用参数
#   reimported（35）：测试 mock 别名 `import json as _json` 形式，与模块级 import 同名
#   redefined-builtin：测试中重定义 print/open 等用于 mock
#   invalid-name（36）：测试变量短名/常量大写/函数名非 snake_case 是惯例
#   no-value-for-parameter（96）：业务 repository/chat 函数均用 @with_session 装饰器注入 session
#     参数，测试调用时不传 session（由装饰器注入），pylint 不理解装饰器故全部误报
lint-tests:
	$(PYLINT) --disable=protected-access,broad-exception-caught,redefined-outer-name,unused-argument,reimported,redefined-builtin,invalid-name,no-value-for-parameter tests/unit tests/it tests/mst tests/scripts

# ruff：快速 import/未用变量/字面量检查（B4 批次会用 --fix 自动修一部分）
lint-ruff:
	$(RUFF) check $(LINT_TARGETS) tests

# ruff：自动修复可机械修复的问题（仅本地用，PR 不要直接 commit --fix 结果）
lint-fix:
	$(RUFF) check --fix $(LINT_TARGETS) tests

# 生成最新扫描基线（B0 产出 _baseline_v2.json，后续批次对照消除）
# 输出 json 到 docs/todo/_baseline_v2.json，覆盖原 rag_issues_code_check.json 的失效基线
# B2：与 lint / lint-tests 保持一致，业务+测试均豁免 broad-exception-caught（测试额外豁免 protected-access）
# B6：测试额外豁免 redefined-outer-name/unused-argument/reimported/redefined-builtin/invalid-name/no-value-for-parameter
lint-baseline:
	$(PYLINT) --output-format=json2 --disable=broad-exception-caught $(LINT_TARGETS) > docs/todo/_baseline_v2.json
	$(PYLINT) --output-format=json2 --disable=protected-access,broad-exception-caught,redefined-outer-name,unused-argument,reimported,redefined-builtin,invalid-name,no-value-for-parameter tests/unit tests/it tests/mst tests/scripts >> docs/todo/_baseline_v2.json
	@echo "Baseline written to docs/todo/_baseline_v2.json"
