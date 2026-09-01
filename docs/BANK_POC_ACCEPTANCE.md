# 银行内网 PoC 验收手册

## 1. 验收前置

- 所有 JAR、镜像、模型权重、Prompt、Policy 和数据集摘要均已批准并记录。
- 测试使用公开 fixture 或机构批准的脱敏仓库，不使用生产代码和生产凭据。
- 宿主防火墙、OIDC、PostgreSQL、加密卷、rootless Docker 和模型 socket 已配置。
- 验收人员至少包含开发者、独立审批人、审计员和管理员四个身份。

## 2. 自动化门禁

```bash
./mvnw clean verify
python3 -m unittest discover -s deploy/deepseek -p 'test_*.py' -v
java -jar apps/cli/target/legacy-pilot-cli-0.3.0-SNAPSHOT.jar eval-run
```

开发验证可以使用上述命令。候选证据生成必须先设置已批准且完整 digest 固定的 `LEGACY_PILOT_MAVEN_IMAGE` 和 `LEGACY_PILOT_POSTGRES_TEST_IMAGE`，确认镜像已导入离线 Docker 存储，再运行 `deploy/bank-poc/build-evidence.sh`；脚本对缺失、非 digest 或本地不存在的镜像失败关闭，并在不启动额外 Ryuk 镜像的情况下运行全量验证。

要求：全量构建和静态检查通过；两项 Maven sandbox 与一项 PostgreSQL 真实 Docker 集成测试不得跳过；DeepSeek 契约测试通过；reference ceiling 为 20/20。Reference ceiling 只验证数据集，不能替代真实模型基线。证据摘要必须覆盖全部受版本控制的应用、部署、文档、评测和构建配置，以及最终构建的 JAR，而不只覆盖少数文件扩展名。

`build-evidence.sh` 会检查两份必需的集成测试报告；测试未执行、失败、报错或跳过时均退出 2。普通开发构建在 Docker 不可用时允许跳过这些环境测试，不能用该结果替代候选证据。

数据库升级验收必须同时覆盖两条路径：已有 H2/v0.2 数据库保持已发布 V1 checksum 并只追加后续迁移；全新 PostgreSQL 使用 `classpath:db/postgresql` 方言迁移。禁止为修复新数据库方言而改写已发布 migration。

## 3. 安全验收

1. 未认证请求被拒绝。
2. `AUDITOR` 不能创建项目、执行任务或发放 Capability。
3. `DEVELOPER` 不能审批或管理 Capability。
4. 执行发起人不能审批同一 run。
5. 客户端提交的 actor/subject 不覆盖 JWT 身份。
6. 路径穿越、symlink、worktree 外写入和受保护文件修改被拒绝。
7. Sandbox 和模型容器均无法访问 DNS、内网和公网测试地址。
8. 镜像、权重或 service manifest 摘要变化时启动失败。
9. JWT、Authorization、数据库密码和测试哨兵不出现在 Trace/报告。
10. 不确定写操作进入 `NEEDS_REVIEW`，恢复不重复调用已完成动作。

## 4. 功能验收

使用统一 API 完成：注册固定 revision 项目 → 创建任务 → 启动 run → 自动索引/规划 → 查看待审批 action → 独立审批 → resume → 编译/测试/静态检查 → 获取补丁和报告。验证原仓库未改变，全部写入仅发生在 run worktree。

取消、模型超时、Docker 失败、服务重启和数据库短暂中断分别执行一次；每种情况必须具有明确状态、关联 ID、可读错误和审计证据。

## 5. 模型验收

先运行 task-001–005 smoke，pass@1 至少 4/5 且无基础设施错误；再以新的不可变 run ID 运行 20 题全量。必须提交 manifest、逐任务 result、primary failure、成本/Token/耗时、comparison JSON、Markdown 和离线 Dashboard。

发布门槛：integrity violation 为 0；20 题 pass@1 目标不低于 70%；最多一次公开反馈后不低于 85%；候选策略最多回归 1 题，且回归 1 题时成本或 p95 至少改善 15%。未达标可以保留完整报告，但不能升级为默认策略。

## 6. 运维验收

- 完成一次 PostgreSQL + state 卷一致性备份和异机/空目录恢复。
- 完成一次 JAR/Policy 升级和回滚演练。
- 验证磁盘、模型、Docker、审计失败和 `NEEDS_REVIEW` 告警。
- 验证 SBOM、SHA256SUMS、第三方许可证和漏洞扫描报告与候选版本一致。

## 7. 签署输出

验收包应包含版本、Git commit、时间、环境、执行人、失败项、风险接受人、所有命令输出摘要和 artifact 哈希。不得把跳过项记为通过；依赖机构外部环境的项目应标记为阻塞并指定责任方。
