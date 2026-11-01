#!/usr/bin/env python3
"""Contract checks; SCRIPT_CODE can point to a repository-built operator ZIP."""
import base64
import datetime
import importlib
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, os.environ.get('SCRIPT_CODE', str(HERE / 'src')))
# The installed Runtime SDK supplies this import during packaged container tests.
if 'SCRIPT_CODE' not in os.environ:
    sys.path.insert(0, str(HERE.parents[2] / 'xstream-operator-sdk/python'))
import pyarrow as pa
from xstream_operator import OperatorContext, descriptor, sample_records, sample_bytes
from xstream_operator.inference import evaluate_inference
from script_support import lines, MAX_LINE_BYTES


def module(name):
    return importlib.import_module('script_' + name)


def execute(name, source, params=None, chunk_size=1):
    events = []
    representation = 'bytes' if isinstance(source, bytes) else 'records'
    table = pa.table({'data': pa.array([source[i:i + chunk_size] for i in range(0, len(source), chunk_size)], type=pa.binary())}) if representation == 'bytes' else source
    with tempfile.TemporaryDirectory() as temporary:
        path = Path(temporary)
        with (path / 'source.arrow').open('wb') as stream:
            with pa.ipc.new_stream(stream, table.schema) as writer:
                writer.write_table(table, max_chunksize=1)
        (path / 'manifest.json').write_text(json.dumps([dict(port='source', file='source.arrow', representation=representation, format='arrow')]))
        (path / 'params.json').write_text(json.dumps(params or {}))
        with OperatorContext(['operator', temporary, str(path / 'params.json')], emit=lambda h, p: events.append((h, p))) as ctx:
            module(name).process(ctx)
    assert events[0][0]['type'] == 'start' and events[-1][0]['type'] == 'end'
    return b''.join(payload for header, payload in events if header['type'] == 'data')


class ScriptsTest(unittest.TestCase):
    def test_utf8_lines_and_matches_cross_every_chunk_boundary(self):
        raw = '气象站温度\r\n气象站湿度\n末行气象站'.encode()
        for size in range(1, len(raw) + 1):
            self.assertEqual(execute('text_replace', raw, {'find': '气象站', 'replacement': '站点'}, size), '站点温度\r\n站点湿度\n末行站点'.encode())
        self.assertEqual(execute('text_replace', b'aaaa', {'find': 'aa'}), b'')

    def test_line_number_preserves_endings_and_empty_lines(self):
        self.assertEqual(execute('line_number', b'A\r\n\nB', {'start': 10, 'separator': ': '}), b'10: A\r\n11: \n12: B')
        self.assertEqual(execute('line_number', b''), b'')

    def test_lines_are_incremental_and_bounded(self):
        class Source:
            def bytes(self):
                yield b'first\n'
                raise RuntimeError('next input was requested')
        iterator = lines(Source())
        self.assertEqual(next(iterator), 'first\n')
        with self.assertRaisesRegex(RuntimeError, 'next input'):
            next(iterator)
        class Large:
            def bytes(self):
                yield b'x' * (MAX_LINE_BYTES + 1)
        with self.assertRaisesRegex(ValueError, '1 MiB'):
            list(lines(Large()))

    def test_jsonl_projection_order_missing_and_nested_values(self):
        raw = '\n{"name":"东站","value":12,"extra":0}\r\n{"name":"西站","value":{"a":[1,2]}}'.encode()
        expected = '{"value":12,"name":"东站","missing":null}\n{"value":{"a":[1,2]},"name":"西站","missing":null}\n'.encode()
        self.assertEqual(execute('jsonl_project', raw, {'fields': ['value', 'name', 'missing']}), expected)
        self.assertEqual(execute('jsonl_project', b'', {'fields': ['name']}), b'')

    def test_jsonl_invalid_data_is_rejected(self):
        for raw in (b'[1,2]\n', b'{"x":NaN}', b'{"x":1,"x":2}', b'{oops}', b'{"x":1e999}'):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                execute('jsonl_project', raw, {'fields': ['x']})
        with self.assertRaisesRegex(ValueError, 'missing'):
            execute('jsonl_project', b'{"x":1}', {'fields': ['y'], 'missing': 'error'})
        with self.assertRaises(UnicodeDecodeError):
            execute('line_number', b'\xff\n')

    def test_records_jsonl_null_nested_and_special_types(self):
        table = pa.table({'station': ['东站', None], 'values': [[1, 2], []], 'date': [datetime.date(2026, 1, 2), None], 'blob': [b'ab', None]})
        rows = [json.loads(line) for line in execute('records_to_jsonl', table).splitlines()]
        self.assertEqual(rows, [{'station': '东站', 'values': [1, 2], 'date': '2026-01-02', 'blob': 'YWI='}, {'station': None, 'values': [], 'date': None, 'blob': None}])
        self.assertEqual(execute('records_to_jsonl', table.slice(0, 0)), b'')
        with self.assertRaises(ValueError):
            execute('records_to_jsonl', pa.table({'x': [float('inf')]}))
        with self.assertRaisesRegex(ValueError, '1 MiB'):
            execute('records_to_jsonl', pa.table({'x': ['x' * MAX_LINE_BYTES]}))

    def test_invalid_parameters_and_input_views(self):
        for name, params in [('text_replace', {'find': ''}), ('text_replace', {'find': '\n'}), ('line_number', {'start': True}), ('line_number', {'unexpected': 1}), ('jsonl_project', {'fields': ['x', 'x']}), ('jsonl_project', {'fields': []}), ('records_to_jsonl', {'path': 'x'})]:
            with self.subTest(name=name, params=params), self.assertRaises(ValueError):
                module(name).validate(params)
        with self.assertRaises(ValueError):
            module('line_number').infer_outputs({'source': descriptor(['records'])}, {})

    def test_inference_and_bounded_samples_for_all_scripts(self):
        cases = [('text_replace', descriptor(['bytes'], ['text'], certainty='known', sample=sample_bytes('气象\n'.encode())), {'find': '气象', 'replacement': '天气'}),
                 ('line_number', descriptor(['bytes'], ['text'], certainty='known', sample=sample_bytes(b'A\nB\n')), {}),
                 ('jsonl_project', descriptor(['bytes'], ['jsonl'], certainty='known', sample=sample_bytes(b'{"x":1,"y":2}\n')), {'fields': ['x']}),
                 ('records_to_jsonl', descriptor(['records'], ['arrow'], pa.schema([('x', pa.int64())]), 'known', sample=sample_records([{'x': 1}, {'x': 2}])), {})]
        for name, source, params in cases:
            m = module(name)
            value = json.loads(evaluate_inference(m.infer_outputs, json.dumps({'inputs': {'source': source}, 'params': params, 'preview': {'max_rows': 2, 'max_bytes': 1024}}).encode(), m.preview_outputs))
            self.assertFalse(value['diagnostics'], value)
            output = value['outputs']['result']
            self.assertEqual(output['certainty'], 'known')
            sample = output['sample']
            self.assertLessEqual(len(json.dumps(sample).encode()), 1024)
            self.assertTrue(base64.b64decode(sample['data_base64']))


if __name__ == '__main__':
    unittest.main()
