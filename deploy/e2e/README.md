# Issue 47 浏览器发布门禁

运行完整发布门禁：

```bash
./deploy/e2e/verify.sh
```

该入口依次执行 Java 全仓 `verify`、Web 依赖锁定安装与生产构建、Chromium 安装和 Playwright E2E。浏览器测试使用全新的临时 Banking Demo Git 仓库、H2 数据库、受管 workspace 和确定性 loopback 模型 fixture，覆盖项目预检、注册和自动切换；所有页面请求仍进入真实 REST/SSE 服务、权限过滤器、持久化 command 与 Agent runtime；唯一的浏览器路由拦截用于注入 SSE 断线，不返回模拟业务数据。

失败诊断写入 `apps/web/output/playwright/`：

- HTML 与 JSON 报告；
- 失败截图、视频和 Playwright trace；
- 服务端日志，其中包含 run 与 correlation ID，但不包含临时 demo token。

CI 将该目录作为 `playwright-diagnostics` artifact 保存 14 天。测试覆盖 EXPLAIN、ANALYZE、CHANGE 审批、刷新恢复、SSE 轮询降级、安全点取消、模型失败重试、认证、角色、幂等、源码路径穿越、键盘焦点、窄屏布局和 WCAG 严重问题扫描。
