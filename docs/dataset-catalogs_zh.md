# 动态数据集目录

[English](dataset-catalogs.md) · [数据模型](data-model_zh.md) · [插件体系](plugins_zh.md)

`DataCatalog` 决定执行上下文暴露哪些 Dataset，`DatasetProvider` 决定一个 Dataset 如何访问内容。数据集名称来自数据库、目录注册表、业务服务或用户程序时，使用目录插件；单个 Dataset 内的动态 Item 列表由 Dataset provider 负责。

## 配置与发现

内置 `static` 目录通过 `datasets` 构造数据集。自定义目录使用 `data_catalog`，不能与非空的 `datasets` 配置并存：

```yaml
plugin_directory: /opt/xstream/plugins
data_catalog:
  provider: folder-catalog
  source:
    root: /srv/catalog
```

该片段用于 Runner。在 Designer 中，将 `data_catalog` 放入 `local_catalog` 下，`plugin_directory` 仍是宿主配置。插件拥有并验证 `source`，核心不解释数据库连接或业务规则。提供者 ID 缺失、重复或者配置非法时，初始化失败。

实现具有公开无参构造方法的类，并注册到：

```text
META-INF/services/xstream.data.DataCatalogProvider
```

下面示例对应的注册文件内容为：

```text
example.FolderCatalogProvider
```

编译时以 `Provided` 方式依赖与宿主版本匹配的 `io.github.rdcn-link %% xstream-data-core`，Scala 版本也需要匹配。将插件 JAR 及其私有依赖安装到 `plugin_directory` 或宿主 classpath，不重复放入宿主 API JAR。目录后端还需要 `xstream-data-files`。重启后发现插件，无需修改中央注册表或客户端。

## 完整提供者示例

每个直接物理子目录成为一个 Dataset。列举时发现名称，新增目录无需重启进程。该示例不会递归地把所有文件夹都变成 Dataset。

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

数据库注册表也可实现同一接口：使用参数绑定查询、明确的超时，正确关闭游标与连接。连接池、事务、刷新和缓存策略由插件实现。发现失败必须抛出异常，不能伪装为空目录。物理路径、数据库细节和凭据不能进入公共元数据及错误信息。

## 公共契约与描述信息

接口位于 `xstream-data-core`：

```scala
trait DataCatalog extends AutoCloseable {
  def list(): Seq[DatasetInfo]
  def directory(check: () => Unit): DatasetDirectory = DatasetDirectory(list(check))
  def open(name: String): PublishedDataset
  // list/open 重载接受取消检查回调。
}
trait DataCatalogProvider extends Plugin {
  def id: String
  def create(settings: Map[String, Object], context: CatalogContext): DataCatalog
}
```

`DatasetInfo` 包含可寻址名称 `name` 和公共 JSON 对象字符串 `description`，没有单独的 Dataset ID。JSON 必须包含非空字符串 `title` 和字符串 `description`，允许自定义字段。`DatasetInfo(name)` 自动使用名称作为标题，描述为空。显式非法 JSON 会被拒绝，包括重复键。描述最多 32768 个字符，保留原始文本。

`directory` 默认包装 `list`；聚合目录可返回 `DatasetDirectory(entries, complete, unavailableSources)` 标记部分结果。来源不可达不能被解释为数据集已删除。`CatalogContext.createDataset` 用于组合已安装的 [Dataset 提供者](dataset-providers_zh.md)；原生后端也可以直接返回 `PublishedDataset`。

## 生命周期与契约测试

目录必须支持并发请求。每次 `open` 返回调用者持有的 `PublishedDataset` 租约；租约上的 `open(check)` 返回 `DatasetAccess`。先关闭访问句柄，再关闭租约。注册表移除数据集后，已获得的租约应仍可使用，但受实际后端可用性限制，不保证内容快照。每份租约需要独立，不能将同一个可关闭实例交给并发调用者。

宿主停机时关闭目录，仅释放目录拥有的资源；应用注入的连接池仍由应用管理。在发现及查询循环中检查取消。一个 JVM 通过 `DataCatalogRuntime` 共享绑定的目录，参见[引擎嵌入](xstream_zh.md)。

测试应覆盖注册、重复 ID、非法参数、新增与移除、发现失败、名称合法唯一、描述必填字段、部分目录、并发租约、取消以及资源归属。共享契约测试运行 `(cd xstream-data && sbt test)`；外部提供者还应测试自身后端及生命周期。数据鉴权和网络目录发现属于嵌入 XStream 的服务职责，不属于提供者契约。
