# Run your first Job

Start here to read a local JSONL file as a record stream. No data server or operator download is needed for this example.

## 1. Build the runner

Use JDK 17 and sbt 1.10.x. Run from the repository root:

```bash
git clone https://github.com/rdcn-link/xstream-codex.git
cd xstream-codex
scripts/bootstrap-local.sh
(cd xstream-engine && sbt stage)
```

## 2. Check the catalog

[engine.yaml](../examples/xstream/engine.yaml) maps Dataset `weather` to `examples/data`. The supplied file is `examples/data/weather-observations.jsonl`. Relative filesystem paths resolve against the process working directory.

```yaml
datasets:
  weather:
    provider: directory
    source:
      root: examples/data
```

## 3. Read the Item

[read.yaml](../examples/xstream/read.yaml) contains:

```yaml
name: Local catalog read
steps:
  - id: read
    operator: read_item@1.0
    inputs: {}
    params:
      uri: context:/weather/weather-observations.jsonl
      view: records
outputs:
  result: read
```

`context:` selects the current catalog, `weather` selects its Dataset, and the remaining path selects an Item. `outputs.result` selects the step output printed by the runner.

```bash
xstream-engine/target/universal/stage/bin/xstream-runner \
  examples/xstream/engine.yaml examples/xstream/read.yaml
```

The terminal prints JSONL records as they arrive. A missing Item usually means the command ran outside the repository root or the catalog root was changed. A bytes view prints original bytes instead.

## Continue by task

- [Design visually](../xstream-designer/README.md): load this Job, inspect output schemas and export YAML.
- [Write a Job](jobs.md): connect steps and supply variables.
- [Manage data](data-model.md): Datasets, folders, archives and views; [read examples](data-view-examples.md).
- [Develop an operator](operator-authoring.md): templates, parameters, inference and local testing.
- [Embed the engine](xstream.md): Scala API, lifecycle and host configuration.
