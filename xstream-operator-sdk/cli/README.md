# XStream operator command-line tools

[中文](README_zh.md)

Install the source-submission tool from the XStream checkout:

```bash
python3 -m pip install ./xstream-operator-sdk/cli
xstream-operator --help
xstream-operator submit --help
```

Use a Python virtual environment or `pipx install ./xstream-operator-sdk/cli` for an isolated installation. The package requires Python 3.10+ and PyYAML; it does not depend on PyArrow, a JVM or Docker. Repository-side builds still need the configured build runtime.

Submit a local operator directory containing `operator.yaml` and `src/`:

```bash
xstream-operator submit /path/to/my-operator \
  --url http://localhost:8820 \
  --token-file /secure/submitter.token
```

The tool packages `src/` and optional `resources/`; it excludes `local.yaml` and top-level build outputs. Symbolic links are rejected. License/SBOM files referenced in the manifest are uploaded separately.

Alternatively, provide a source submission YAML and an existing ZIP/TAR:

```bash
xstream-operator submit /path/to/submission.yaml \
  --source /path/to/sources.zip \
  --url https://operators.example.org \
  --token-env XSTREAM_SUBMIT_TOKEN
```

`--token-env` reads the named environment variable. Obtain a submitter token from the repository administrator. The tool resolves registered languages and runtimes, uploads source materials and submits them for analysis, build and review. JSON output contains the submission ID and pending status; approval is required before public publication. Remote submission requires HTTPS; HTTP is accepted only for localhost/loopback development. No administrator session or automatic approval is provided.

See [source submission and review](../../docs/operator-submission.md) and [local operators](../../docs/local-operators.md). The installed command works from any directory and does not read files from the XStream checkout.
