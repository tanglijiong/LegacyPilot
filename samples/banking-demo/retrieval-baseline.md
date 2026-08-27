# Banking Demo Retrieval Baseline

固定索引：`banking-fixture-v2`
查询：`daily transfer limits`
相关符号：`io.legacypilot.samples.banking.TransferService`
K：10

| Retriever | Recall@10 最低门槛 |
| --- | ---: |
| Lucene BM25 | 1.0 |
| Exact + BM25 + disabled Vector Hybrid | 1.0 |

该基线由 `RetrievalAndContextTest` 在构建中重新计算。Vector 默认关闭，确保基线不依赖外部 Embedding 服务。
