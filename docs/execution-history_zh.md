# 执行历史 API

宿主通过 `ExecutionHistory` 记录 Job 和步骤的状态，记录保存元数据，不保存结果流；结果物化见[执行策略](materialization_zh.md)。

共享实现为 `xstream.execution.ExecutionHistory`。每次执行开始前写入记录，在状态变化、进度观测与结束时更新。单个 JSON 文档同时包含最新快照和有序状态事件，避免快照与事件分开提交。写入临时文件并同步到磁盘，然后原子替换记录，同步父目录；写入失败会使对应请求失败，不静默禁用记录。目录写锁防止两个实例同时回收对方未完成的执行，CLI 使用只读模式。

## 存储配置

```yaml
execution_store:
  provider: file
  settings:
    directory: .xstream-executions
```

`provider` 选择通过 ServiceLoader 注册的 `ExecutionStoreProvider`，插件校验其私有 `settings`。内置文件实现位于 `plugins/xstream-plugin-execution-file`。`new ExecutionHistory(store, ownsStore = false)` 可保留宿主对存储的所有权。一个目录只能有一个写入者。宿主必须为不同进程分配独立目录。记录保留与结果物化的过期时间分开管理。

参见[执行 API](../xstream-execution-api/README_zh.md)和[插件注册](plugins_zh.md)。
