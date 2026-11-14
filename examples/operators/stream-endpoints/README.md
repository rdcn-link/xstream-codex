# Repository stream endpoint operators

[中文](README_zh.md)

`item_source@1.0` and `client_sink@1.0` are versioned repository operators. Jobs resolve their approved manifests and checksum-verified programs from the configured repository or verified cache. The Designer lists their repository metadata, ICON, role, implementation type and trust assessment. There is no implicit operator registration or fallback when a package is unavailable.

| Operator | Role | Program operation |
|---|---|---|
| `item_source@1.0` | Source | `catalog.read`: open the selected View of a catalog Item |
| `client_sink@1.0` | Sink | `client.receive`: bind named streams to the Job caller |

These SDK transport programs use the `stream` executor. A program is a JSON document with `api_version: 1` and an `operation`. It selects a bounded host transport service; it cannot execute package code in the host JVM, choose host commands or bypass access control. Catalog authorization, namespace validation, cancellation and stream cleanup remain host responsibilities. `context:` addresses the host's bound DataCatalog. The client receiver supports named result bindings and does not publish a dataset. Both endpoint programs are graph boundaries and do not create an operator container.

`StreamExecutor` is registered through `META-INF/services/xstream.execution.OperatorExecutor`; the repository's `StreamBuildRecipe` uses `META-INF/services/xstream.operator.SourceBuildRecipe`. Build language `stream` copies `endpoint.json` inside the isolated builder and validates its fields, size, version and operation before publication. Install `xstream-plugin-operator-executors` on engine/Designer hosts and `xstream-plugin-source-analysis` on the repository.

Submit and optionally approve the two programs:

```bash
python3 publish.py --url http://127.0.0.1:8820 \
  --token-file /secure/submitter.token \
  --approve-password-file /secure/admin-password
```

The administrator option registers `stream-endpoints@1.0` when absent, using an existing registered Scala build image to run the isolated copy. Omit it when a repository administrator will register the runtime and review submissions separately. No credentials are packaged. See [operator development](../../../docs/operator-authoring.md), [Job YAML](../../../docs/jobs.md) and [DataView examples](../../../docs/data-view-examples.md).
