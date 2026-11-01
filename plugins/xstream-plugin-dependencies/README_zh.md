# 依赖环境插件

[English](README.md)

通过 `DependencyEnvironmentProvider` 扩展实现 Python（uv）、JVM（Coursier）和原生 bin/lib 的依赖解析及固定镜像构建。实现通过 `META-INF/services/xstream.operator.DependencyEnvironmentProvider` 注册，无需修改核心注册表。

配置、构建工具要求、缓存生命周期和二进制提交示例见[依赖环境指南](../../docs/dependency-environments_zh.md)。

运行 `sbt test` 验证插件发现、坐标校验、路径安全和取消行为；设置 `XSTREAM_DEPENDENCY_DOCKER_TESTS=1` 可启用真实容器测试。
