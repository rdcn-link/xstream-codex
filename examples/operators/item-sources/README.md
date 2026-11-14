# Item collection operators

[中文](README_zh.md)

Scala SDK implementations of `folder_items_source`, `csv_items_source`, and `item_inventory`, `item_byte_count`. Sources emit Item references, not file contents. They use the execution host's private Item access service and require a runtime containing the matching SDK, execution API and data-core versions.

| Operator | Role | Input | Output |
|---|---|---|---|
| folder_items_source | source | URI parameter, recursive flag | items collection |
| csv_items_source | source | CSV URI parameter, uri_column | items collection |
| item_inventory | transform | items | text bytes containing URI/kind/format |
| item_byte_count | transform | items | URI and byte count, reading one member at a time |


## Build and test

```bash
cd examples/operators/item-sources
sbt test package
```

The JVM SDK must be available in your configured artifact repository. `*.json` files illustrate execution and port declarations; repository submission additionally requires runtime pins, source provenance, icon, parameter help/examples and complete usage documentation. Output ports are inferred by operator methods, never declared at manifest top level. See [operator submission](../../../docs/operator-submission.md).

## Use locally

Configure an engine `inline_runtimes.jvm_tools` using a locally installed matching JVM image. Add the following definition to a local Job and reference it as `operator: {inline: folder_items}`:

```yaml
operators:
  folder_items:
    type: sdk
    role: source
    runtime: jvm_tools
    program:
      path: ./target/scala-2.13/xstream-item-sources_2.13-1.0.jar
      entrypoint: xstream.examples.items.FolderItemsSource
    inputs: {}
```

The step parameters are `{uri: 'context:/science/labels', recursive: true}`. For CSV use `CsvItemsSource` and `{uri: 'context:/science/manifest.csv', uri_column: uri}`. CSV manifests preserve row order, duplicates and other columns as metadata; member metadata can be unknown until opening. Relative paths refer to the manifest's logical parent. Raw filesystem paths and URI credentials are not accepted.

Collection consumers declare `representations: [items]` and use `ctx.input("source").foreachItem`. They may open a member with `ctx.openItem(item, "bytes") { stream => ... }`; consume incrementally and release it inside the callback. See [Item reference streams](../../../docs/items-stream.md). Remote Jobs require approved repository references; inline definitions are local only.

For a single Item, use the `view` parameter of `item_source` to read its contents directly. Collection Sources emit references for explicit access by collection consumers.

## Publish

Register runtime `jvm-items@1.0` with the matching SDK, then run:

```bash
python3 publish.py --url http://localhost:8820 \
  --token-file /path/to/submitter.token \
  --approve-password-file /path/to/admin-password
```
Omit the approval password option to await administrator review. `item_byte_count` skips directories without bytes, preserves duplicates and ordering, and fails the Job if a member cannot be read.

## End-to-end verification

After building the matching runtime image and runner, run:

```bash
python3 scripts/test-items-stream.py --image xstream/operator-jvm-items:1.0
```
Run from the project root with Docker access and a published `client_sink@1.0`. The test uses isolated containers to verify ZIP folders, duplicate CSV entries, lazy reads, missing-member failure and traversal rejection.
