# Native command operator example

[中文](README_zh.md)

This C program reads bytes from stdin and writes uppercase ASCII to stdout. The container's `command` executor bridges Arrow streams; the C program needs no Arrow dependency.

```bash
mkdir -p examples/operators/native/build
cc -O2 -Wall -Wextra -Werror -s \
  -o examples/operators/native/build/uppercase examples/operators/native/uppercase.c
printf 'hello' | examples/operators/native/build/uppercase
(cd plugins/xstream-plugin-operator-executors && sbt 'testOnly xstream.operator.executors.CommandExecutorSpec')
```

The execution definition is `operators/native_uppercase/1.0/operator.yaml`, using the shared `xstream/operator-python:1.0` runtime. The binary must match its OS, architecture and C library. Stdout becomes chunked output, without publishing a result Dataset. See [native command contracts](../../../docs/native-commands.md) for arguments, resources and ports.

Use [local operator libraries](../../../docs/local-operators.md) for development and [source submission and review](../../../docs/operator-submission.md) for public publication; the repository builds the final binary.
