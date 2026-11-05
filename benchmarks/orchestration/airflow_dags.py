"""Real Airflow DAGs: each task invokes one language-specific container."""
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
from airflow.sdk import DAG, task, get_current_context
from suite import workflow, Ledger, FileExecutor


@task(retries=0, execution_timeout=timedelta(minutes=30))
def execute_operator(step, inputs):
    config = get_current_context()['dag_run'].conf
    resolved = {port: config['inputs'][value['initial']] if 'initial' in value else value for port,value in inputs.items()}
    ledger = Ledger()
    executor = FileExecutor(config['artifacts'], [], Path(config['trial']), ledger, config['fsync'])
    result = executor.run(step, resolved)
    (executor.directory/step['id']/'metrics.json').write_text(json.dumps({'result':result,'detail':ledger.step_details[0]}))
    # Intermediate values are path/representation/format metadata only, never data payloads.
    return result


def build(name):
    with DAG(dag_id='xstream_benchmark_'+name, schedule=None, catchup=False,
             start_date=datetime(2025,1,1,tzinfo=timezone.utc), max_active_runs=1,
             max_active_tasks=8, is_paused_upon_creation=False, tags=['xstream-benchmark']) as dag:
        handles = {'observations':{'initial':'observations'},'dimension':{'initial':'dimension'}}
        for step in workflow(name):
            handles[step['id']] = execute_operator.override(task_id=step['id'])(step, {p:handles[h] for p,h in step['inputs'].items()})
    return dag


for workload in ('pipeline','join','aggregate'):
    globals()['benchmark_'+workload] = build(workload)
