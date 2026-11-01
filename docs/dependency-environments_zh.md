# 依赖环境与执行单元

[English](dependency-environments.md)

**Runtime** 提供固定摘要的操作系统镜像、解释器/JDK 及共享 SDK。**依赖环境**在 Runtime 上添加已锁定的第三方库。**执行单元**在一个节点上运行一个或多个步骤。共享环境并不意味着独立可执行程序变成同一个进程。

## 声明依赖

Python 项目将 `pyproject.toml` 放在 `operator.yaml` 旁边：

```toml
[project]
name = "weather-filter"
version = "1.0.0"
requires-python = ">=3.11,<3.14"
dependencies = ["packaging==25.0"]
```

```yaml
# operator.yaml：其他算子元信息以及必选的结构推导、样例接口保持一致。
dependencies:
  provider: python
```

Python 插件在注册的构建镜像中运行 uv，生成带哈希校验的传递依赖锁文件 `requirements.lock`，将选定的软件包安装到独立的依赖目录。构建镜像须提供 `/usr/local/bin/python` 和 `/usr/local/bin/uv`，可参考 Python Runtime Dockerfile。执行解释器与构建端必须面向相同的 Python ABI/平台。Job 执行时不安装软件包。无第三方依赖的脚本使用 `dependencies = []`，不要求产生编译后的二进制。

JVM 代码配置：

```yaml
dependencies:
  provider: jvm
```

插件读取 `pom.xml` 中版本明确的依赖，或 Maven、Gradle、sbt 构建导出的 `dependencies.json`。导出文件包含运行期 Maven 坐标：

```json
["org.apache.commons:commons-csv:1.14.1"]
```

也可以通过 `dependencies.coordinates` 提供此数组。Coursier（注册构建镜像中的 `/usr/local/bin/cs`）解析传递依赖；锁文件记录下载的每个 JAR 的路径和 SHA-256。使用属性、依赖管理版本或复杂 scope 的 Maven 项目须导出 `dependencies.json`，插件不解释任意 Gradle/sbt 程序。Arrow 和 XStream SDK 由 Runtime 提供，不允许通过直接依赖坐标覆盖。

仓库使用注册的语言构建配方与解析得到的 JAR 编译 `src/`。本地项目可通过 `local.yaml` 的 command 构建器调用自己的构建工具。`jvm` 本地构建器在选定的 JDK 镜像内编译简单的 Java `src/` 项目，适用于无依赖模板。完整的 Maven/Gradle/sbt 项目使用 command 构建器，并导出运行期坐标以准备环境。

原生库和命令可放在 `lib/`、`bin/`。配置 `dependencies.provider: native` 后，它们被安装到 `/opt/xstream/lib`、`/opt/xstream/bin`。Python/JVM 插件也会包含这两个目录。它们仍然依赖平台；随包提供库不会自动解决 glibc 不兼容、绝对 RPATH 或驱动缺失。符号链接和不安全路径会被拒绝。Runtime 必须提供脚本使用的解释器。

## 创建、检查、构建和提交

```bash
xstream-operator init ./weather-filter --language python
xstream-operator check ./weather-filter
xstream-operator build ./weather-filter --config ./engine.yaml
xstream-operator submit ./weather-filter --url https://repo.example.org --token-file ./publisher.token
```

模板支持 `python`、`java`、`shell`。Shell 模板包含包装一小段 Shell 代码的 Python SDK 适配器，选定 Runtime 必须包含 `/bin/sh`。它示范无状态的字节块转换，不是需要完整输入流的任意命令的通用适配器；此类命令须实现 `process(ctx)`，通过有界缓冲连接标准输入输出。包装器提供与其他算子相同的必选结构推导和有界样例接口。

`build` 调用 `xstream-runner build-operator CONFIG.yaml DIRECTORY`。这是可信的本地操作，远程 Job 不能请求任意本地源码构建。若精简执行 Runtime 不包含依赖解析工具，本地设置可通过 `dependency_builder_image` 单独指定构建镜像。提交前应选择支持该语言的已注册 Runtime；模板中的 runtime 名称是可编辑示例。

