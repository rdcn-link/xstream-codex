# 文件交接、模型工具编排与流处理测试

使用相同的 Scala/Python 算子、内容摘要、运行镜像和计算逻辑，比较 Airflow 3.3.2 与 XStream。可选的 `llm-tools` 使用真实 LangGraph/Responses 函数调用。

## 工作负载

1. 筛选：Scala CSV 解析 → Python 质量筛选 → Scala 温度筛选 → Scala CSV 输出。
2. Join：Scala 解析观测表和站点表 → Scala 内连接 → Python 质量筛选 → Scala CSV 输出。
3. 聚合：Scala CSV 解析 → Python 质量筛选 → Scala SQL 分组计数和温度求和 → Scala CSV 输出。

输入包含引号内逗号、1,024 个站点和确定性负载。独立 Python 实现校验最终结果 SHA-256、字节数和行数。输入生成及包装在计时前完成。

## 方法

- `airflow-file`：真实 standalone 调度器、DAG processor、API 服务及 LocalExecutor，采用本地 SQLite 测试数据库，不使用 `dag.test()`。任务启动容器，XCom 传递路径元信息，步骤间通过 Arrow IPC 文件交接数据。重试为零；Airflow 保存调度状态及 XCom 路径。最终 CSV 仅消费并计算摘要，不保存。
- `llm-tools`：LangGraph 交替执行模型决策和真实 Scala/Python 工具。模型从固定 DAG 中选择就绪步骤并提供输入句柄，参数绑定到 DAG。独立调用可并行。与 Airflow 共用文件执行器；模型 API 等待、图循环和最终完成响应均计时。数据内容不进入模型上下文。
- `xstream-isolated`：算子在独立进程中通过流通信。
- `xstream-fused`：兼容算子可共用运行进程；Scala/Python 边界保持独立。两种流模式均禁止中间物化。

这些是具体配置，Airflow 不强制使用文件。所有方式均可写入日志、代码及框架元信息。无中间文件指的是数据负载。Join 和 SQL 使用有界内存表；流传输不代表所有计算都是增量或常量内存。

## 运行

需要 Python 3.12、Docker、Java 17、sbt，及包含 Scala PiFlow 2.0 算子和 Python `piflow_filter@1.2` 的算子仓库。准备好运行镜像，执行账号需要 Docker 权限。

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

输出目录必须不存在。每个样本后更新 `results.json` 和 `report.md`；预热保留但不计入中位数。失败或结果不一致使程序非零退出。Airflow API 仅监听动态分配的回环端口；框架退出后停止。

## 计时与限制

框架及持久宿主启动不计时，单独记录。作业计时包含调度、容器/进程启动、解析、计算、序列化及完整结果消费。随机种子控制交错顺序；保留操作系统热缓存。输入生成、下载及镜像准备不计时。文件方案预先将 CSV 包装成 Arrow binary，XStream 在计时内包装，偏向文件基线。

记录总耗时、首批最终结果延迟、吞吐和中间文件逻辑字节数。文件默认使用缓冲写入，逻辑大小不是物理磁盘流量；`--fsync` 可开启同步写入。不测试故障恢复、持久性保证、物理磁盘 I/O 及资源峰值。Join/聚合限制最多 250,000 行，流水线更大规模可单独指定 `--workflows pipeline`。

[Airflow XCom 文档](https://airflow.apache.org/docs/apache-airflow/stable/core-concepts/xcoms.html)。

## 模型工具编排

只读加载 `--codex-home`（默认 `~/.codex`）中的 `config.toml` 和 `auth.json`，使用配置的模型、Responses 服务地址、推理强度、请求头及凭据。凭据优先级：提供商 `experimental_bearer_token`、`env_key`、`auth.json` 中的 `OPENAI_API_KEY`。报告不保存凭据、请求头或服务地址，错误省略 HTTP 正文及请求头。

```bash
/tmp/xstream-airflow-venv/bin/python benchmarks/orchestration/suite.py \
  --output .benchmark-runs/orchestration-with-llm \
  --rows 250000 --warmups 1 --repeats 3 \
  --methods airflow-file llm-tools xstream-isolated xstream-fused \
  --codex-home "$HOME/.codex"
```

真实调用消耗 API 配额，不重试、不使用固定计划回退。依赖违规、提前结束及轮数超限均失败。每样本最多 12 次响应，每 HTTP 请求超时 180 秒。记录用量、缓存及推理 token、API/工具批次耗时和执行顺序；失败保留异常前完成的分项及完整失败耗时。所有方法使用相同独立校验。默认不开启模型方法。

本测试衡量已知 DAG 的在线调度成本，不评价自然语言规划质量。LangGraph 为应用循环，模型使用 Responses 函数调用；网络、模型及负载影响耗时。不由 token 推算价格。[函数调用文档](https://developers.openai.com/api/docs/guides/function-calling)。

## 发布与检查

```bash
/tmp/xstream-airflow-venv/bin/python benchmarks/orchestration/summarize_llm.py \
  .benchmark-runs/orchestration-with-llm/results.json
/tmp/xstream-airflow-venv/bin/python -m unittest discover -s benchmarks/orchestration -p 'test_*.py' -v
```

发布生成中英文报告、脱敏原始记录和六类双语 SVG：总耗时、首批数据延迟、中间文件量、成功次数、算子进程数、模型编排耗时分解。柱为成功样本中位数、误差线为最小至最大值；模型分解采用均值便于相加。另含三张 Mermaid 图说明混合语言工作流及文件/流交接机制。

Airflow 并行度为 8，Scheduler 心跳为 1 秒，DAG 刷新及最短处理间隔为 5 秒，控制端每 0.2 秒查询状态。调度与查询等待均计时；框架服务在整批测试中保持运行。
