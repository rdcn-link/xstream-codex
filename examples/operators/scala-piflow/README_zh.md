# Scala 流处理算子

[English](README.md)

六个主要示例使用 Scala 实现 JVM 算子 SDK：`piflow_csv_parser`、`piflow_csv_save`、`piflow_filter`、`piflow_execute_sql_stop`、`piflow_merge` 和 `piflow_join`，版本均为 `2.0`。每个算子实现处理、结构推导及有界输出样例。参数、端口和完整 Job 见[算子目录](../../../docs/operator-catalog_zh.md)。

```bash
cd examples/operators/scala-piflow
sbt test
```

CSV 解析和输出使用 Commons CSV。Filter 和 SQL 使用 SQLite JDBC，采用内存数据库、内存临时表、只读查询模式与指令预算。SQL 表达式的输出类型在处理前保持未知。Merge 按字段名推导联合结构；Join 推导连接键、重名后缀和外连接可空性。Join 的两个标量输入和输出分别限制为估算 128 MiB；SQL 的输入、输出也采用该限制。CSV 和 Merge 分批处理，但 SQL、Join 并不是恒定内存算法。Merge 前需解码字典列；SQL、Join 和 CSV 输出接收非字典的标量列。

`pom.xml` 声明固定版本的第三方运行依赖。仓库解析并锁定这些库，生成共享依赖环境；算子 JAR 只包含业务类。Scala、Arrow 和 SDK 由 `jvm-arrow@2.1` 提供。由于执行临时目录不可执行，该 Runtime 也预装版本匹配的 SQLite 原生库。执行 Job 时不安装软件包。运行时及依赖环境一致且满足合并条件的算子可以合并到同一进程，包括来自不同代码包的算子。

按照 [build-runtimes.sh](build-runtimes.sh) 构建并登记执行和编译 Runtime。源码发布使用仓库的正常源码构建 API，之后由管理员审核：

```bash
python3 publish.py --url http://localhost:8820 --token-file /secure/publisher.token csv_parser
```

逐个执行即可发布其它算子，也可省略名称提交全部六个。`--approve-password-file` 会额外等待每个构建并审核后再处理下一个，供维护已审查示例部署的管理员使用。凭据不会包含在源码包中。发布源码包含 Apache-2.0 许可证及上游署名。
