# Repository boundaries and artifact builds

[中文](repositories_zh.md)

XStream owns data contracts and implementations, the streaming engine and its designer. XStream also owns operator SDK/contracts, inference, executor and source-analysis plugins, and runtime build tools. The [operator repository service](https://github.com/rdcn-link/xstream-operator-repository) is an independent project consuming versioned XStream contract and source-analysis artifacts. DACP owns service transport, authorization, federation and clients. This is a source-repository split, not removal of all DACP library dependencies from the engine.

The artifact dependency graph is acyclic:

1. XStream data artifacts.
2. DACP public integration artifacts, consuming data artifacts.
3. XStream operator SDK, inference and executors, then the engine; these consume data and DACP integration artifacts.
4. DACP servers and XStream designer, consuming the engine and public libraries.

Run `scripts/bootstrap-local.sh /path/to/dacp-codex` from this checkout to publish development dependencies in that order. The script deliberately requires an explicit source path. Published libraries use organization `io.github.rdcn-link`, generally version `0.1.0-SNAPSHOT`; `dacp-client-scala` uses `0.1.0`. Ordinary builds resolve Maven/Ivy artifacts, without sibling-checkout detection, symlinks or source fallbacks. Configure a shared artifact repository when distributing builds to other machines; current unreleased dependencies must first be published there or bootstrapped locally.

Run `sbt test` from this project root for data, engine and designer tests. Parser/stream contract tests live in `xstream-data/xstream-data-tests`; browser checks live in `scripts/test-designer-*.py` and require Playwright plus Chromium. DACP service and integration tests remain in the DACP checkout. Run `python3 scripts/check-doc-translations.py` in each repository.

Third-party format dependencies stay in their data plugin modules. Existing ServiceLoader registration files move with their implementations. Contributor registration and configuration examples remain in the data and designer guides; adding a plugin does not require a central registry edit.
