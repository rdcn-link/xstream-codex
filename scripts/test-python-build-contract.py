#!/usr/bin/env python3
"""Regression checks for compile-time preview registration without executing sources."""
import ast
import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
RESOURCE = ROOT / 'plugins/xstream-plugin-source-analysis/src/main/resources/xstream-python-build.py'
spec = importlib.util.spec_from_file_location('operator_build', RESOURCE)
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


class PythonBuildContract(unittest.TestCase):
    def test_missing_registration_fails_before_execution(self):
        for call in ['run_operator(process, infer_outputs)',
                     'run_operator(process, infer_outputs, preview_outputs=None)',
                     'run_inference(infer_outputs)',
                     'run_inference(infer_outputs, preview=None)',
                     'start(process, infer_outputs)',
                     'sdk.run_operator(process, infer_outputs)']:
            source = 'from xstream_operator import run_operator, run_inference, run_operator as start\nimport xstream_operator as sdk\n' + call
            with self.subTest(call=call), self.assertRaisesRegex(ValueError, 'example.py:3:.*requires explicit'):
                builder.validate_preview_registration(ast.parse(source), 'example.py')

    def test_explicit_registration_supports_aliases_and_bounded_unavailability(self):
        source = '''from xstream_operator import run_operator as start, run_inference as infer
import xstream_operator as sdk
def preview_outputs(inputs, params, limits):
    return {}
start(process, infer_outputs, preview_outputs=preview_outputs)
infer(infer_outputs, preview=preview_outputs)
sdk.run_operator(process, infer_outputs, preview_outputs=preview_outputs)
'''
        builder.validate_preview_registration(ast.parse(source), 'example.py')

    def test_pure_templates_can_supply_inherited_preview(self):
        builder.validate_preview_registration(ast.parse('''from xstream_operator import BytesOperator
class Example(BytesOperator):
    def transform(self, data, params): return data
Example().main()
'''), 'example.py')

    def test_compile_does_not_execute_sources_or_trust_build_metadata(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / 'src'
            root.mkdir()
            (root / 'example.py').write_text("raise RuntimeError('must never execute publisher code during build')\n")
            (root / '.dacp-build.json').write_text('{"spoofed":true}')
            output = Path(tmp) / 'operator.zip'
            builder.build(root, output)
            with zipfile.ZipFile(output) as archive:
                self.assertIn('example.pyc', archive.namelist())
                self.assertEqual(archive.read('.dacp-build.json'), b'{"python_process_modules": []}')


if __name__ == '__main__':
    unittest.main()
