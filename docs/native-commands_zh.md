# 原生命令算子

已实现：`executor: command` 将一个 bytes 输入端口连接到程序 stdin，将程序 stdout 包装成一个 bytes 输出端口。程序可使用 C、C++、Rust、Go 编译，也可使用现有命令行工具。程序在独立 Docker 容器内执行，不在服务器宿主机启动。普通模式可执行已发布的命令算子，不支持内联原生源码编译。

```yaml
name: uppercase
version: "1.0"
image: example/native-runtime:1
executor: command
entrypoint: /usr/bin/tr
executor_config:
  args: [a-z, A-Z]
  stdin: source
  stdout: {port: result, representation: bytes, format: text}
inputs:
  source: {representations: [bytes], formats: []}
icon:
  path: "M3 5h18v14H3zM3 10h18M9 5v14"
  color: "#14866b"
parameters:
  type: object
  properties: {}
  required: []
  additionalProperties: false
```

运行时须包含 `/usr/local/bin/python`、PyArrow、可执行程序及匹配系统和架构的依赖。桥接器复用 Python 运行时，但原生程序自身无需依赖 Python、Arrow 或 DACP。`xstream/operator-python:1.0` 可运行[独立 C 制品示例](../examples/operators/native/README_zh.md)，不保证包含 `tr` 等系统工具；请在派生镜像中预装所需程序。

`entrypoint` 必须为绝对路径。`args` 为字面字符串列表，直接传递参数，不经过 shell、不展开变量或替换作业参数。`args`、`stdin`、`stdout` 均为必填，未知设置会被拒绝。在 `inputs` 中声明 stdin 对应的端口，且仅支持 bytes。源算子设置 `stdin: null`，汇算子设置 `stdout: null`。清单不声明 `outputs`：执行器必选的结构推导方法根据 `executor_config.stdout` 给出输出结构。非空 stdout 必须指定 `port`、`representation: bytes` 和非空 `format`。格式标签不会触发转换。

若独立上传可执行文件，设置 `artifact: path/to/program` 和 `entrypoint: /operator/program`，文件名须一致。本地制品路径相对 `operator.yaml`。下载文件经过校验并只读挂载；由于内容寻址存储不保存执行权限，桥接器将程序复制到容器私有 `/operator-work`、设置执行权限后启动。该目录是插件请求的 512 MiB 有界可执行 tmpfs，普通 `/tmp` 仍不可执行。共享库由运行时提供，执行时不下载依赖或编译。

桥接器从输入 FIFO 的 Arrow IPC 字节单元读取原始字节、写入 stdin，输入结束后关闭 stdin。同时以最大 64 KiB 的块读取 stdout 并产生 XStream start/data/end 帧。管道和缓冲区有界，保留背压，不收集完整输入输出。stderr 仅用于诊断。程序退出成功且输入传递成功后才产生 end；非零退出或输入传递失败使作业失败，已输出的数据是不完整结果。取消和超时使用引擎容器清理机制，终止程序及其子进程。命令应消费完整 stdin；提前关闭 stdin 不作为成功截断机制。主动预览请使用 shell 的预览参数。

适配器支持零个或一个 bytes 输入、零个或一个 bytes 输出。尚未实现 records 序列化、多管道、文件名参数、可 seek 临时输入文件和 shell 流水线。复杂情况请使用显式算子包装，不隐式展平结构或多维数据。

## 发布与执行

仓库仅接受源码提交，通过 `xstream-operator submit` 上传，由仓库构建并审核。原生程序需要管理员提供的构建运行时；发布包和共享执行运行时分别下载。参见[提交与审核](operator-submission_zh.md)。本地开发可使用[本地算子库](local-operators_zh.md)的 `command` 构建器。

Job 使用普通输入连接引用该算子，通过 `xstream-runner engine.yaml job.yaml` 执行。

## 插件契约与验证

`CommandExecutor` 注册于 `META-INF/services/dacp.OperatorExecutor`，位于 `plugins/xstream-plugin-operator-executors`。外部插件以 Provided 依赖 `xstream-execution-api`，打包 service 文件，将 JAR 和私有依赖放入宿主 `plugin_directory` 后重启。插件验证自身配置，引擎管理资源。

```bash
(cd plugins/xstream-plugin-operator-executors && sbt 'testOnly xstream.operator.executors.CommandExecutorSpec')
```

测试覆盖注册、字面参数、端口支持、配置与启动规则。完整例子见[原生命令算子](../examples/operators/native/README_zh.md)。
