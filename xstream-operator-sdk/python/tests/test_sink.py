"""Subprocess checks for the private Sink decision protocol and staged effects."""
import json
import os
from pathlib import Path
import socket
import struct
import subprocess
import sys
import tempfile
import unittest


FIXTURE = '''
import os, time
from pathlib import Path
from xstream_operator.sink import run_sink
root = Path(os.environ['TEST_SINK_ROOT'])
mode = os.environ['TEST_SINK_MODE']
def process(ctx):
    (root / 'staging').write_text('result')
    if mode == 'process_failed':
        raise RuntimeError('processing failed')
    if mode == 'cancel':
        while True:
            ctx._active()
            time.sleep(.01)
def complete(ctx):
    if mode == 'commit_failed':
        raise RuntimeError('commit failed')
    (root / 'staging').replace(root / 'published')
def discard(ctx, error):
    (root / 'discarded').write_text(str(error))
    if mode == 'discard_failed':
        raise RuntimeError('cleanup failed')
    (root / 'staging').unlink(missing_ok=True)
run_sink(process, complete, discard)
'''


def exact(stream, count):
    result = bytearray()
    while len(result) < count:
        part = stream.read(count - len(result))
        if not part:
            raise EOFError('truncated protocol')
        result.extend(part)
    return result


def receive(connection):
    with connection.makefile('rb', buffering=0) as stream:
        length, = struct.unpack('>I', exact(stream, 4))
        return json.loads(exact(stream, length))['state']


def decide(connection, state):
    raw = json.dumps({'state': state}).encode()
    connection.sendall(struct.pack('>I', len(raw)) + raw)


class SinkLifecycleTest(unittest.TestCase):
    def check_lifecycle(self, mode, decision, expected, success=False):
        with tempfile.TemporaryDirectory(prefix='sink-test-') as directory:
            root = Path(directory)
            (root / 'manifest.json').write_text('[]')
            (root / 'params.json').write_text('{}')
            env = dict(os.environ, XSTREAM_SINK_SOCKET=str(root / 'control.sock'),
                       XSTREAM_JOB_ID='job', XSTREAM_ATTEMPT_ID='attempt',
                       TEST_SINK_ROOT=directory, TEST_SINK_MODE=mode)
            sdk = str(Path(__file__).resolve().parents[1])
            env['PYTHONPATH'] = sdk + os.pathsep + env.get('PYTHONPATH', '')
            with socket.socket(socket.AF_UNIX) as server:
                server.bind(env['XSTREAM_SINK_SOCKET'])
                server.listen(1)
                server.settimeout(8)
                child = subprocess.Popen([sys.executable, '-c', FIXTURE, 'operator',
                                          directory, str(root / 'params.json')],
                                         env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                try:
                    connection, _ = server.accept()
                    with connection:
                        connection.settimeout(8)
                        self.assertEqual(receive(connection), 'ready')
                        if mode not in ('process_failed', 'cancel'):
                            size, payload = struct.unpack('>II', exact(child.stdout, 8))
                            self.assertEqual(json.loads(exact(child.stdout, size))['type'], 'sink_prepared')
                            self.assertEqual(payload, 0)
                            self.assertTrue((root / 'staging').exists())
                            self.assertFalse((root / 'published').exists())
                            self.assertIsNone(child.poll())
                        if decision:
                            decide(connection, decision)
                        self.assertEqual(receive(connection), expected)
                    _, stderr = child.communicate(timeout=8)
                    self.assertEqual(child.returncode == 0, success, stderr.decode())
                    self.assertEqual((root / 'published').exists(), success)
                    if expected == 'discarded':
                        self.assertTrue((root / 'discarded').exists())
                        self.assertFalse((root / 'staging').exists())
                    if expected == 'commit_failed':
                        self.assertFalse((root / 'discarded').exists())
                        self.assertTrue((root / 'staging').exists())
                finally:
                    if child.poll() is None:
                        child.kill()
                    child.communicate()

    def test_complete_only_after_job_decision(self):
        self.check_lifecycle('normal', 'complete', 'completed', success=True)

    def test_discard_staged_result(self):
        self.check_lifecycle('normal', 'discard', 'discarded')

    def test_process_failure_cleans(self):
        self.check_lifecycle('process_failed', None, 'discarded')

    def test_commit_failure_preserves_uncertain_result(self):
        self.check_lifecycle('commit_failed', 'complete', 'commit_failed')

    def test_discard_failure_is_reported(self):
        self.check_lifecycle('discard_failed', 'discard', 'discard_failed')

    def test_cancel_while_processing_cleans(self):
        self.check_lifecycle('cancel', 'discard', 'discarded')


if __name__ == '__main__':
    unittest.main()
