# 仓库边界与制品构建

[English](repositories.md)

XStream 负责数据契约及实现、流式引擎及设计器；XStream 同时负责算子 SDK/契约、推导、执行器及源码扫描插件、运行环境构建工具和算子仓库；DACP 负责服务传输、授权、联合调度和客户端。这是源码仓库拆分，不意味着引擎移除了全部 DACP 库依赖。

制品依赖图无环：

1. XStream 数据制品。
2. 依赖数据制品的 DACP 公共集成制品。
3. 依赖数据和 DACP 集成制品的 XStream 算子 SDK、推导、执行器，随后构建引擎。
4. 依赖引擎和公共库的 DACP 服务与 XStream 设计器。

在本项目执行 `scripts/bootstrap-local.sh /path/to/dacp-codex`，即可按顺序发布开发依赖。脚本要求显式源码路径。制品组织名为 `io.github.rdcn-link`，版本通常为 `0.1.0-SNAPSHOT`；`dacp-client-scala` 为 `0.1.0`。普通构建解析 Maven/Ivy 制品，不探测相邻源码目录，不使用符号链接或源码回退。向其他机器分发构建时应配置共享制品仓库；目前未发布的依赖必须先发布到该仓库，或在本地初始化。

在本项目根目录执行 `sbt test` 运行数据、引擎和设计器测试。解析器/流契约测试位于 `xstream-data/xstream-data-tests`；浏览器检查位于 `scripts/test-designer-*.py`，需要 Playwright 和 Chromium。DACP 服务及集成测试仍在 DACP 项目中。两个项目分别执行 `python3 scripts/check-doc-translations.py` 检查文档配对。

第三方格式依赖保留在数据插件模块，现有 ServiceLoader 注册文件随实现迁移。贡献者注册和配置示例仍在数据及设计器指南中；新增插件不需要修改中央注册表。
