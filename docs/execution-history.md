# Execution history API

Hosts use `ExecutionHistory` for Job and step state. Records store metadata, not result streams; see [execution policies](materialization.md) for output retention.

The shared implementation is `xstream.execution.ExecutionHistory`. A record is written before execution, then updated on state transitions, progress observations and completion. A single JSON document contains both its latest snapshot and ordered state events, avoiding separate commits. A temporary file is synchronized to disk, atomically replaces the record, and its parent directory is synchronized. Write failures fail the associated request rather than silently disabling recording. A directory writer lock prevents two instances from recovering each other's active executions; the CLI opens history read-only.

## Storage configuration

```yaml
execution_store:
  provider: file
  settings:
    directory: .xstream-executions
```

`provider` selects a ServiceLoader-registered `ExecutionStoreProvider`; the plugin validates its private `settings`. The built-in file implementation is in `plugins/xstream-plugin-execution-file`. Use `new ExecutionHistory(store, ownsStore = false)` when the host retains store ownership. A directory has one writer; hosts must allocate separate directories to different processes. Record retention is independent of materialized-output expiration.

See the [execution API](../xstream-execution-api/README.md) and [plugin registration](plugins.md).
