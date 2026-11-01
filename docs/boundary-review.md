# DACP and XStream project boundaries

XStream owns the data model, Arrow streams, Job language, execution policies, inference, local execution, physical planning, execution units and materialization. DACP owns remote services, authorization, S3 and Union membership/namespace management.

`ExecutionTargetProvider` and `RemoteItemProvider` are independent extension points. The engine uses protocol-independent execution and data-access contracts; the optional DACP connector implements remote data reads and computation. Union translates data locations and member capacities into `GraphPlacement` inputs. Designer only authors Jobs; it neither plans nor displays execution.

Standalone XStream builds do not read DACP sources or require a running DACP service. DACP consumes versioned XStream artifacts. Websites build independently from documentation snapshots and link cross-project topics to their authoritative documentation.

DACP's `proto/dacp.proto` is the authoritative metadata specification. `scripts/export-xstream-protocol.py` exports or checks connector snapshots. This is an explicit integration tool, not a XStream build dependency.

Engine code uses `xstream.engine`, execution contracts use `xstream.execution`, and data plugins use their own `xstream.data.*` packages. Service registration files and host callers use these package names. Some DACP integration tests explicitly use XStream examples through `XSTREAM_SOURCE_ROOT`; production startup does not read that checkout.

See [execution targets and planning](execution-targets.md). Validate with `python3 scripts/check-independence.py`, `python3 scripts/check-doc-translations.py`, and engine/connector `sbt test`.
