# Runtime management

[中文](operator-runtimes_zh.md)

## Browse and select

The public **Runtimes** page and GET `/v1/runtimes` list registered environments, their versions, executors, dependencies and build support. Choose a compatible name/version for source submissions. Dependencies stay in shared images to keep individual operator packages small. Download runtime archives from Runtimes rather than the operator detail page.

## Register an environment

An administrator signs in at `/admin`, opens Runtimes and supplies a name, version, description, pinned execution image ID and its archive, supported executors and dependency versions. Register the compatible build image/archive and languages too. Python builds need the matching Python version and installed SDK/dependencies. Java/Scala builds need the selected compiler, SDK and dependency classpath. Build environments download no dependencies during compilation. The environment catalog and registered archives are public; version entries are immutable.

Use a minimal Python/Arrow environment for records, a JVM SDK environment for Java/Scala, or a scientific Python environment when scientific libraries are required. These are environment choices, not a promise that specific versions are installed on every repository.

## Image identity and archive checksum

The Docker image ID pins the execution environment. The archive's SHA-256 verifies the downloadable archive file. They identify different objects and need not match. The operator package contains business code; its checksum is separate again.

## Build isolation

The repository runs fixed build recipes in isolated Docker containers. A successful build remains private until approval. See [source submission and review](operator-submission.md#isolated-builds) for compiler requirements, limits and isolation details, and [source analysis/build extensions](operator-build-extensions.md) for plugin registration.
