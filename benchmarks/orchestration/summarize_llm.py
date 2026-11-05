#!/usr/bin/env python3
"""Publish sanitized raw samples and bilingual reports for a complete model comparison."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import statistics

ROOT = Path(__file__).resolve().parents[2]
METHODS = ['airflow-file', 'llm-tools', 'xstream-isolated', 'xstream-fused']


def publish(source, destination):
    data = json.loads(source.read_text())
    config = data['config']
    expected = len(config['rows']) * len(config['workflows']) * len(config['methods']) * (config['warmups'] + config['repeats'])
    if len(data['samples']) != expected or not set(METHODS) <= set(config['methods']):
        raise ValueError('A complete four-configuration comparison is required')
    groups = {}
    for sample in data['samples']:
        if not sample['warmup'] and sample['state'] == 'succeeded':
            groups.setdefault((sample['workflow'], sample['input_rows'], sample['method']), []).append(sample)
    failures = sum(s['state'] != 'succeeded' for s in data['samples'])
    clean = copy.deepcopy(data)
    clean['config']['output'] = '<benchmark-output>'
    clean['config']['codex_home'] = '<local-codex-config>'
    for artifact in clean['artifacts'].values():
        artifact['artifact_path'] = Path(artifact['artifact_path']).name
    clean['benchmark_source_sha256'] = {str(p.resolve().relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in [Path(__file__), *[Path(__file__).parent / x for x in ['suite.py', 'llm_adapter.py', 'airflow_adapter.py', 'airflow_dags.py', 'plot_results.py', 'requirements.txt', 'src/main/scala/benchmarks/Host.scala']]]}
    destination.mkdir(parents=True, exist_ok=True)
    (destination/'benchmarks').mkdir(exist_ok=True)
    raw_name = 'benchmarks/orchestration-with-llm-'+data['timestamp'][:10].replace('-','')+'.json'
    (destination/raw_name).write_text(json.dumps(clean, indent=2)+'\n')
    from plot_results import render
    render(data, destination/'benchmarks'/'figures')
    for zh in [False, True]:
        title = '文件、模型工具编排与流传递实测' if zh else 'Measured file, model-tool and streaming orchestration'
        lines = ['# '+title, '', data['timestamp'], '']
        if zh:
            lines += [f'本次在同一批测试中交错执行四种配置：Airflow 文件传递、LangGraph 大模型工具编排、XStream 独立进程流传递、XStream 合并进程流传递。共 {expected} 次运行（含预热），失败 {failures} 次。三组工作负载为 CSV 筛选、双表 Join 和分组聚合。每个组合预热 {config["warmups"]} 次，正式运行 {config["repeats"]} 次。', '', '所有方式使用相同的 Scala/Python 算子、运行镜像和确定性气象观测数据，按独立 Python 实现校验最终输出的 SHA-256、字节数和行数。失败样本不进入中位数，保留在原始记录中。']
        else:
            lines += [f'Four configurations are interleaved in one test batch: Airflow file handoffs, LangGraph model-tool orchestration, XStream isolated-process streaming and XStream fused-process streaming. There are {expected} executions including warmups, with {failures} failures. Workloads are CSV filtering, two-table Join and grouped aggregation. Each combination has {config["warmups"]} warmups and {config["repeats"]} measured repetitions.', '', 'Every method uses the same Scala/Python operators, runtime images and deterministic meteorological observations. An independent Python implementation validates final SHA-256, length and row count. Failed trials are excluded from medians and retained in raw records.']
        suffix = '_zh' if zh else ''
        lines += ['', '## '+('工作流与执行机制' if zh else 'Workloads and execution mechanisms'), '',
            '```mermaid', 'flowchart LR',
            '  P[Scala CSV parser] --> Q[Python quality filter] --> W[Scala temperature filter] --> E[Scala CSV export]',
            '  O[Scala observations parser] --> J[Scala Join]',
            '  D[Scala stations parser] --> J --> F[Python filter] --> X[Scala CSV export]',
            '  A[Scala CSV parser] --> B[Python quality filter] --> S[Scala SQL aggregate] --> C[Scala CSV export]', '```', '',
            '```mermaid', 'flowchart TB',
            '  subgraph Files[Airflow / model tools]',
            '    T1[Operator process] --> IPC[Arrow IPC file] --> T2[Operator process]',
            '    Scheduler[Scheduler / model] -. Path metadata .-> T2', '  end',
            '  subgraph Streams[XStream]',
            '    R1[Scala process] -->|Arrow stream| R2[Python process] -->|Arrow stream| R3[Compatible Scala steps in one process]', '  end', '```', '',
            ('每条链是一组工作负载；Scala/Python 边界保留独立进程。' if zh else 'Each chain is one workload; Scala/Python boundaries retain separate processes.'), '',
            '```mermaid', 'flowchart LR', '  Scheduler[Airflow Scheduler] --> Executor[LocalExecutor] --> Task[Task process] --> Container[Scala or Python container]', '```', '',
            ('Scheduler 判断步骤何时就绪，LocalExecutor 在本机进程中执行任务；本测试的任务再启动对应算子容器。' if zh else 'The Scheduler decides when steps are ready. LocalExecutor executes tasks in local processes; each benchmark task launches its operator container.'), '',
            '## '+('实测图表' if zh else 'Measured figures'), '',
            ('柱为成功正式样本中位数，误差线为最小值到最大值，不是置信区间。失败次数单独展示。模型分解图采用均值以便相加。' if zh else 'Bars show successful measured medians; whiskers span minimum to maximum, not confidence intervals. Completion counts show failures separately. The additive model breakdown uses means.'), '']
        for figure, caption in [('wall-time', '总耗时' if zh else 'Job time'), ('first-data', '首批数据延迟' if zh else 'First data latency'), ('file-volume', '中间数据文件量' if zh else 'Intermediate file volume'), ('process-count', '算子进程数' if zh else 'Operator processes'), ('completion', '成功与失败次数' if zh else 'Completion counts'), ('model-breakdown', '模型编排耗时分解' if zh else 'Model orchestration breakdown')]:
            lines += [f'![{caption}](benchmarks/figures/{figure}{suffix}.svg)', '']
        lines += [('Airflow 使用真实 standalone 调度器与 LocalExecutor，调度状态采用本地 SQLite 测试数据库，XCom 只交换路径元信息，任务通过 Arrow IPC 文件交接数据；不是 dag.test()。' if zh else 'Airflow uses a real standalone scheduler and LocalExecutor with a local SQLite test database. XCom carries path metadata; tasks hand data over through Arrow IPC files. This is not dag.test().'), '']
        lines += [('Scala 算子版本为 2.0，Python piflow_filter 版本为 1.2；运行镜像及依赖摘要见原始记录。' if zh else 'Scala operators use version 2.0; Python piflow_filter uses version 1.2. Runtime image and dependency hashes are in the raw records.'), '']
        lines += ['', '## '+('模型配置与计时' if zh else 'Model configuration and timing'), '']
        lines += [('Airflow 并行度为 8，Scheduler 心跳为 1 秒，DAG 刷新和最短处理间隔为 5 秒，控制端每 0.2 秒查询完成状态；这些调度及查询等待均包含在作业耗时中。框架进程在整批测试期间保持运行。' if zh else 'Airflow parallelism is 8; Scheduler heartbeat is 1 second; DAG refresh and minimum processing intervals are 5 seconds. The controller polls completion every 0.2 seconds; scheduling and polling waits are timed. Framework services stay running throughout the batch.'), '']
        model = data['model_configuration']
        lines += [f'- 模型：`{model["model"]}`；提供商：`{model["provider"]}`；API：`{model["wire_api"]}`；推理强度：`{model["reasoning_effort"]}`。' if zh else f'- Model: `{model["model"]}`; provider: `{model["provider"]}`; API: `{model["wire_api"]}`; reasoning: `{model["reasoning_effort"]}`.', f'- [Airflow LocalExecutor](https://airflow.apache.org/docs/apache-airflow/stable/core-concepts/executor/local.html) · [Airflow XCom](https://airflow.apache.org/docs/apache-airflow/stable/core-concepts/xcoms.html)', f'- LangGraph {data["versions"]["langgraph"]}; Airflow {data["versions"]["apache-airflow"]}; PyArrow {data["versions"]["pyarrow"]}.', '']
        if zh:
            lines += ['模型配置从本机 Codex 配置只读加载，不复制凭据、请求头或服务 URL。报告只记录模型与提供商名称及推理强度。没有更换配置中的模型。', '', '模型通过 Responses 函数调用选择就绪步骤，并提交上游结果句柄。LangGraph 执行这些真实工具调用，再把结果句柄返回给模型；独立调用可以并行。模型不接收数据负载。固定 DAG 和算子参数与其他方法一致，所以测试的是执行编排，不是自然语言生成工作流的质量。', '', '大模型方法与 Airflow 复用同一个 Arrow 文件执行器。计时包括模型请求、网络与推理等待、LangGraph 循环、容器启动、实际计算和最终 DONE 响应。不会用模拟调用、缓存计划或硬编码下一步代替模型。API 不重试，轮数上限为 12。', '', '输入生成、算子/镜像下载和框架启动不计入作业耗时。保留系统热缓存，按记录的种子随机交错各方法。每个算子容器限制 1 GiB 内存和 128 个 PID。宿主为 16 逻辑 CPU、约 31.3 GiB 内存的共享虚拟机。', '', '## 总耗时', '', '秒，成功正式样本的中位数。', '', '| 工作负载 | 输入行数 | Airflow 文件 | 模型工具编排 | XStream 独立 | XStream 合并 |']
        else:
            lines += ['Model settings are read from local Codex configuration without copying credentials, request headers or service URLs. Only model/provider names and reasoning effort are recorded. The configured model is preserved.', '', 'The model selects ready steps through Responses function calls and supplies upstream result handles. LangGraph executes real tool calls and returns result handles. Independent calls may run concurrently. Data payloads never enter model context. The fixed DAG and operator parameters match the other methods: this measures execution orchestration, not natural-language workflow-generation quality.', '', 'Model tools and Airflow share the same Arrow-file executor. Timing includes API/network/reasoning waits, the LangGraph loop, container startup, computation and the final DONE response. No simulated calls, cached plan or hardcoded next-step fallback replaces the model. API retries are disabled; the response limit is 12.', '', 'Input generation, operator/image retrieval and framework startup are excluded. OS caches remain warm. Methods are randomly interleaved with a recorded seed. Each operator container has 1 GiB memory and 128 PIDs. The shared virtual host has 16 logical CPUs and approximately 31.3 GiB RAM.', '', '## Wall time', '', 'Seconds, median of successful measured samples.', '', '| Workload | Input rows | Airflow files | Model tools | XStream isolated | XStream fused |']
        lines += ['|---|---:|---:|---:|---:|---:|']
        keys = [(w,n) for w in config['workflows'] for n in config['rows']]
        def med(w,n,m,k):
            samples = groups.get((w,n,m), [])
            values = [s[k] for s in samples if s.get(k) is not None]
            return statistics.median(values) if values else None
        def fmt(v): return '—' if v is None else f'{v:.3f}'
        for w,n in keys:
            lines.append(f'| {w} | {n:,} | '+' | '.join(fmt(med(w,n,m,'wall_s')) for m in METHODS)+' |')
        lines += ['', '## '+('相对耗时与执行单元' if zh else 'Relative time and execution units'), '',
            ('加速比为 Airflow 文件方案中位数 / 对应 XStream 中位数；大于 1 表示本批测试中 XStream 更快。' if zh else 'Speedup is the Airflow-file median / the corresponding XStream median; greater than 1 means XStream was faster in this batch.'), '',
            ('| 工作负载 | 行数 | 独立流加速比 | 合并流加速比 |' if zh else '| Workload | Rows | Isolated stream speedup | Fused stream speedup |'), '|---|---:|---:|---:|']
        for w,n in keys:
            base=med(w,n,'airflow-file','wall_s')
            ratios=[base/value if base is not None and (value:=med(w,n,m,'wall_s')) else None for m in ['xstream-isolated','xstream-fused']]
            lines.append(f'| {w} | {n:,} | '+' | '.join(('—' if r is None else f'{r:.2f}×') for r in ratios)+' |')
        lines += ['', ('算子进程数由实际执行单元记录计算，不含 Airflow/模型/XStream 框架进程及宿主 read_item 数据源。合并受到语言及锁定依赖环境限制。' if zh else 'Operator process counts come from actual execution-unit records, excluding framework processes and host read_item sources. Fusion respects language and locked dependency environments.'), '']
        lines += [('| 工作负载 | 合并单元内的步骤 | 运行环境 |' if zh else '| Workload | Steps sharing an execution unit | Runtime |'), '|---|---|---|']
        for w,n in keys:
            samples=groups.get((w,n,'xstream-fused'),[])
            for unit in (samples[0].get('execution_units',[]) if samples else []):
                if unit.get('mode')=='shared_process':
                    lines.append(f'| {w} ({n:,}) | '+', '.join('`'+step+'`' for step in unit['steps'])+f' | {unit.get("runtime_executor", "—")} |')
        lines += ['', '## '+('模型运行成功率与波动' if zh else 'Model completion and variability'), '', ('| 工作负载 | 行数 | 成功/正式次数 | 成功耗时范围 s | 失败耗时 s |' if zh else '| Workload | Rows | Successful/measured | Successful range s | Failed wall times s |'), '|---|---:|---:|---:|---:|']
        for w,n in keys:
            attempted = [s for s in data['samples'] if s['workflow']==w and s['input_rows']==n and s['method']=='llm-tools' and not s['warmup']]
            good = [s['wall_s'] for s in attempted if s['state']=='succeeded']
            bad = [s for s in attempted if s['state']!='succeeded']
            span = f'{min(good):.3f}–{max(good):.3f}' if good else '—'
            failed_times = ', '.join(fmt(s.get('wall_s')) for s in bad) or '—'
            lines.append(f'| {w} | {n:,} | {len(good)}/{len(attempted)} | {span} | {failed_times} |')
        if failures:
            lines += ['', ('失败的模型运行仍保留完整运行耗时；其 API/工具分项只统计异常前已完成的响应和批次。成功样本不足三次的组，其耗时中位数应结合成功次数阅读。' if zh else 'Failed model runs retain total elapsed time; API/tool breakdowns only cover responses and batches completed before the error. Read medians alongside success counts when fewer than three samples succeed.')]
        lines += ['', '## '+('模型开销与用量' if zh else 'Model overhead and usage'), '', '| '+('工作负载 | 行数 | API 等待 s | 工具批次 s | 响应轮数 | 输入 token | 输出 token | 总 token' if zh else 'Workload | Rows | API wait s | Tool batches s | Responses | Input tokens | Output tokens | Total tokens')+' |', '|---|---:|---:|---:|---:|---:|---:|---:|']
        for w,n in keys:
            samples = groups.get((w,n,'llm-tools'), [])
            if samples:
                token = {k:statistics.median(s['token_usage'][k] for s in samples) for k in ['input_tokens','output_tokens','total_tokens']}
                lines.append(f'| {w} | {n:,} | {med(w,n,"llm-tools","model_api_s"):.3f} | {med(w,n,"llm-tools","tool_batches_s"):.3f} | {med(w,n,"llm-tools","model_turns"):g} | '+ ' | '.join(f'{token[k]:g}' for k in token)+' |')
        lines += ['', '## '+('首批结果延迟' if zh else 'Time to first final-output data'), '', ('| 工作负载 | 行数 | Airflow 秒 | 模型工具秒 | 独立进程秒 | 合并进程秒 |' if zh else '| Workload | Rows | Airflow s | Model tools s | Isolated s | Fused s |'), '|---|---:|---:|---:|---:|---:|']
        for w,n in keys:
            lines.append(f'| {w} | {n:,} | '+' | '.join(fmt(med(w,n,m,'first_data_s')) for m in METHODS)+' |')
        lines += ['', '## '+('解释与限制' if zh else 'Interpretation and limits'), '']
        if zh:
            lines += ['- 大模型方案能理解动态需求、选择工具，但本轮 DAG 已知，测量的是其在线调度成本；不能据此判断模型规划质量。', '- API 耗时受网络、提供商负载与模型影响。模型提供商可能缓存重复提示词，缓存 token 在原始数据中记录。token 用量不代表价格，未推算费用。用量表各列分别取中位数，因此不保证逐列相加等于总量中位数。', '- Airflow 并不强制文件传递。此处比较具体配置；文件执行器使用 Python/PyArrow，XStream 使用 JVM 协议，并非只替换传输介质的消融实验。', '- XStream 策略禁止物化且没有配置物化存储，步骤间没有数据文件。元数据和运行代码仍可写盘。SQL/Join 使用内存表。', '- 文件模式未开启 fsync，文件逻辑大小不能当作物理磁盘 I/O。本轮不测试强持久化、故障恢复、跨节点传输或资源峰值。', '- 三次正式重复与共享主机只能支持这组测量的结论；原始记录包含每次耗时、工具调用顺序、token 细项及内容摘要。', '', '## 复现', '', '- [程序、依赖、配置读取规则和运行命令](../benchmarks/orchestration/README_zh.md)', f'- [全部原始记录]({raw_name})']
        else:
            lines += ['- Model orchestration can interpret dynamic requests and choose tools. Here the DAG is known: the measurement is online scheduling cost, not planning quality.', '- API latency depends on network, provider load and model. Provider prompt caching may occur; cached tokens are recorded in raw data. Token usage is not pricing; no fee is inferred. Token columns are independent medians and need not sum to the median total.', '- Airflow does not require file handoffs. These are concrete configurations; the file executor uses Python/PyArrow while XStream uses JVM protocols. This is not a transport-only ablation.', '- XStream forbids materialization and has no materialization store, so steps do not exchange data files. Metadata and runtime code may still be written. SQL/Join use in-memory tables.', '- fsync is disabled. Logical file sizes are not physical disk I/O. Strong durability, recovery, cross-node transport and peak resource usage are not tested.', '- Three measured repetitions on a shared host only support the reported measurements. Raw records retain per-trial times, tool call order, token details and content hashes.', '', '## Reproduction', '', '- [Programs, dependencies, configuration resolution and commands](../benchmarks/orchestration/README.md)', f'- [All raw records]({raw_name})']
        if zh:
            labels={'Scala CSV parser':'Scala CSV 解析','Python quality filter':'Python 质量筛选','Scala temperature filter':'Scala 温度筛选','Scala CSV export':'Scala CSV 输出','Scala observations parser':'Scala 观测表解析','Scala stations parser':'Scala 站点表解析','Python filter':'Python 筛选','Scala SQL aggregate':'Scala SQL 聚合','Airflow / model tools':'Airflow / 模型工具','Operator process':'算子进程','Arrow IPC file':'Arrow IPC 文件','Scheduler / model':'调度器 / 模型','Path metadata':'路径元信息','Scala process':'Scala 进程','Python process':'Python 进程','Arrow stream':'Arrow 流','Compatible Scala steps in one process':'兼容 Scala 步骤共享进程','Task process':'任务进程','Scala or Python container':'Scala 或 Python 容器'}
            inside=False
            for i,line in enumerate(lines):
                if line=='```mermaid':inside=True
                elif line=='```':inside=False
                elif inside:
                    for before,after in labels.items():line=line.replace(before,after)
                    lines[i]=line
        (destination/('orchestration-llm-benchmark_zh.md' if zh else 'orchestration-llm-benchmark.md')).write_text('\n'.join(lines)+'\n')
    return failures


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('results', type=Path)
    parser.add_argument('--docs', type=Path, default=ROOT/'docs')
    args = parser.parse_args()
    failures = publish(args.results, args.docs)
    print(f'Published bilingual report; failed trials: {failures}')
    raise SystemExit(bool(failures))
