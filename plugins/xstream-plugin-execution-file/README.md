# XStream file execution storage

[中文](README_zh.md)

ServiceLoader implementation of execution history, using provider ID `file`. Configure its private directory on the engine host; Jobs do not select history storage paths.

The plugin also registers `CheckpointStoreProvider` with ID `file`. Configure `checkpoint_store` to retain explicitly selected outputs within one Job execution. Arrow IPC File envelopes preserve original frames and integrity checks; quotas bound actual writes. See [checkpoint configuration and contracts](../../docs/execution-policy.md#explicit-output-checkpoints).
