# 原生命令算子示例

[English](README.md)

本例 C 程序从 stdin 读取字节，将 ASCII 转为大写后写到 stdout。容器中的 `command` 执行器负责 Arrow 流桥接，C 程序无需 Arrow 依赖。

```bash
mkdir -p examples/operators/native/build
cc -O2 -Wall -Wextra -Werror -s \
  -o examples/operators/native/build/uppercase examples/operators/native/uppercase.c
printf 'hello' | examples/operators/native/build/uppercase
(cd plugins/xstream-plugin-operator-executors && sbt 'testOnly xstream.operator.executors.CommandExecutorSpec')
```

执行定义见 `operators/native_uppercase/1.0/operator.yaml`，使用共享 `xstream/operator-python:1.0` 运行时。二进制必须匹配其操作系统、架构和 C 库。stdout 逐块转为输出，不发布结果 Dataset。原生程序的参数、资源和端口约束见[原生命令契约](../../../docs/native-commands_zh.md)。

本地开发使用[本地算子库](../../../docs/local-operators_zh.md)；公开发布采用[源码提交及审核](../../../docs/operator-submission_zh.md)，仓库构建最终二进制。
