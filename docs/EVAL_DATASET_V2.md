# Eval Dataset v2 与 Fixture 治理

v0.3 使用 `eval-dataset-v2` 数据契约。它的目的不是增加配置量，而是在任何模型调用和计费发生前，确定任务、fixture、允许修改范围和预算没有漂移。

## 目录与加载顺序

```text
evals/
  fixtures/<fixture-id>/provenance.yml
  datasets/v0.3/
    manifest.yml
    task-001/{task.yml,assertions.yml}
```

加载器按 `manifest.yml` 中的 task 顺序运行，不依赖文件系统枚举顺序。加载期间会：

1. 验证 manifest schema、任务 ID 唯一性与 dataset SHA-256；
2. 验证每个 fixture 的来源、revision、许可证、构建命令和内容 SHA-256；
3. 拒绝 symlink、越出项目根目录的 fixture，以及指向 `evals/reference-solutions` 的 fixture；
4. 验证任务的难度、变更类型、影响面、允许/禁止文件、超时和资源预算；
5. 确保 expected production files 全部处于 allowedFiles 中，且不与 forbiddenFiles 重叠。

SHA-256 对排序后的相对路径与文件内容计算；`.git`、`.legacy-pilot` 和 Maven `target` 目录不参与 fixture digest。dataset digest 只覆盖 manifest 明确列出的 task 目录，manifest 自身不进入 digest，避免自引用。

## 任务边界

每个任务显式记录：

- `fixtureId`、`category`、`difficulty`、`changeType`、`expectedImpact`；
- `allowedFiles`、`forbiddenFiles` 与 `expectedFiles`；
- `maximumSteps`、`timeoutSeconds`；
- Token、内存和成本上限；
- 公开确定性 assertions。

`WorkspaceIntegrityGuard` 在候选运行前后比较文件摘要。修改 `pom.xml`、测试、未授权生产文件，删除预期文件或引入 symlink 都会 fail closed。编译与测试仍由 fixture verifier 执行；reference solution 只供 ceiling 流程使用，不属于 provider workspace 或索引输入。

## 当前冻结程度

`v0.3-draft.1` 先迁移原有 task-001–005，用来校准 schema、fixture registry 和完整性检查。它还不是最终 20-task dataset；task-006–020 完成 ceiling 与完整性验证后才会冻结正式 manifest。

旧 `evals/datasets/v0.1` 保持原文件不变，加载器继续兼容其目录格式。
