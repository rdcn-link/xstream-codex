# XStream file execution storage

[English](README.md)

执行历史存储的 ServiceLoader 实现，provider ID 为 `file`。在引擎宿主中配置私有目录，Job 不指定历史存储路径。

本插件同时注册 ID 为 `file` 的 `CheckpointStoreProvider`。配置 `checkpoint_store` 可在同一次 Job 执行中保留显式选择的输出。Arrow IPC File 封套保留原始帧并进行完整性校验，配额约束实际写入。见[检查点配置与契约](../../docs/execution-policy_zh.md#显式输出检查点)。
