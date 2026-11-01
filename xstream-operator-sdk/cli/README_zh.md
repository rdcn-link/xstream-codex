# XStream 算子命令行工具

[English](README.md)

在 XStream 源码目录安装源码提交工具：

```bash
python3 -m pip install ./xstream-operator-sdk/cli
xstream-operator --help
xstream-operator submit --help
```

建议使用 Python 虚拟环境，或通过 `pipx install ./xstream-operator-sdk/cli` 隔离安装。该包只需要 Python 3.10+ 和 PyYAML，不依赖 PyArrow、JVM 或 Docker；仓库端构建仍需要配置的构建运行环境。

提交包含 `operator.yaml` 和 `src/` 的本地算子目录：

```bash
xstream-operator submit /path/to/my-operator \
  --url http://localhost:8820 \
  --token-file /secure/submitter.token
```

工具自动打包 `src/` 和可选的 `resources/`，排除 `local.yaml` 及顶层构建产物，拒绝符号链接。清单引用的许可证、SBOM 文件单独上传。

也可以指定源码提交 YAML 和已有的 ZIP/TAR：

```bash
xstream-operator submit /path/to/submission.yaml \
  --source /path/to/sources.zip \
  --url https://operators.example.org \
  --token-env XSTREAM_SUBMIT_TOKEN
```

`--token-env` 从指定环境变量读取令牌。提交者 Token 由仓库管理员分配。工具查询注册语言和运行环境，上传源码材料并提交扫描、构建和审核。JSON 输出包含提交 ID 和待审状态；管理员批准后才公开。远程提交要求 HTTPS，仅 localhost／回环地址开发允许 HTTP。不提供管理员会话或自动批准功能。

参见[源码提交与审核](../../docs/operator-submission_zh.md)和[本地算子](../../docs/local-operators_zh.md)。安装后可在任意目录执行，无需读取 XStream 源码目录中的文件。
