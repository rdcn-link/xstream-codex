"""LangGraph + Responses function calling. Credentials are loaded only at runtime."""
import concurrent.futures
import json
import os
from pathlib import Path
import time
import tomllib
import httpx


class AgentFailure(RuntimeError):
    def __init__(self, cause, metrics):
        super().__init__(str(cause))
        self.metrics = metrics


class CodexResponses:
    def __init__(self, directory):
        directory = Path(directory).expanduser()
        config = tomllib.loads((directory / 'config.toml').read_text())
        provider = config.get('model_providers', {}).get(config.get('model_provider'), {})
        if provider.get('wire_api', 'responses') != 'responses':
            raise ValueError('This benchmark requires a Responses API provider')
        self.model = config.get('model')
        if not self.model:
            raise ValueError('Codex model is missing')
        auth_path = directory / 'auth.json'
        auth = json.loads(auth_path.read_text()) if auth_path.exists() else {}
        key = provider.get('experimental_bearer_token') or os.environ.get(provider.get('env_key', ''),'') or auth.get('OPENAI_API_KEY')
        if not key:
            raise ValueError('No API credential found in Codex configuration')
        self.secret = key
        headers = dict(provider.get('http_headers', {}))
        headers['Authorization'] = 'Bearer ' + key
        self.client = httpx.Client(base_url=provider.get('base_url', 'https://api.openai.com/v1').rstrip('/')+'/', headers=headers, timeout=180)
        self.reasoning = config.get('model_reasoning_effort', 'low')
        self.metadata = {'model': self.model, 'provider': config.get('model_provider', 'openai'), 'wire_api': 'responses', 'reasoning_effort': self.reasoning, 'framework': 'LangGraph', 'max_model_turns': 12, 'api_retries': 0}

    def create(self, history, tool):
        started = time.perf_counter()
        try:
            response = self.client.post('responses', json={'model': self.model, 'input': history, 'tools': [tool], 'parallel_tool_calls': True, 'reasoning': {'effort': self.reasoning}, 'store': False})
        except httpx.HTTPError:
            raise RuntimeError('Model API transport failed') from None
        if response.status_code != 200:
            # Never include response bodies or request headers: gateways can echo credentials.
            raise RuntimeError(f'Model API returned HTTP {response.status_code}')
        try:
            result = response.json()
        except ValueError:
            raise RuntimeError('Model API did not return JSON') from None
        if result.get('status') not in (None, 'completed') or result.get('error'):
            raise RuntimeError('Model API response was incomplete or failed')
        return result, time.perf_counter() - started

    def close(self):
        self.client.close()


def make_adapter(api):
    from langgraph.graph import StateGraph, START, END
    from typing import TypedDict

    class State(TypedDict):
        history: list
        pending: list
        done: bool

    def run(plan, inputs, executor):
        available = dict(inputs)
        by_id = {s['id']: s for s in plan}
        completed = set()
        calls = []
        turns = []
        usage = {'input_tokens': 0, 'output_tokens': 0, 'total_tokens': 0, 'cached_input_tokens': 0, 'reasoning_output_tokens': 0}
        final_result = None
        model_s = 0.0
        tool_s = 0.0
        started = time.perf_counter()
        tool = {'type': 'function', 'name': 'execute_step', 'description': 'Execute one step of the supplied DAG using its declared Scala operator and parameters. All dependencies must have completed. Return opaque output handle; never inspect data.', 'strict': False, 'parameters': {'type': 'object', 'properties': {'step_id': {'type': 'string', 'enum': list(by_id)}, 'inputs': {'type': 'object', 'additionalProperties': {'type': 'string'}, 'description': 'Map each declared input port to its completed upstream output handle.'}}, 'required': ['step_id', 'inputs'], 'additionalProperties': False}}
        prompt = ('Execute this complete fixed data-analysis DAG by calling execute_step. You are the runtime scheduler, not a planner: preserve every declared step and parameter; do not add, omit or repeat steps. Input handles initially available: '+json.dumps(list(inputs))+'. A successful step returns its step_id as the output handle. Call independent ready steps in parallel where possible. Never call a downstream step before receiving its input handles. After export completes, respond only DONE. DAG: '+json.dumps(plan))

        def model(state):
            nonlocal model_s
            if len(turns) >= 12:
                raise RuntimeError('Model turn budget exhausted')
            response, duration = api.create(state['history'], tool)
            model_s += duration
            u = response.get('usage') or {}
            for k in ['input_tokens','output_tokens','total_tokens']:
                usage[k] += u.get(k, 0)
            usage['cached_input_tokens'] += (u.get('input_tokens_details') or {}).get('cached_tokens', 0)
            usage['reasoning_output_tokens'] += (u.get('output_tokens_details') or {}).get('reasoning_tokens', 0)
            output = response.get('output', [])
            pending = [x for x in output if x.get('type') == 'function_call']
            turns.append({'wall_s': duration, 'tool_calls': len(pending), 'usage': u})
            if not pending and len(completed) != len(plan):
                raise RuntimeError('Model finished before all steps completed')
            return {'history': state['history'] + output, 'pending': pending, 'done': not pending}

        def tools(state):
            nonlocal final_result, tool_s
            begin = time.perf_counter()
            batch = []
            seen = set()
            for call in state['pending']:
                if call.get('name') != 'execute_step':
                    raise ValueError('Unknown model tool')
                args = json.loads(call['arguments'])
                sid = args.get('step_id')
                if sid not in by_id or sid in completed or sid in seen:
                    raise ValueError('Invalid or repeated model step')
                s = by_id[sid]
                if args.get('inputs') != s['inputs'] or any(h not in available for h in s['inputs'].values()):
                    raise ValueError('Model violated DAG input dependencies')
                seen.add(sid)
                batch.append((call, s, {p: available[h] for p,h in s['inputs'].items()}))
            outputs = []
            with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
                futures = [pool.submit(executor.run, s, ins) for _,s,ins in batch]
                for (call,s,_), future in zip(batch, futures):
                    result = future.result()
                    completed.add(s['id'])
                    if s['id'] == 'export':
                        final_result = result
                    else:
                        available[s['id']] = result
                    calls.append({'step_id': s['id'], 'inputs': s['inputs']})
                    outputs.append({'type': 'function_call_output', 'call_id': call['call_id'], 'output': json.dumps({'state': 'succeeded', 'output_handle': s['id']})})
            tool_s += time.perf_counter() - begin
            return {'history': state['history'] + outputs, 'pending': [], 'done': False}

        graph = StateGraph(State)
        graph.add_node('model', model)
        graph.add_node('tools', tools)
        graph.add_edge(START, 'model')
        graph.add_conditional_edges('model', lambda s: END if s['done'] else 'tools')
        graph.add_edge('tools', 'model')
        def metrics():
            return {'model_api_s': model_s, 'tool_batches_s': tool_s, 'model_turns': len(turns), 'tool_calls': calls, 'token_usage': usage, 'model_turn_details': turns, 'agent_wall_s': time.perf_counter()-started}
        try:
            graph.compile().invoke({'history': [{'role': 'user', 'content': prompt}], 'pending': [], 'done': False}, {'recursion_limit': 30})
            if final_result is None:
                raise RuntimeError('No final output from model tools')
            return final_result | metrics()
        except Exception as error:
            raise AgentFailure(error, metrics()) from None
    return run
