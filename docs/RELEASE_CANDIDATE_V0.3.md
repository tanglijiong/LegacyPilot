# LegacyPilot v0.3.0-rc.1 候选发布说明

## 候选结论

截至 2026-09-07，Web 产品演示验收、安全与恢复门禁、11 组真实后端浏览器 E2E 和同 revision 三次演练已通过；项目接入流程支持不运行编译地预检本地/公共 Git 项目，并明确展示语言相关能力边界；本地 DeepSeek 主演示也已现场完成真实 `EXPLAIN`、源码证据查看和停服重启恢复。该结论不等于模型质量发布通过：`v0.3-core.1` 当前为 9/20（45%），低于 14/20（70%）门槛。

因此候选状态是 `PRODUCT_DEMO_ACCEPTED_MODEL_QUALITY_NOT_MET`，用于面试演示和后续工程评审，不应发布成“v0.3 模型质量达标”的正式版本。

## 可复现配置

| 项目 | 固定值 |
| --- | --- |
| Banking Demo revision | `ee79b3282e22aaf419a137666a57a2b1b3f4f875` |
| Java | 21 或更高 |
| Web | Node.js 22，生产构建由 Vite 执行 |
| 验证隔离 | 已启动的 Docker daemon，Maven 容器无网络 |
| 主演示模型 | Docker Model Runner 本地 DeepSeek-Coder-V2-Lite-Instruct Q4_K_M，8192 context，仅 loopback |
| 自动演练模型 | 确定性本地 fixture，只证明控制面，不计模型分数 |
| 数据库 | 演示/浏览器门禁使用本地 H2；银行参考部署使用 PostgreSQL |
| 浏览器 | Playwright Chromium |

完整候选检查：

```bash
./deploy/candidate/verify.sh
```

## 已通过的产品质量门槛

- Java 全仓构建、测试、格式、静态分析与覆盖率门禁；
- Web 类型检查和生产构建；
- EXPLAIN、ANALYZE、CHANGE 真实后端浏览器路径；
- 刷新恢复、SSE 真实断流续传、Java 进程强制终止恢复、取消、重试和独立审批；
- 真实 Maven 编译失败、重新规划、新 digest 重新审批和修复后验证成功；
- 认证、角色、幂等、并发版本和源码路径安全负向用例；
- 键盘、焦点、390px 窄屏和 axe 严重问题扫描；
- 同一固定 Banking Demo revision 的完整面试闭环连续三次通过；
- 公开材料的个人绝对路径和常见凭据模式检查。

## 已知限制与未通过项

- 20 题本地 DeepSeek 质量仅 9/20；固定场景成功不能外推为通用 Java 变更成功率。
- H2 浏览器演示不能替代 PostgreSQL、容器镜像 digest 和银行环境集成证据。
- Demo 身份仅用于 loopback 演示；生产必须配置 OIDC、网络边界和真实职责分离。
- 自动演练的模型 fixture 是确定性的控制面测试替身，不是模型能力基准。
- 主演示依赖本地模型性能；冷启动时间和硬件吞吐不属于当前候选 SLA。

## 发布判定

| 判定维度 | 状态 |
| --- | --- |
| 面试级产品闭环 | 通过 |
| 安全、恢复、可访问性回归 | 通过 |
| 三次固定 revision 演练 | 通过 |
| 20 题模型质量 ≥ 70% | **未通过（45%）** |
| 正式 v0.3 模型质量发布 | **阻断** |

机器可读配置位于 `deploy/candidate/manifest.json`，演示过程与答辩材料位于 `docs/INTERVIEW_DEMO.md`。
