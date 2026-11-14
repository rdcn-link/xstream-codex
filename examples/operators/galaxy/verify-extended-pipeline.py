#!/usr/bin/env python3
"""Check public Galaxy adapters composed through a real XStream Job."""
import json
from pathlib import Path
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
JOB = 'job-extended-pipeline.yaml'
EXPECTED = 'seq1\t4\trun1\n'

def main():
    result = subprocess.run([
        'sudo', '-n', '-u', 'cnic', '-g', 'docker',
        str(ROOT / 'xstream-engine/target/universal/stage/bin/xstream-runner'),
        str(HERE / 'engine-extended.yaml'), str(HERE / JOB)
    ], cwd=ROOT, capture_output=True, timeout=180)
    assert result.returncode == 0, result.stderr.decode(errors='replace')[-6000:]
    actual = result.stdout.decode('utf-8')
    assert actual == EXPECTED, (actual, EXPECTED)
    (HERE / 'verification-extended-pipeline.json').write_text(json.dumps({
        'status': 'passed', 'job': JOB,
        'operators': ['item_source@1.0', 'galaxy_colorspace_to_fasta@1.0',
                      'galaxy_reverse_complement@1.0', 'galaxy_fasta_length@1.0',
                      'galaxy_add_input_name@1.0'],
        'expected_output': EXPECTED, 'output': actual
    }, indent=2) + '\n')
    print('PASS real five-step sequence pipeline:', repr(actual))

if __name__ == '__main__':
    main()
