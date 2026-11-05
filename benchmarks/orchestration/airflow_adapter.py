"""Dedicated Airflow standalone/LocalExecutor instance; no dag.test shortcuts."""
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import sys
import time
import uuid
import httpx


class AirflowHost:
    def __init__(self, directory):
        self.root = Path(directory).resolve()
        self.root.mkdir(parents=True)
        dags = self.root/'dags'
        dags.mkdir()
        shutil.copy2(Path(__file__).with_name('airflow_dags.py'),dags/'benchmark_dags.py')
        with socket.socket() as sock:
            sock.bind(('127.0.0.1',0))
            port = sock.getsockname()[1]
        self.url=f'http://127.0.0.1:{port}'
        self.environment = dict(os.environ)
        self.environment['PATH']=str(Path(sys.executable).parent)+os.pathsep+self.environment.get('PATH','')
        self.environment.update(AIRFLOW_HOME=str(self.root), AIRFLOW__CORE__LOAD_EXAMPLES='false',
            AIRFLOW__CORE__EXECUTOR='LocalExecutor', AIRFLOW__CORE__PARALLELISM='8',
            AIRFLOW__CORE__SIMPLE_AUTH_MANAGER_ALL_ADMINS='true',
            AIRFLOW__API__HOST='127.0.0.1',AIRFLOW__API__PORT=str(port),AIRFLOW__API__WORKERS='1',
            AIRFLOW__CORE__EXECUTION_API_SERVER_URL=self.url+'/execution/',
            AIRFLOW__DAG_PROCESSOR__REFRESH_INTERVAL='5', AIRFLOW__DAG_PROCESSOR__MIN_FILE_PROCESS_INTERVAL='5',
            AIRFLOW__SCHEDULER__SCHEDULER_HEARTBEAT_SEC='1', AIRFLOW__LOGGING__LOGGING_LEVEL='WARNING',
            AIRFLOW__CORE__FERNET_KEY='', PYTHONPATH=str(Path(__file__).parent.resolve()))
        self.log=(self.root/'service.log').open('wb')
        started=time.perf_counter()
        self.process=subprocess.Popen([str(Path(sys.executable).with_name('airflow')),'standalone'],env=self.environment,
            stdout=self.log,stderr=subprocess.STDOUT,start_new_session=True)
        self.client=httpx.Client(base_url=self.url,timeout=30,trust_env=False)
        try:
            while time.perf_counter()-started<180:
                if self.process.poll() is not None:
                    raise RuntimeError('Airflow exited during startup; see its service.log')
                try:
                    token=self.client.get('/auth/token')
                    if token.status_code==201:
                        self.client.headers['Authorization']='Bearer '+token.json()['access_token']
                        response=self.client.get('/api/v2/dags',params={'limit':100})
                        if response.status_code==200 and len([d for d in response.json()['dags'] if d['dag_id'].startswith('xstream_benchmark_')])==3:
                            break
                except (httpx.HTTPError,ValueError,KeyError):pass
                time.sleep(.5)
            else:raise RuntimeError('Airflow DAG discovery timeout; see service.log')
            self.startup_s=time.perf_counter()-started
        except BaseException:
            self.close()
            raise

    def run(self, name, plan, inputs, executor):
        # Refresh local-only short-lived credentials; no password is stored in results.
        token=self.client.get('/auth/token');token.raise_for_status()
        self.client.headers['Authorization']='Bearer '+token.json()['access_token']
        dag='xstream_benchmark_'+name
        run_id='manual__benchmark_'+uuid.uuid4().hex
        payload={'dag_run_id':run_id,'logical_date':None,'conf':{'trial':str(executor.directory),
            'inputs':inputs,'artifacts':executor.artifacts,'fsync':executor.fsync}}
        response=self.client.post(f'/api/v2/dags/{dag}/dagRuns',json=payload)
        if response.status_code not in (200,201):
            raise RuntimeError(f'Airflow trigger failed HTTP {response.status_code}: '+response.text[:500])
        deadline=time.monotonic()+1800
        while time.monotonic()<deadline:
            response=self.client.get(f'/api/v2/dags/{dag}/dagRuns/{run_id}')
            response.raise_for_status()
            state=response.json()['state']
            if state=='success':break
            if state=='failed':raise RuntimeError('Airflow DAG failed; see task logs in '+str(self.root/'logs'))
            time.sleep(.2)
        else:raise TimeoutError('Airflow DAG run exceeded 1800 seconds')
        for step in plan:
            metrics=json.loads((executor.directory/step['id']/'metrics.json').read_text())
            executor.ledger.add(metrics['detail'])
            if step['id']=='export':result=metrics['result']
        return result|{'airflow_dag_id':dag,'airflow_run_id':run_id,'airflow_state':'success'}

    def close(self):
        if self.process.poll() is None:
            os.killpg(self.process.pid,signal.SIGTERM)
            try:self.process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(self.process.pid,signal.SIGKILL);self.process.wait()
        self.client.close()
        self.log.close()
