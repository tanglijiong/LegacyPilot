# v0.3 候选发布检查

从仓库根目录执行：

```bash
./deploy/candidate/verify.sh
```

运行前需要 JDK 21、Node.js 22、Git、Chromium 安装能力和已启动的 Docker daemon；CHANGE 的 Maven 验证在离线容器 sandbox 中执行。

该入口先验证候选 manifest 和公开材料安全边界，再运行 Java 全仓验证、Web 生产构建、真实后端浏览器 E2E，最后在同一固定 Banking Demo revision 上连续执行三次面试闭环演练。

失败诊断保存在 `apps/web/output/playwright/`。`release-gate-results.json` 记录完整浏览器门禁，`interview-rehearsal-results.json` 记录三次演练。目录只包含测试身份和临时环境摘要，不保存真实密钥。

候选结论严格分为两部分：产品演示验收已通过；`v0.3-core.1` 的 20 题模型质量为 9/20，低于 14/20 门槛，仍未通过。
