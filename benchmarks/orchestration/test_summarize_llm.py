import json
from pathlib import Path
import tempfile
import unittest
from summarize_llm import METHODS, publish


class ReportContracts(unittest.TestCase):
    def fixture(self):
        return {
            'timestamp': '2026-11-05T00:00:00Z',
            'config': {'rows': [1], 'workflows': ['pipeline'], 'methods': METHODS,
                       'warmups': 0, 'repeats': 1, 'output': '/private/payload', 'codex_home': '/private/codex'},
            'artifacts': {'parse': {'artifact_path': '/private/operator.jar'}},
            'model_configuration': {'model': 'test', 'provider': 'test', 'wire_api': 'responses', 'reasoning_effort': 'low'},
            'versions': {'langgraph': 'test', 'apache-airflow': 'test', 'pyarrow': 'test'},
            'samples': [{'workflow': 'pipeline', 'input_rows': 1, 'method': method,
                         'warmup': False, 'state': 'succeeded', 'wall_s': 1,
                         'first_data_s': .1, 'model_api_s': .2, 'tool_batches_s': .8,
                         'model_turns': 2, 'token_usage': {'input_tokens': 2, 'output_tokens': 1, 'total_tokens': 3}}
                        for method in METHODS],
        }

    def test_bilingual_reports_sanitize_paths_and_preserve_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root/'results.json'
            source.write_text(json.dumps(self.fixture()))
            original = source.read_bytes()
            self.assertEqual(publish(source, root/'docs'), 0)
            self.assertEqual(source.read_bytes(), original)
            raw = (root/'docs/benchmarks/orchestration-with-llm-20261105.json').read_text()
            self.assertNotIn('/private/', raw)
            self.assertIn('benchmark_source_sha256', json.loads(raw))
            self.assertTrue((root/'docs/orchestration-llm-benchmark.md').exists())
            self.assertTrue((root/'docs/orchestration-llm-benchmark_zh.md').exists())
            for name in ['wall-time','first-data','file-volume','completion','model-breakdown','process-count']:
                for suffix in ['', '_zh']:
                    self.assertIn('<svg', (root/f'docs/benchmarks/figures/{name}{suffix}.svg').read_text())

    def test_incomplete_batch_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            data = self.fixture()
            data['samples'].pop()
            source = root/'results.json'
            source.write_text(json.dumps(data))
            with self.assertRaisesRegex(ValueError, 'complete'):
                publish(source, root/'docs')

    def test_failure_is_retained_and_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            data = self.fixture()
            data['samples'][1]['state'] = 'failed'
            source = root/'results.json'
            source.write_text(json.dumps(data))
            self.assertEqual(publish(source, root/'docs'), 1)
            report = (root/'docs/orchestration-llm-benchmark.md').read_text()
            self.assertIn('with 1 failures', report)
            self.assertIn('| pipeline | 1 | 1.000 | — | 1.000 | 1.000 |', report)


if __name__ == '__main__':
    unittest.main()
