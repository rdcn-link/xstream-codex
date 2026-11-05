# File handoffs and streaming benchmark

Compare Airflow 3.3.2 with XStream using the same published Scala/Python operators, JAR hashes, runtime images and SQL kernels. The optional `llm-tools` method adds real LangGraph/Responses function-call scheduling.

## Workloads

1. **Pipeline:** parse CSV, reject bad observations, select warm observations, serialize CSV.
2. **Join:** parse observations and station metadata, inner join by station ID, reject bad observations, serialize CSV.
3. **Aggregate:** parse CSV, reject bad observations, count and sum temperature by station, serialize CSV.

Inputs contain quoted commas, 1,024 station IDs and a deterministic payload. An independent Python CSV implementation checks the complete output SHA-256, length and row count. Input generation and input wrapping occur before timing.

## Methods

- `airflow-file`: Airflow runs a real standalone scheduler, DAG processor, API server and LocalExecutor on a local SQLite test database. Each task starts a container; XCom carries path metadata. This does not use `dag.test()`. Each task writes an Arrow IPC stream file and returns its path. Retries are disabled; Airflow retains scheduling metadata and XCom path values. The final CSV is consumed and hashed without saving it.
- `llm-tools`: LangGraph alternates model decisions with actual Scala/Python tool execution. The model selects ready steps and supplies input handles from the same fixed DAG; operator parameters remain bound to the DAG. Independent tool calls can execute concurrently. Intermediate Arrow files use the same `FileExecutor` as Airflow. Model network/response latency, graph overhead and the final completion response are timed. Data payloads never enter model context.
- `xstream-isolated`: the same operators communicate through streams, each with its own runtime process.
- `xstream-fused`: compatible operators may share a runtime process. Intermediate materialization is forbidden by policy in both XStream modes.

This tests specific integrations; Airflow does not inherently require file handoffs. Metadata, logs and framework state may be written in every method. “No intermediate files” refers to data payloads, not all filesystem activity.

Join and SQL aggregation use bounded in-memory tables. Streaming handoff does not imply every kernel is incremental or constant-memory.

## Run

Run from the XStream repository. Install Docker and Java 17, prepare the runtime images and start an operator repository containing the Scala PiFlow operators at version `2.0` and Python `piflow_filter@1.2`.

```bash
python3.12 -m venv /tmp/xstream-airflow-venv
/tmp/xstream-airflow-venv/bin/pip install apache-airflow==3.3.2 \
  --constraint https://raw.githubusercontent.com/apache/airflow/constraints-3.3.2/constraints-3.12.txt
/tmp/xstream-airflow-venv/bin/pip install -r benchmarks/orchestration/requirements.txt
/tmp/xstream-airflow-venv/bin/pip check
cd benchmarks/orchestration
sbt -batch compile writeClasspath
cd ../..
/tmp/xstream-airflow-venv/bin/python benchmarks/orchestration/suite.py \
  --repository http://127.0.0.1:8820 \
  --output .benchmark-runs/orchestration \
  --rows 10000 100000 250000 --warmups 1 --repeats 3
```

The executing account must have Docker access. The output directory must not exist. Results are saved after each sample in `results.json` and `report.md`. Warmups are recorded but excluded from medians. Any output mismatch or failed trial makes the suite exit unsuccessfully.

## Measurement boundaries

Persistent framework hosts are initialized before timing; startup is recorded separately. Each job measures scheduling, container/process startup, parsing, computation, serialization and complete output consumption. Method order is randomized with a recorded seed. OS caches remain warm. Repository downloads and image preparation are excluded. Airflow receives CSV prewrapped as Arrow binary input; XStream wraps CSV during execution, conservatively favoring the baseline.

Report wall time, time to first final-output data, throughput and logical intermediate file bytes. Logical file bytes are not physical disk traffic; default writes use OS buffering. Use `--fsync` for explicit intermediate-file synchronization. These runs do not measure crash recovery, correctness after failure, physical disk I/O, or peak CPU/memory. Join and aggregate inputs are capped at 250,000 rows for the operators' bounded input limits. Larger streaming pipeline tests can be run separately with `--workflows pipeline`.

Reference: [Airflow XComs](https://airflow.apache.org/docs/apache-airflow/stable/core-concepts/xcoms.html).

## Model tool scheduling

The tested environment uses Python 3.12. This method reads `config.toml` and `auth.json` from `--codex-home` (default `~/.codex`) without changing them. It uses the configured model, Responses provider base URL, reasoning effort, headers, and API credential. Credential resolution: provider `experimental_bearer_token`, provider `env_key`, then `auth.json` `OPENAI_API_KEY`. No credentials or provider URL are saved in results. Errors omit HTTP bodies and headers.

```bash
/tmp/xstream-airflow-venv/bin/python benchmarks/orchestration/suite.py \
  --output .benchmark-runs/orchestration-with-llm \
  --rows 250000 --warmups 1 --repeats 3 \
  --methods airflow-file llm-tools xstream-isolated xstream-fused \
  --codex-home "$HOME/.codex"
```

Model calls use real API quota. There are no API retries or fixed-plan fallback; dependency violations, early completion and exhausted turn budgets fail the sample. Each sample has a 12-response limit and each HTTP request a 180-second timeout. Usage, cached input/reasoning tokens, model/API wall time, tool batch wall time and actual tool call order are recorded. Failed agent runs retain completed-call metrics. All methods must pass the same independent oracle. The default methods omit `llm-tools`, so a normal file/stream run does not invoke a model.

This compares execution of an already specified DAG, not natural-language planning quality. LangGraph is the application loop; model requests use the Responses function-calling API. Provider/model choice and network/load affect model timings. No model price is inferred from token counts.

[Official function-calling API](https://developers.openai.com/api/docs/guides/function-calling).

Publish sanitized raw records and complete English/Chinese reports after the four-configuration suite finishes:

```bash
/tmp/xstream-airflow-venv/bin/python benchmarks/orchestration/summarize_llm.py \
  .benchmark-runs/orchestration-with-llm/results.json
```

Run the local contract checks (no model calls):

```bash
/tmp/xstream-airflow-venv/bin/python -m unittest discover -s benchmarks/orchestration -p 'test_*.py' -v
```

## Figures

Publishing generates six measured SVG figures in both languages: job wall time, first final-output data latency, logical intermediate-file volume, completion counts, operator process counts and model scheduling breakdown. Bars show successful-run medians and min–max whiskers; model breakdown uses means for additive components. Three Mermaid diagrams describe the mixed-language workloads, file/stream handoffs and Airflow scheduling/execution chain. Figures and sanitized raw records are bundled with the report.

Airflow uses parallelism 8, a 1-second Scheduler heartbeat, 5-second DAG refresh/minimum processing intervals and 0.2-second controller polling. Scheduling and polling waits are included; framework services remain running during the complete batch.
