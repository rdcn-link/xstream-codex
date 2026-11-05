import json
from pathlib import Path
import tempfile
import time
import unittest
import httpx
from llm_adapter import AgentFailure, CodexResponses, make_adapter
import suite


class FakeAPI:
    def __init__(self, batches):
        self.batches = iter(batches)
        self.history = []

    def create(self, history, tool):
        self.history.append(history)
        output = [{'type': 'function_call', 'name': 'execute_step', 'call_id': s['id'],
                   'arguments': json.dumps({'step_id': s['id'], 'inputs': s['inputs']})}
                  for s in next(self.batches)]
        return {'output': output, 'usage': {'input_tokens': 10, 'output_tokens': 2, 'total_tokens': 12}}, .01


class FakeExecutor:
    def __init__(self):
        self.calls = []

    def run(self, step, inputs):
        self.calls.append(step['id'])
        if step['id'] == 'export':
            return {'sha256': 'test', 'bytes': 1, 'rows': 0, 'first_data_at': time.perf_counter()}
        return {'path': step['id']}


class ModelContracts(unittest.TestCase):
    def test_parallel_ready_steps_and_results_roundtrip(self):
        plan = suite.workflow('join')
        api = FakeAPI([plan[:2], [plan[2]], [plan[3]], [plan[4]], []])
        executor = FakeExecutor()
        result = make_adapter(api)(plan, {'observations': {}, 'dimension': {}}, executor)
        self.assertEqual(set(executor.calls), {s['id'] for s in plan})
        self.assertEqual(result['model_turns'], 5)
        self.assertEqual(result['token_usage']['total_tokens'], 60)
        self.assertEqual(result['sha256'], 'test')
        self.assertEqual(sum(x['type'] == 'function_call_output' for x in api.history[-1] if 'type' in x), 5)

    def test_dependency_violation_is_not_executed(self):
        plan = suite.workflow('pipeline')
        api = FakeAPI([[plan[1]]])
        executor = FakeExecutor()
        with self.assertRaisesRegex(AgentFailure, 'dependencies') as caught:
            make_adapter(api)(plan, {'observations': {}}, executor)
        self.assertEqual(executor.calls, [])
        self.assertEqual(caught.exception.metrics['model_turns'], 1)

    def test_early_model_completion_fails(self):
        with self.assertRaisesRegex(AgentFailure, 'before all steps'):
            make_adapter(FakeAPI([[]]))(suite.workflow('pipeline'), {}, FakeExecutor())

    def test_codex_secrets_not_in_metadata_or_api_errors(self):
        with tempfile.TemporaryDirectory() as raw:
            p = Path(raw)
            secret = 'test-secret-never-log'
            (p/'config.toml').write_text('model="test"\nmodel_provider="test"\n[model_providers.test]\nwire_api="responses"\nbase_url="https://example.invalid/v1"\n')
            (p/'auth.json').write_text(json.dumps({'OPENAI_API_KEY': secret}))
            api = CodexResponses(p)
            self.assertNotIn(secret, json.dumps(api.metadata))
            api.client.close()
            api.client = httpx.Client(base_url='https://example.invalid/v1/', transport=httpx.MockTransport(lambda request: httpx.Response(401, text=secret)))
            try:
                with self.assertRaisesRegex(RuntimeError, 'HTTP 401') as caught:
                    api.create([], {})
                self.assertNotIn(secret, str(caught.exception))
            finally:
                api.close()


if __name__ == '__main__':
    unittest.main()
