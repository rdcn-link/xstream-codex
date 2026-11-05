"""Small contract checks; the end-to-end suite supplies cross-engine validation."""
import hashlib
import io
import tempfile
import unittest
from pathlib import Path
import pyarrow as pa
import yaml
import suite


class Contracts(unittest.TestCase):
    def test_binary_chunks_preserve_bytes(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            content = bytes(range(256)) * 1025
            (root / 'source').write_bytes(content)
            suite.bytes_ipc(root / 'source', root / 'output')
            with pa.ipc.open_stream(root / 'output') as reader:
                chunks = [chunk.as_py() for batch in reader for chunk in batch.column(0)]
            self.assertGreater(len(chunks), 1)
            self.assertTrue(all(len(chunk) <= 65536 for chunk in chunks))
            self.assertEqual(b''.join(chunks), content)

    def test_independent_oracle_empty_and_filter(self):
        header = (','.join(suite.FIELDS) + '\n').encode()
        self.assertEqual(suite.oracle('pipeline', 1), {
            'sha256': hashlib.sha256(header).hexdigest(), 'bytes': len(header), 'rows': 0})
        self.assertEqual(suite.oracle('join', 1000)['rows'], 900)
        self.assertEqual(suite.oracle('aggregate', 1000)['rows'], 900)

    def test_jobs_reference_only_step_outputs(self):
        with tempfile.TemporaryDirectory() as raw:
            path = Path(raw) / 'job.yaml'
            for name in ('pipeline', 'join', 'aggregate'):
                suite.write_job(name, Path('1000'), path)
                job = yaml.safe_load(path.read_text())
                defined = set()
                for step in job['steps']:
                    self.assertTrue(set(step['inputs'].values()) <= defined)
                    defined.add(step['id'])
                self.assertIn(job['outputs']['result'], defined)

    def test_workloads_have_mixed_language_operator_versions(self):
        for name in ('pipeline','join','aggregate'):
            versions={suite.OP_VERSIONS[s['kind']] for s in suite.workflow(name)}
            self.assertEqual(versions, {'1.2','2.0'})

    def test_truncated_protocol_fails(self):
        with self.assertRaises(EOFError):
            suite.read_exact(io.BytesIO(b'12'), 3)


if __name__ == '__main__':
    unittest.main()
