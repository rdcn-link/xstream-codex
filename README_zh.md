# XStream

<img src="assets/xstream-logo.svg" alt="XStream" width="96">

[English](README.md)

XStream 是 Scala 编写的流式计算工具集，包含类型化数据访问、可嵌入的作业引擎和浏览器图形设计器。步骤消费和生成数据流，中间数据无需保存为文件；需要时可通过显式执行策略启用物化和重放。

## 组成

| 模块 | 用途 |
|---|---|
| [xstream-data](xstream-data/README_zh.md) | DataCatalog → Dataset → Item、类型化 DataView/DataStream 契约、插件发现、文件及科学格式实现和网络编码 |
| [xstream-engine](xstream-engine/README_zh.md) | 嵌入式执行、取消、执行策略、自动远程计算下推及 `xstream-runner` 启动程序 |
| [xstream-designer](xstream-designer/README_zh.md) | 图形化作业编辑、目录、变量、结构和样例预览、聊天草案及 YAML 导入导出 |

`context:/dataset/item` 在当前 JVM 绑定的目录中解析；显式 `dacp://host:port/dataset/item` URI 使用配置的远程连接。科学数组、图像和序列保留自身结构，不会被隐式展平成记录。格式和后端实现通过插件契约注册。

## 构建

使用 JDK 17 和 sbt 1.10.x。这些模块从 [DACP](https://github.com/rdcn-link/dacp-codex) 拆出。DACP 通过版本化制品依赖 XStream；XStream 引擎和设计器通过版本化制品依赖 DACP 集成库。构建不会直接引用另一个源码目录。

目前尚无公开发布版本。为开发机器初始化本地制品仓库，请克隆两个项目后执行：

```bash
git clone git@github.com:rdcn-link/dacp-codex.git
git clone git@github.com:rdcn-link/xstream-codex.git
cd xstream-codex
scripts/bootstrap-local.sh ../dacp-codex
sbt test
(cd xstream-engine && sbt stage)
(cd xstream-designer && sbt stage)
```

初始化脚本按依赖顺序发布数据制品、DACP 公共集成库和引擎。显式的 DACP 路径仅用于初始化尚未发布的开发制品，普通构建通过 sbt 仓库解析制品。干净机器必须先取得集成制品才能构建引擎。详见[仓库边界](docs/repositories_zh.md)。

## 运行

在 XStream 项目根目录执行：

```bash
xstream-engine/target/universal/stage/bin/xstream-runner \
  examples/xstream/engine.yaml examples/xstream/read.yaml
xstream-designer/target/universal/stage/bin/xstream-designer \
  xstream-designer/example.yaml
```

请先修改配置路径、服务地址和凭据。启动程序将所选输出流写入 stdout；设计器默认监听 `http://127.0.0.1:8840/`。算子使用审核公开的仓库或经校验的本地缓存，内置 `read_item` 无需下载算子包。

## 文档

- [引擎和嵌入](docs/xstream_zh.md)
- [类型化流和科学视图](docs/data-streams_zh.md)
- [科学数据编排和预览](docs/designer-scientific_zh.md)
- [插件契约和注册](xstream-data/README_zh.md)
- [DACP 作业 YAML](https://github.com/rdcn-link/dacp-codex/blob/main/docs/jobs_zh.md)

## 标志与来源

原创矢量[完整标志](assets/xstream-logo.svg)和[图形符号](assets/xstream-mark.svg)以青绿/蓝色与紫色/琥珀色交叉流构成 X。设计器使用图形符号作为 favicon。源码来源见 [ORIGIN](ORIGIN)。实现保留现有包命名空间，本次拆分不重命名 API 包。
