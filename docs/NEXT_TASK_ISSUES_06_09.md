# 长期任务：Java 代码智能与预算化上下文

**覆盖 Issues：** 06、07、08、09
**状态：** ✅ Completed
**开始日期：** 2026-08-27

## 目标

把固定 revision 的 Java workspace 转换成确定、可追溯的结构化索引，并通过精确匹配、BM25、可选 Vector 和依赖图扩展，为 Agent 构建严格不超过 Token 预算的代码上下文。

## 交付切片

1. `java-analyzer`：多模块源码发现、AST、符号、Spring 角色、问题报告和 revision/schema 身份。
2. `java-analyzer` dependency graph：继承、实现、import、字段类型、注入和方法调用边；保留未解析目标。
3. `context-engine`：统一 Retriever、精确匹配、Lucene BM25、可选 Vector 和 Hybrid 合并。
4. `context-engine`：图扩展、稳定引用、去重、多样性、Token 估算、packing 和淘汰原因。
5. Banking fixture、golden symbol snapshot、调用链与 Recall@K 基线。

## 确定性与安全约束

- 索引输入固定为 workspace 和 Git revision，文件与输出排序稳定。
- 只读取 Maven 标准源码根中的 `.java` 文件；跳过符号链接、构建输出与非源码目录。
- 单文件语法错误记录为 `IndexProblem`，不能中止整个项目索引。
- 所有符号、边和上下文引用均可回到文件与行列范围。
- 未解析依赖显式保留，不能被静默丢弃或伪装成高置信关系。
- Vector Retriever 未配置或失败时，精确与 BM25 基线仍正常工作。
- Context Builder 对任何输入都不得超过配置 Token 预算，并记录入选与淘汰理由。

## 完成结果

- `java-analyzer` 已支持 Maven 多模块 `src/main/java` 与 `src/test/java`，提取 package、import、类型、构造器、方法、字段、注解、修饰符、Javadoc、源码范围和 Spring 角色。
- 索引以 schema version 与固定 revision 标识；结果、符号 ID 与依赖边排序确定，损坏文件形成 `IndexProblem`。
- 依赖图覆盖 extends、implements、imports、field type、constructor/field injection、method reference 与 method call；未解析目标保留低置信边。
- 图查询支持 upstream/downstream、边类型、最大深度、结果上限与限定深度路径查找。
- `context-engine` 已实现 Exact、Lucene BM25、Optional Vector、Weighted Hybrid、Recall@K 和稳定证据引用。
- Context Builder 按符号边界构建上下文，执行图扩展、去重、每文件多样性限制、超限摘要和严格 Token packing，并记录淘汰原因。
- Banking fixture 的 golden snapshot 为 34 个符号；Controller → Service → Repository 调用路径为 2 跳。
