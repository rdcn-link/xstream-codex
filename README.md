# XStream

<img src="assets/xstream-logo.svg" alt="XStream" width="96">

[中文](README_zh.md)

XStream is a Scala toolkit for stream-oriented computation: typed data access, an embeddable Job engine and a browser-based visual designer. Steps consume and produce streams; intermediate data need not be saved as files. Explicit execution policies enable materialization and replay when required.

## Components

| Module | Purpose |
|---|---|
| [xstream-data](xstream-data/README.md) | DataCatalog → Dataset → Item, typed DataView/DataStream contracts, plugin discovery, file and scientific-format implementations and network codecs |
| [xstream-operator-sdk](xstream-operator-sdk/README.md) | JVM/Python operator templates and metadata contracts |
| [xstream-operator-inference](xstream-operator-inference/README.md) | Reusable on-demand structure and sample workers |
| [xstream-operator-repository](https://github.com/rdcn-link/xstream-operator-repository) | Source submissions, isolated builds, review, public operator and runtime downloads |
| [xstream-engine](xstream-engine/README.md) | Embedded execution, cancellation, execution policies, automatic remote compute pushdown and the `xstream-runner` launcher |
| [xstream-designer](xstream-designer/README.md) | Visual Job editing, catalogs, variables, schema and sample previews, chat proposals and YAML import/export |

`context:/dataset/item` resolves through the catalog bound to the current JVM. Explicit `dacp://host:port/dataset/item` URIs use configured remote connections. Scientific arrays, images and sequences keep their structures rather than being flattened into records. Format and backend implementations register through plugin contracts.

## Build

Use JDK 17 and sbt 1.10.x. These modules were extracted from [DACP](https://github.com/rdcn-link/dacp-codex). DACP consumes XStream as versioned artifacts; XStream engine and designer consume DACP transport and execution API integration libraries as versioned artifacts. Builds never reach into another source checkout.

There are no public releases yet. To populate a development machine's local artifact repository, clone both repositories and run:

```bash
git clone git@github.com:rdcn-link/dacp-codex.git
git clone git@github.com:rdcn-link/xstream-codex.git
cd xstream-codex
scripts/bootstrap-local.sh ../dacp-codex
sbt test
(cd xstream-engine && sbt stage)
(cd xstream-designer && sbt stage)
```

The bootstrap publishes data artifacts, DACP public integration libraries, XStream operator SDK/inference/executors, then the engine in dependency order. The explicit DACP path is needed only for bootstrapping unpublished development artifacts. Ordinary builds resolve artifacts through sbt repositories. A clean machine cannot build the engine until these integration artifacts are available. See [repository boundaries](docs/repositories.md).

## Run

From the XStream repository root:

```bash
xstream-engine/target/universal/stage/bin/xstream-runner \
  examples/xstream/engine.yaml examples/xstream/read.yaml
xstream-designer/target/universal/stage/bin/xstream-designer \
  xstream-designer/example.yaml
```

Edit configuration paths, service addresses and credentials first. The runner prints its selected output stream to stdout; the designer listens on `http://127.0.0.1:8840/` by default. Operators use approved public repositories or their verified local cache. The built-in `read_item` needs no package download.

## Documentation

- [Write an operator](docs/operator-authoring.md)
- [Submit source and review](docs/operator-submission.md)
- [Engine and embedding](docs/xstream.md)
- [Typed streams and scientific views](docs/data-streams.md)
- [Scientific authoring and previews](docs/designer-scientific.md)
- [Plugin contracts and registration](xstream-data/README.md)
- [Job YAML](docs/jobs.md)

## Logo and origin

The original vector [logo](assets/xstream-logo.svg) and [symbol](assets/xstream-mark.svg) depict crossing turquoise/blue and violet/amber streams forming an X. The designer uses this symbol as its favicon. See [ORIGIN](ORIGIN) for source provenance. Public implementation packages retain their existing namespaces; this extraction does not rename API packages.

Documentation ownership and website publishing: [maintenance policy](docs/documentation.md).
