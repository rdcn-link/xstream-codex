# JDBC 数据集：PostgreSQL、类型与流语义

[English](jdbc-datasets.md) · [Dataset 配置](dataset-providers_zh.md)

使用 `sqldb` provider 将数据库表映射为 Dataset 中的 Item。此配置可用于 Runner、嵌入式应用或其他加载 XStream 数据目录的宿主。Designer 使用时将目录配置放在 `local_catalog` 中。

## PostgreSQL

已捆绑 PostgreSQL 和 H2 JDBC 驱动。替换 `sources` 下的私有 `warehouse` 数据源，并更新其显式公开映射：

```yaml
datasets:
  sales:
    provider: sqldb
    source:
      connection: warehouse
    tables:
      /reports/orders: {table: orders}
      /clients: {table: customers}
sources:
  warehouse:
    provider: jdbc
    settings:
      url: jdbc:postgresql://db-host:5432/sales?sslmode=verify-full
      user_env: XSTREAM_DB_USER
      password_env: XSTREAM_DB_PASSWORD
      schema: public
      tables: [orders, customers]
      fetch_size: 256
      query_timeout_seconds: 60
```

启动宿主前设置上述环境变量，并为 PostgreSQL 驱动配置可信的服务器 CA，以使用 `verify-full`。数据库账号需要对公开表具有 SELECT 权限，并对 schema 具有 USAGE 权限。凭据保留在宿主上，不会发送给数据使用者。也支持直接填写 `user` / `password`；环境变量字段优先，引用的变量缺失时配置加载失败。每个私有 SQL 数据源描述一个数据库 schema。如需组合多个数据源，可以使用 `provider: mixed` 并配置 Item 级 `sql` 绑定。

使用 `provider: directory` 和 `source.root` 自动发现文件，或显式配置 `sqldb` / `mixed` 的公开 Item 映射及私有 `sources`。未映射的私有数据源和表不会公开。文件映射使用 `{file: /private/path.csv}`；SQL 映射使用 `{sql: warehouse, table: private_table}`。客户端可见的名称和路径是独立选择的别名。每个私有 SQL 数据源支持：

| 字段 | 含义 |
|---|---|
| `url` | JDBC 连接 URL |
| `user`, `password` | 可选的宿主端凭据 |
| `user_env`, `password_env` | 存储凭据的环境变量名称 |
| `schema` | 精确的数据库 schema；默认使用驱动当前 schema |
| `tables` | 可选的精确表名白名单，区分大小写；省略则允许映射 schema 中任意表，`[]` 不允许任何表；所有表仍需显式公开别名 |
| `fetch_size` | JDBC 获取窗口，默认为 `batch_size` |
| `query_timeout_seconds` | 语句超时，默认为 60 秒，必须为正数 |

SQL Item 使用 `kind: data`、`format: sql`，提供 `records` 与 `bytes` 视图。表映射必须匹配 JDBC 元数据，并作为标识符加引号。通过[记录扫描](records-scans_zh.md)执行支持的只读筛选、投影与 limit；客户端不提供底层表名或任意数据库 SQL。私有数据源设置不属于 Item 元数据。

SQL 数据项在正常列举和打开时，`recordCount` 保持未知。显式调用 `access.count("/reports/orders")` 会执行 `COUNT(*)`，返回精确计数，空表返回零。由于 JDBC 没有可移植的内容修改时间或序列化字节大小，`lastModifiedTimeMs` 和 `totalSizeBytes` 保持缺失。计数可能扫描大表，并遵守 `query_timeout_seconds`；元数据和后续读取不共享同一快照。所有文件夹属性均保持未知，包括空文件夹，且不会访问子 Item。

## 类型与流语义

支持的 SQL 类型映射到 Arrow 如下：

| SQL | Arrow |
|---|---|
| 整数类型 | 有符号 int64 |
| REAL / FLOAT / DOUBLE | float64 |
| BOOLEAN / BIT | bool |
| 字符类型 | utf8 |
| 二进制类型 | binary |
| DECIMAL / NUMERIC，声明精度为 1–38，小数位数为 0–精度 | decimal128 |
| DATE | date32 |
| TIME | time64，微秒 |
| TIMESTAMP | timestamp，微秒，无时区 |
| TIMESTAMP WITH TIME ZONE | timestamp，微秒，UTC |

保留空值和 JDBC 列的可空性，时间精度低于微秒的部分会截断。目前明确拒绝数组、LOB、任意厂商特有类型、无约束 decimal 和带时区 time，不会静默转换类型。其他 JDBC 引擎需要通过插件依赖或宿主类路径提供驱动，并验证 schema 元数据、类型映射和游标行为。

`records` 直接流式提供表记录。表没有原始文件，因此 `bytes` 提供这些记录的 **Arrow IPC Stream 序列化**，可交给 Arrow IPC reader 增量读取；它不是 CSV、SQL 文本或数据库备份。需要表转换时选择接受 records 的算子。Scala 读取接口见 [DataView 示例](data-view-examples_zh.md)。

每个请求独占 JDBC 连接、语句和结果集。读取请求使用只读事务和只向前游标。PostgreSQL 使用 autocommit=false 和正数 fetch size，避免缓冲整个结果（见[驱动文档](https://jdbc.postgresql.org/documentation/query/)）。Arrow 分配受批次大小约束，JDBC 缓冲行为取决于驱动，单个大值仍会占用内存。SQL 结果没有隐含行顺序。元数据发现与流读取使用不同连接，因此元数据和数据不共享同一快照。重新打开的流从头读取，不支持重试、检查点或去重。在获取数据或批次之间检查取消；正在执行的数据库操作可能持续到驱动或查询超时。

## 在 Job 中读取

配置数据库凭据环境变量后，用该目录运行以下 Job：

```yaml
name: Read database orders
steps:
  - id: read
    operator: read_item@1.0
    inputs: {}
    params:
      uri: context:/sales/reports/orders
      view: records
outputs:
  result: read
```

示例路径 `/reports/orders` 来自上面的显式映射。参数、变量与执行方式见 [Job 指南](jobs_zh.md)及[快速入门](quickstart_zh.md)。
