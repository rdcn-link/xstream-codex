# XStream file execution storage

[English](README.md)

执行历史和物化存储的 ServiceLoader 实现，provider ID 均为 `file`。在引擎宿主中配置私有目录和配额，不要在 Job 中暴露存储路径。
