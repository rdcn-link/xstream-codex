# 本地源码算子

[English](README.md)

无需仓库的 Python → Scala 流水线。从 XStream 项目根目录执行：

```bash
cd xstream-engine && sbt stage
cd ..
xstream-engine/target/universal/stage/bin/xstream-runner \
  examples/operators/local/engine.yaml examples/operators/local/job.yaml
```

需要 Docker 访问权限，已安装 `xstream/operator-python:1.0`、`xstream/operator-jvm:1.1` 镜像、sbt/JDK 17，以及匹配的已发布到本机的 XStream JVM SDK，见[初始化说明](../../../README_zh.md)。Scala 示例将 SDK 声明为 provided 依赖，生成薄 JAR。构建诊断写入 stderr，stdout 应输出：

```text
TEMPERATURE: 21.5 C
STREAMING WEATHER OBSERVATIONS
```

`operator.yaml` 是可发布声明，`local.yaml` 是本机构建及运行配置。Python 源码 ZIP 和 Scala JAR 均有构建缓存。两个模板都提供结构推导及有界样例实现，处理过程以流传递，不物化中间数据。

在 `operator.yaml` 中选择真实仓库运行环境后，可以提交任一示例：

```bash
xstream-operator submit examples/operators/local/python \
  --url https://operators.example.org --token-file /protected/submitter.token
```

仓库构建并审核源码，本机配置和构建产物不会上传。库内名称引用、Maven 构建、取消、插件扩展及发布要求见[本地算子库](../../../docs/local-operators_zh.md)。
