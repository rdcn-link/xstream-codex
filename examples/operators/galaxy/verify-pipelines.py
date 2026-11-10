#!/usr/bin/env python3
"""Verify public multi-package Galaxy compositions through real engine jobs."""
import json,subprocess,tempfile
from pathlib import Path
HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
CASES={
 'job-vcf-dosage-pipeline.yaml': 'CHROM\tPOS\tREF\tALT\tA\tB\n1\t10\tA\tG\t1\t0\n',
 'job-dna-word-pipeline.yaml': 'sequence\tword\tcount\tfraction\nx\tAT\t1\t0.333333\nx\tTT\t2\t0.666667\n',
 'job-codon-projection-pipeline.yaml': 'sequence\tcodons\tcai\ngene\t2\t0.707107\n',
 'job-joined-regression-pipeline.yaml': 'term\tcoefficient\nintercept\t1.000000\ncolumn_1\t2.000000\nr_squared\t1.000000\n',
 'job-extended-pipeline.yaml':'seq1\t4\trun1\n',
 'job-fastq-quality-pipeline.yaml':'NC\n',
 'job-interval-coverage-pipeline.yaml':'chr1\t0\t4\t1\nchr1\t9\t10\t1\nchr2\t1\t4\t1\n'
}
def main():
 results=[]
 with tempfile.NamedTemporaryFile(mode='w',suffix='.yaml') as config:
  config.write((HERE/'engine-extended.yaml').read_text().replace('.xstream-executions-galaxy-extended','.xstream-executions-galaxy-pipelines'));config.flush()
  verify(config.name,results)
 (HERE/'verification-pipelines.json').write_text(json.dumps(dict(status='passed',pipelines=results),indent=2)+'\n')
def verify(config,results):
 for name,expected in CASES.items():
  result=subprocess.run(['sudo','-n','-u','cnic','-g','docker',str(ROOT/'xstream-engine/target/universal/stage/bin/xstream-runner'),config,str(HERE/name)],cwd=ROOT,capture_output=True,timeout=180)
  assert result.returncode==0,(name,result.stderr.decode(errors='replace')[-6000:])
  actual=result.stdout.decode();assert actual==expected,(name,actual,expected)
  results.append(dict(job=name,status='passed',expected=expected,output=actual));print('PASS public multi-step pipeline',name,flush=True)
if __name__=='__main__':main()
