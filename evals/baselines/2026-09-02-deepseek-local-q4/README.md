# DeepSeek-Coder-V2-Lite-Instruct 本地 Q4 基线

这是 LegacyPilot `v0.3-core.1` 的真实本地模型结果，不是 reference ceiling。

- 模型：`hf.co/bartowski/deepseek-coder-v2-lite-instruct-gguf:q4_k_m`
- 运行环境：Apple M2 Max、32 GB、Docker Model Runner/llama.cpp、loopback HTTP
- smoke：`deepseek-local-smoke-20260902-a9`，4/5
- 完整基线：`deepseek-local-full-20260902-v6`，9/20
- 完整候选：`deepseek-local-full-20260902-v8`，9/20
- 候选 Token：93,382；本地推理成本：0 美元
- 候选耗时：p50 10.883 秒；p95 31.941 秒
- 候选失败：断言 2、测试 3、编译 6；基础设施错误 0
- 回归：0 项；改进：0 项
- 发布门禁：失败，唯一原因是 45% 低于 70%

机器可读对比、Markdown 报告和离线 Dashboard 位于 `evals/runs/deepseek-local-comparison-v6-v8-corrected/`。原始工作区和包含本机绝对路径的 checkpoint 保持在忽略目录中，不进入交付包。
