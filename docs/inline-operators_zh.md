# Job 内联算子

[English](inline-operators.md)

本地 XStream Job 可以在顶层 `operators` 中直接定义算子，步骤用 `operator: {inline: name}` 引用。适配器通过 `xstream.execution.InlineOperatorProvider` 发现；内置的 `sdk`、`command`、`script` 提供者位于 `xstream-plugin-operator-executors`。所有定义都会校验，包括未被步骤使用的项。本地开发无需先创建算子库目录或提交算子仓库。

当一个 Job 需要把程序定义与步骤放在一起时，使用这种方式。已有 XStream SDK 程序选 `sdk`，第三方命令行工具选 `command`，Job 内嵌源码或本地脚本文件选 `script`。实现类型与图角色（`source`、`transform`、`sink`）是两个独立的选择。可复用算子的开发路径见[开发指南](development_zh.md)。

## 配置运行环境

独立引擎配置为运行时指定已安装的容器镜像。SDK 采用 `executor` 指定的执行器；命令工具和脚本使用内置的 `command` 桥接器。后两类镜像需要包含 `/usr/local/bin/python`、PyArrow 及所选工具／解释器。程序在隔离容器中运行，受内存及临时空间配额约束。

```yaml
inline_runtimes:
  python_tools:
    image: xstream/operator-python:1.0
    executor: python
  jvm_tools:
    image: xstream/operator-jvm:1.0
    executor: jvm
```

运行时镜像与解释器由本地配置选择，不授予 Job 在宿主机上执行的权限。`xstream-runner` 默认以 Job YAML 所在目录作为 `job_working_directory`；嵌入式引擎也可显式配置。程序路径相对于该目录解析。引用文件在执行前复制到私有暂存区，受大小上限与取消检查约束；Job 结束后由引擎清理。`script.source` 最大 512 KiB。

## SDK 程序

SDK 定义声明程序文件、入口和输入端口契约。Scala／Java 使用 JAR 和 JVM 完整入口类名。Python 使用 `.py`、`.zip` 或 `.whl`；包的 `entrypoint` 为模块名。程序需遵守现有 XStream SDK 的处理和推导协议。

```yaml
operators:
  filter:
    type: sdk
    role: transform
    runtime: jvm_tools
    program: {path: ./target/filters.jar, entrypoint: example.FilterOperator}
    inputs:
      source: {representations: [records], formats: []}
    outputs:
      result: {view: records}
```

依赖由配置的运行时镜像提供。Python SDK 可使用 `runtime: python_tools` 和 `program: {path: ./filter.py, entrypoint: filter}`。SDK 算子可直接处理所声明的结构化流；声明一种表示方式不会把数组或序列扁平化。

## 现成命令行工具

内置适配器处理 bytes 流：每个输入可绑定到 `stdin` 或临时 `file`，每个输出可来自 `stdout` 或临时 `file`。镜像中已安装的工具用 `command` 指定；本地可执行文件还可通过 `artifact: ./tool` 引入，复制到容器中有界的可执行暂存区。命令直接启动，不经过 shell。

```yaml
operators:
  convert:
    type: command
    role: transform
    runtime: python_tools
    command: /usr/bin/gdal_translate
    args: [{param: format}, {input: source}, {output: result}]
    inputs:
      source: {view: bytes, binding: file}
    outputs:
      result: {view: bytes, binding: file, format: geotiff}
```

`args` 的项可以是字面字符串，或完整值引用 `{param: name}`、`{input: port}`、`{output: port}`。参数值必须是标量。每个文件端口都必须在 `args` 中引用，Job 不能自选生成的文件路径。每个方向最多 32 个端口，stdin 和 stdout 各最多一个。文件绑定在容器中受配额约束的 `/tmp` 物化，执行后清理；步骤间的 Arrow 端口仍传递 bytes 流。程序以非零状态退出或输出文件不存在时 Job 失败。records、数组、多种科学视图或定制预览应使用 SDK 算子。

## 现成脚本

脚本定义使用相同的输入输出及参数声明。`script.path` 指向已有本地文件，或使用 `script.source` 内嵌源码，两者必须恰选其一。解释器使用运行镜像中的绝对路径。脚本本身无需导入 XStream SDK。

```yaml
operators:
  clean:
    type: script
    role: transform
    runtime: python_tools
    interpreter: /usr/local/bin/python
    script: {path: ./clean.py}
    args: [{input: source}, {output: result}, {param: threshold}]
    inputs:
      source: {view: bytes, binding: file}
    outputs:
      result: {view: bytes, binding: file, format: csv}
```

兼容流输入输出的脚本可使用 `stdin`／`stdout` 绑定。适配器不会执行 shell 字符串插值。取消时引擎终止容器及其中程序；stdout 专供声明的输出 bytes，诊断写入 stderr。

## 运行与发布

```yaml
name: Local pipeline
operators:
  upper:
    type: script
    role: transform
    runtime: python_tools
    interpreter: /usr/local/bin/python
    script:
      source: |
        import sys
        sys.stdout.buffer.write(sys.stdin.buffer.read().upper())
    inputs:
      source: {view: bytes, binding: stdin}
    outputs:
      result: {view: bytes, binding: stdout, format: text}
steps:
  - id: read
    operator: item_source@1.0
    inputs: {}
    params: {uri: 'context:/files/input.txt', view: bytes}
  - id: upper
    operator: {inline: upper}
    inputs: {source: read:result}
    params: {}
  - id: client_sink
    operator: client_sink@1.0
    inputs: {result: upper:result}
```

运行命令为 `xstream-runner engine.yaml job.yaml`。远程提交前，需要将自定义算子发布到公开仓库，并把内联引用替换为获批的 `name@version` 引用。DACP 节点和 Union 拒绝**任何**包含 `operators` 的 Job，包括未使用的定义；也拒绝本地目录和旧式源码引用。Scala／Python 客户端在连接前拦截，服务端再次校验。Designer 保留内联 YAML 定义并支持本地结构推导；编辑时应选择本地 Target。

注册提供者时，编写无参的 `InlineOperatorProvider` 实现，并将类名写入 `META-INF/services/xstream.execution.InlineOperatorProvider`。实现 `validate` 与 `prepare`；插件负责定义字段，引擎负责暂存清理、容器隔离和取消。验证命令：在 `plugins/xstream-plugin-operator-executors` 下执行 `sbt 'testOnly xstream.operator.executors.InlineOperatorsSpec'`；具备 Docker 访问权限且已构建 runner 后执行 `sudo -n python3 scripts/test-inline-operators.py`。要在冒烟测试中包含 Scala SDK JAR，可传入 `--jvm-jar path/to/operators.jar --jvm-entrypoint your.package.Uppercase`；入口需要读取 bytes 类型的 `source` 端口，并向 `result` 端口输出大写后的 bytes 数据。
