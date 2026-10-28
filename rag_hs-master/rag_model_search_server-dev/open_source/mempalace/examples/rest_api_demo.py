#!/usr/bin/env python3
"""MemPalace REST adapter —— 客户端示例：建知识库 → 存数据 → 查数据。

演示如何通过 REST API 完整操作一个 MemPalace 知识库。先起服务再跑本脚本：

    # 起 GPU + BGE-M3 服务（内网免鉴权）
    docker run -d -p 8777:8777 --gpus all -v mempalace-data:/data \
      -v /disk/models/bge-m3:/models/bge-m3:ro \
      -e MEMPALACE_REST_HOST=0.0.0.0 -e MEMPALACE_REST_NO_AUTH=1 \
      -e MEMPALACE_EMBEDDING_MODEL=bgem3 -e MEMPALACE_BGE_M3_PATH=/models/bge-m3 \
      -e MEMPALACE_EMBEDDING_DEVICE=cuda \
      mempalace:rest-bgem3

    # 跑本示例
    pip install requests
    python examples/rest_api_demo.py

环境变量（可选）：
    MEMPALACE_REST_URL       服务地址（默认 http://127.0.0.1:8777）
    MEMPALACE_REST_TOKEN     bearer token；服务端开了 NO_AUTH 就留空
    MEMPALACE_DEMO_PALACE    演示用的宫殿编号（默认 demo-kb）
"""

from __future__ import annotations

import os
import sys

import requests

BASE_URL = os.environ.get("MEMPALACE_REST_URL", "http://127.0.0.1:8777").rstrip("/")
TOKEN = os.environ.get("MEMPALACE_REST_TOKEN", "")
PALACE = os.environ.get("MEMPALACE_DEMO_PALACE", "demo-kb")


class MempalaceClient:
    """对 REST 适配层的薄封装。"""

    def __init__(self, base_url: str, token: str = "", timeout: int = 300):
        self.base = base_url
        self.timeout = timeout
        self.session = requests.Session()
        if token:
            self.session.headers["Authorization"] = f"Bearer {token}"

    def health(self) -> bool:
        r = self.session.get(f"{self.base}/healthz", timeout=5)
        return r.status_code == 200

    def create_palace(self, palace: str | None = None) -> dict:
        params = {"palace": palace} if palace else {}
        r = self.session.post(f"{self.base}/palace", params=params, timeout=60)
        r.raise_for_status()
        return r.json()

    def store(self, entry: str, palace: str | None = None,
              topic: str = "general", agent_name: str = "demo") -> dict:
        payload = {"entry": entry, "topic": topic, "agent_name": agent_name}
        if palace:
            payload["palace"] = palace
        # 首次写入会加载嵌入模型（GPU BGE-M3 ~10s），故给长超时
        r = self.session.post(f"{self.base}/store", json=payload, timeout=self.timeout)
        r.raise_for_status()
        return r.json()

    def search(self, query: str, palace: str | None = None,
               limit: int = 5, detail: str = "snippet") -> dict:
        params = {"q": query, "limit": limit, "detail": detail}
        if palace:
            params["palace"] = palace
        r = self.session.get(f"{self.base}/search", params=params, timeout=60)
        r.raise_for_status()
        return r.json()


def main() -> int:
    client = MempalaceClient(BASE_URL, TOKEN)
    print(f"服务: {BASE_URL}  |  宫殿: {PALACE}  |  鉴权: {'token' if TOKEN else 'NO_AUTH(免)'}")

    # 0) 健康检查
    if not client.health():
        print(f"✗ 服务不可达：{BASE_URL}/healthz", file=sys.stderr)
        return 1
    print("✓ 服务在线\n")

    # 1) 创建知识库（幂等）
    print(f"[1] 创建知识库 “{PALACE}”")
    res = client.create_palace(PALACE)
    print(f"    success={res['success']}  already_exists={res['already_exists']}")
    print(f"    palace_path={res['palace_path']}\n")

    # 2) 存数据
    entries = [
        ("MemPalace 用 ChromaDB 做向量存储，支持向量 + BM25 混合检索", "架构"),
        ("项目 Phoenix 部署在 Kubernetes 集群，上线截止 2026 年 Q3", "进度"),
        ("BGE-M3 是 1024 维多语言嵌入模型，中文检索效果最好", "模型"),
        ("用户 Alice 负责后端，Bob 负责前端", "团队"),
    ]
    print(f"[2] 存入 {len(entries)} 条数据")
    for entry, topic in entries:
        res = client.store(entry, palace=PALACE, topic=topic)
        print(f"    [{topic:4}] entry_id={res['entry_id'][:48]}…")
    print()

    # 3) 查数据（语义检索；用不同问法，验证语义而非关键词匹配）
    print("[3] 语义检索")
    queries = [
        "向量数据库的方案",
        "什么时候上线",
        "中文搜索用什么模型",
        "谁做前端",
    ]
    for q in queries:
        res = client.search(q, palace=PALACE, limit=1, detail="snippet")
        results = res.get("results", [])
        if results:
            hit = results[0]
            text = hit.get("text", "")
            text = (text[:42] + "…") if len(text) > 42 else text
            print(f"    q=“{q}”")
            print(f"      → sim={hit.get('similarity')}  |  {text}")
        else:
            print(f"    q=“{q}” → 无结果")
    print()

    # 4) 隔离验证：建一个「其它」空宫殿，查它，确认不会返回 PALACE 的数据
    other_palace = f"{PALACE}-other"
    print(f"[4] 隔离验证（建空库 “{other_palace}”，查同一关键词，应为 0 命中）")
    client.create_palace(other_palace)
    other = client.search("向量数据库", palace=other_palace, limit=5)
    print(f"    “{other_palace}” 命中数: {other.get('count', 0)}  (应为 0 —— 与 “{PALACE}” 完全隔离)")
    back = client.search("向量数据库", palace=PALACE, limit=1)
    print(f"    “{PALACE}”      命中数: {back.get('count', 0)}  (对照：原库正常召回)")
    print()

    print("✓ 完成")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
