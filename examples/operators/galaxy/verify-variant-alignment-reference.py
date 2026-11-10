#!/usr/bin/env python3
"""Replay preserved BCFtools/samtools oracle outputs; compare adapters with documented normalization."""
import argparse,json,subprocess,tempfile
from pathlib import Path
HERE=Path(__file__).resolve().parent

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--bcftools',type=Path,required=True);p.add_argument('--samtools',type=Path,required=True);p.add_argument('--report',type=Path,default=HERE/'verification-variant-alignment-reference.json');a=p.parse_args()
 evidence=json.loads((HERE/'upstream-variant-alignment-cases.json').read_text());results=[]
 for i,c in enumerate(evidence['cases']):
  family=c['tool'].split()[0];binary=getattr(a,family).resolve()
  with tempfile.TemporaryDirectory(prefix='galaxy-native-oracle-') as td:
   root=Path(td);source=root/'input';source.write_text(c['data']);(root/'ref.fa').write_text('>chr1\nTACGAAAAAA\n')
   cmd=[str(binary)]+[str(source) if v=='<input>' else str(root/'ref.fa') if v=='<ref.fa>' else v for v in c['command']]
   out=subprocess.run(cmd,capture_output=True,check=True,timeout=30).stdout.decode()
   def stable(text):return '\n'.join(v for v in text.splitlines() if not v.startswith('@PG\t'))
   assert stable(out)==stable(c['native_output']),(i,c['operator'],out,c['native_output'])
  results.append(dict(case=i,operator=c['operator'],status='passed',normalization=c.get('normalization','Record/header formatting is compared separately where documented.')))
 a.report.write_text(json.dumps(dict(status='passed',case_count=len(results),references=evidence['references'],cases=results),indent=2)+'\n');print('PASS',len(results),'native variant/alignment oracle cases')
if __name__=='__main__':main()