源码归档包含 `src/`、可选的 `resources/`、`bin/`、`lib/` 和依赖声明文件，不包含 `local.yaml`、凭据、构建输出及缓存。提交前检查有意放入可发布目录的所有文件。

## 第三方二进制包装算子

仓库接受源码提交和二进制包装提交，后者始终需要提供实现算子接口的包装源码。算子配置示例：

```yaml
submission: binary
binary:
  artifact: vendor/tool
  source_url: https://vendor.example.org/tool
  version: "2.0"
  platform: linux/amd64
  license: BSD-3-Clause
dependencies:
  provider: native
```

CLI 单独上传可执行文件，并将 `binary.artifact` 替换成摘要/大小/文件名描述。仓库检查归属和平台，将其安装到 `/opt/xstream/bin/<原文件名>`，构建包装代码，所有产物经批准后才公开。使用 Python/JVM 包装器适配流格式、提供结构推导及有界样例。仅有一个可执行文件并不构成完整算子。

自动源码报告**仅覆盖包装源码**，不为二进制自动赋予可信度。批准时必须明确给出管理员评分和审核说明。公开算子页区分仓库从源码构建的算子和第三方二进制包装算子，并展示管理员的最终评价。`source_public` 控制包装源码是否公开，批准后的可执行文件可下载。审核时需要考虑许可证和再分发责任。

## 锁文件、缓存与执行规划

```mermaid
flowchart LR
  A[源码与依赖声明] --> B[插件：uv / Coursier / 随包文件]
  B --> C[锁文件：平台与产物哈希]
  C --> D[基础 Runtime + 规范化锁文件指纹]
  D --> E[不可变的缓存环境镜像]
  E --> F[物理执行单元]
```

锁文件包含平台、解析后的依赖内容及相关加载信息。路径用于定位文件，摘要用于识别内容。调整映射键顺序不改变指纹；基础 Runtime 镜像 ID 或库内容变化则会改变指纹。发布元信息携带 `environment.fingerprint`、`environment.image`、`environment.archive` 和 `environment.lock`；客户端验证归档与指纹后加载固定摘要镜像。

环境归档在跨进程锁保护下原子构建。租约保护构建端获取和使用中的环境。准备环境时清理空闲归档，默认空闲期为 24 小时，并回收失效进程的租约。不自动删除 Docker 镜像，因为其他应用可能仍在使用。执行时复用已加载的不可变镜像，不修改共享环境。

同一节点上的步骤形成连通、凸的区域，锁定依赖环境及进程设置相同，并实现执行器的进程内接口时，可融合执行。不同算子代码包可以融合。独立分支不会仅因依赖相同而合并。混合环境可以形成多个执行单元。物化、重放和重试策略保留其执行边界；目前融合适用于默认流式策略。不会通过切换 Python 模块搜索路径模拟依赖隔离。原生可执行程序保持独立进程，除非另行提供明确的进程内接口。

物理计划展示环境指纹、Runtime 和进程数，边界说明依赖/执行器或策略原因。独立单元之间继续使用有界流连接，缓存依赖环境不会物化流处理结果。

## 扩展与验证

实现 `xstream.operator.DependencyEnvironmentProvider`，并在以下文件中注册类名：

```text
META-INF/services/xstream.operator.DependencyEnvironmentProvider
```

`resolve` 调用依赖工具并返回确定的传递依赖锁；`build` 按该锁生成固定摘要镜像和归档。长操作须轮询 `check()`，并在失败、取消时清理。执行服务通过插件环境发现 provider JAR，仓库构建端从应用 classpath 加载。核心代码没有语言插件注册表。

运行 contract、依赖插件、执行器、结构推导、引擎以及仓库发布测试。可选的真实容器测试：

```bash
cd plugins/xstream-plugin-dependencies
XSTREAM_DEPENDENCY_DOCKER_TESTS=1 sbt test
```

需要 Docker 权限、`xstream/operator-python:1.0`，以及匹配的、包含 uv 的 `xstream/dependency-python-builder:test` 镜像。单元测试覆盖规范化指纹、并发缓存构建、租约回收、损坏检测、取消、插件发现、路径安全和执行单元划分。兼容依赖集合合并及 JVM ClassLoader 隔离是后续增强；当前仅融合完全相同的环境。
