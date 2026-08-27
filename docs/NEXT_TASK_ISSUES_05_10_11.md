# 长期任务：安全沙箱与受控工具运行时

**覆盖 Issues：** 05、10、11
**状态：** ✅ Completed（真实 Docker 集成测试在 daemon 与固定镜像可用时自动执行）
**开始日期：** 2026-08-27

## 目标

把已完成的隔离 Git worktree 升级为可执行但默认拒绝的工具环境：模型只能发现并调用注册的类型化工具；所有输入经过 JSON Schema、大小与策略校验；Maven 只能在资源受限、默认无网络的非 root Docker 容器中运行。

## 安全不变量

- 不提供接受 shell 字符串的 Agent 工具。
- 所有目标路径必须解析在当前 Run 的 workspace 内，符号链接逃逸失败关闭。
- `READ_ONLY` 默认允许，`WORKSPACE_WRITE` 绑定 action digest 审批，`COMMAND_EXECUTION` 还需命中参数白名单，`EXTERNAL_IO` 默认拒绝。
- 容器使用只读根文件系统、非 root UID/GID、无网络、移除 capabilities、禁止提权，并限制 CPU、内存、PID、临时空间、运行时间和输出。
- 批准绑定规范化工具输入；输入变化后旧批准失效。
- Maven cache 只读挂载，默认离线；依赖预热属于显式运维步骤。

## 实现切片

1. `tool-spi`：Descriptor、JSON Schema、Registry、Policy、Executor、结构化结果与脱敏。
2. `sandbox-docker`：安全命令构造、容器生命周期、timeout/cancel、输出和空间预算。
3. `tool-filesystem`：read/search、基于内容摘要的 create/apply patch。
4. `tool-git`：只读 diff 与变更统计。
5. `tool-maven`：compile、test、单类测试、静态分析；Goal/Profile/Property 白名单。
6. 契约、安全与 Docker 集成测试；同步 README、架构、ADR 和 Backlog。

## 当前环境说明

Docker CLI 可用，但任务开始时 Docker daemon 未运行。确定性测试与安全命令构造测试照常执行；真实容器集成测试采用 daemon 探测，在可用环境执行，在当前环境明确跳过并保留验收命令。

## 完成结果

- 已交付 5 个模块：`tool-spi`、`sandbox-docker`、`tool-filesystem`、`tool-git`、`tool-maven`，并接入 Spring 启动配置。
- Tool Executor 统一处理注册、Schema、策略、审批摘要、timeout、脱敏与输入输出上限。
- Docker 默认使用固定 Maven 镜像、无网络、只读根文件系统、非 root、移除 capabilities、禁止提权和资源限制；支持取消、重复执行 ID 防护及容器回收。
- 文件工具拒绝绝对路径、`..`、符号链接与可检测的硬链接逃逸；补丁采用内容摘要和原子替换，冲突时不覆盖。
- Maven 仅暴露固定操作和参数数组，Profile/Property/Test selector 均需通过白名单与格式校验。
- `./mvnw clean verify` 覆盖格式、Checkstyle、SpotBugs、JaCoCo 与测试；真实容器用例按环境条件运行或明确跳过。
