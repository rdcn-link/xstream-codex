# Dynamic Dataset catalogs

[中文](dataset-catalogs_zh.md) · [Data model](data-model.md) · [Plugin architecture](plugins.md)

`DataCatalog` determines which Datasets the execution context exposes. `DatasetProvider` determines how one Dataset accesses its contents. Use a catalog plugin when Dataset names come from a database, directory registry, business service or application. Dynamic Items within one Dataset belong to its Dataset provider.

## Configuration and discovery

The built-in `static` catalog constructs Datasets from `datasets`. A custom catalog uses `data_catalog`; it cannot coexist with nonempty `datasets` configuration:

```yaml
plugin_directory: /opt/xstream/plugins
data_catalog:
  provider: folder-catalog
  source:
    root: /srv/catalog
```

This fragment configures the Runner. Place `data_catalog` under `local_catalog` in the Designer; `plugin_directory` remains a host setting. The plugin owns and validates `source`. Core does not interpret database connections or business rules. Missing/duplicate provider IDs and invalid configuration fail initialization.

Implement a public no-argument class and register it in:

```text
META-INF/services/xstream.data.DataCatalogProvider
```

For the example below, the registration file contains:

```text
example.FolderCatalogProvider
```

Compile against `io.github.rdcn-link %% xstream-data-core` using the host's matching version as `Provided`, and the matching Scala version. Install the plugin JAR and its private dependencies in `plugin_directory` or the host classpath, without duplicating host API JARs. A directory backend additionally needs `xstream-data-files`. Restart to discover the plugin; no central registry edits or client changes are required.

## A complete provider example

Each immediate physical subdirectory becomes one Dataset. The catalog discovers names when listed; newly added directories do not require a process restart. The example does not recursively turn every folder into a Dataset.

```scala
package example

import xstream.data._
import java.nio.file.{Files, LinkOption, Path}
import scala.jdk.CollectionConverters._

class FolderCatalogProvider extends DataCatalogProvider {
  val id = "folder-catalog"
  def create(settings: Map[String, Object], context: CatalogContext): DataCatalog = {
    require(settings.keySet == Set("root"), "folder-catalog requires only source.root")
    require(settings("root").isInstanceOf[String], "source.root must be text")
    val root = Path.of(settings("root").toString).toRealPath()
    require(Files.isDirectory(root), "catalog root must be a directory")
    new DataCatalog {
      def list(): Seq[DatasetInfo] = list(() => ())
      override def list(check: () => Unit): Seq[DatasetInfo] = {
        check()
        val children = Files.list(root)
        try children.iterator().asScala.filter { p =>
          check(); Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)
        }.map { p =>
          val name = p.getFileName.toString
          DatasetNames.validate(name)
          DatasetInfo(name)
        }.toVector.sortBy(_.name)
        finally children.close()
      }
      def open(name: String): PublishedDataset = {
        DatasetNames.validate(name)
        val path = root.resolve(name)
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
          throw new java.io.FileNotFoundException("dataset unavailable")
        context.createDataset(DatasetDefinition("directory",
          source = Map[String, Object]("root" -> path.toString)))
      }
    }
  }
}
```

For a database registry, implement the same interface using bound queries, explicit timeouts and properly closed cursors/connections. Pooling, transactions, refresh and cache policies belong to the plugin. Discovery failures must throw, not masquerade as an empty directory. Keep physical paths, database details and credentials out of public metadata and errors.

## Public contracts and descriptions

The interfaces reside in `xstream-data-core`:

```scala
trait DataCatalog extends AutoCloseable {
  def list(): Seq[DatasetInfo]
  def directory(check: () => Unit): DatasetDirectory = DatasetDirectory(list(check))
  def open(name: String): PublishedDataset
  // list/open overloads accept a cancellation check.
}
trait DataCatalogProvider extends Plugin {
  def id: String
  def create(settings: Map[String, Object], context: CatalogContext): DataCatalog
}
```

`DatasetInfo` contains the addressable `name` and public JSON object string `description`, without a separate Dataset ID. The JSON must contain a nonempty string `title` and a string `description`; custom fields are allowed. `DatasetInfo(name)` supplies a name-based title and empty description. Explicit invalid JSON is rejected, including duplicate keys. Descriptions are limited to 32768 characters and preserve their original text.

`directory` normally wraps `list`; an aggregating catalog may return `DatasetDirectory(entries, complete, unavailableSources)` to identify partial results. Missing sources must not imply that Datasets were deleted. `CatalogContext.createDataset` composes installed [Dataset providers](dataset-providers.md). Native backends may return a `PublishedDataset` directly.

## Lifecycle and contract tests

Catalogs must support concurrent requests. Each `open` returns a caller-owned `PublishedDataset` lease; `open(check)` on that lease returns a `DatasetAccess`. Close access first, then the lease. Previously acquired leases remain usable when a registry removes a Dataset, subject to actual backend availability. This does not guarantee content snapshots. Each lease must be independent: never hand the same closable instance to concurrent callers.

The host closes its catalog at shutdown. Release only catalog-owned resources; injected application connection pools remain application-owned. Check cancellation inside discovery/query loops. One JVM shares its bound catalog through `DataCatalogRuntime`; see [engine embedding](xstream.md).

Test registration, duplicate IDs, invalid settings, additions/removals, discovery failure, unique valid names, required description fields, partial directories, concurrent leases, cancellation and resource ownership. Run `(cd xstream-data && sbt test)` for shared contract checks; contributed providers must also test their own backend and lifecycle. Data authorization and transport discovery are the responsibility of a service embedding XStream, not of this provider contract.
