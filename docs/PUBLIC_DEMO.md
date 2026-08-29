# 公开演示证据

## 媒体文件

- [GIF（800 × 450，12 FPS）](assets/demo/legacy-pilot-task-005-real-model.gif)
- [MP4（1280 × 720，30 FPS）](assets/demo/legacy-pilot-task-005-real-model.mp4)
- [封面](assets/demo/legacy-pilot-task-005-poster.png)

演示时长约 41 秒。它是已完成真实模型运行的证据回放，不冒充实时录屏；画面中的结果均可由保存的基线记录和隔离任务工作区复核。

## 演示对象

- 数据集任务：`task-005`，每日转账限额
- 模型：OpenAI GPT-5.4，reasoning effort `high`
- Prompt：`baseline-prompt-v1`
- 策略：`codex-agent-v1`
- 模型尝试次数：2（一次公开断言反馈重试）
- API 等价估算成本：`$0.432923`
- 基线记录：[2026-08-29 GPT-5.4 real-model baseline](../evals/baselines/2026-08-29-gpt-5.4-codex-agent-v1/README.md)

## 不依赖 reference overlay

录制前重新检查了保留的 `task-005` 隔离工作区：

- 工作区内 reference 文件数量为 0；
- fixture 的 `pom.xml` 未变化；
- 测试源文件未变化；
- 与基线相比只有 3 个生产文件发生变化：新增 `DailyTransferPolicy.java`、新增 `TransferLimitException.java`、修改 `TransferService.java`；
- 公开确定性断言 5/5：普通用户 50,000、VIP 200,000、并发转账同步、UTC 日期边界及目标文件存在；
- 使用 JDK 21 重新执行独立 Maven 测试，结果通过。

模型从未获得 reference solution。唯一的重试输入只包含首次失败的公开确定性断言；完整策略与首次/最终分数均保留在基线记录中。

## 运行环境

真实模型基线运行于 macOS 15.5 / arm64、OpenJDK 21.0.12.1、Maven Wrapper 3.9.16、Codex CLI 0.144.1。媒体在同一台机器上依据上述保存证据生成；视频无音轨，不包含凭据或本地绝对路径。
