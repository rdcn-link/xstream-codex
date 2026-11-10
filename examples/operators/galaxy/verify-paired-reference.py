#!/usr/bin/env python3
"""Replay pinned BEDTools and seqtk native outputs for two-input stream adapters."""
import argparse,json,subprocess,tempfile
from pathlib import Path
HERE=Path(__file__).resolve().parent

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--bedtools',type=Path,required=True);p.add_argument('--seqtk',type=Path,required=True);p.add_argument('--report',type=Path,default=HERE/'verification-paired-reference.json');a=p.parse_args();results=[]
 for filename,family in [('upstream-paired-interval-cases.json','bedtools'),('upstream-paired-sequence-cases.json','seqtk')]:
  evidence=json.loads((HERE/filename).read_text())
  for i,c in enumerate(evidence['cases']):
   with tempfile.TemporaryDirectory(prefix='galaxy-paired-oracle-') as td:
    root=Path(td);source=root/'source';ref=root/'reference';source.write_text(c['data']);ref.write_text(c['inputs']['reference'])
    if family=='seqtk':args=[str(source) if v=='<source>' else str(ref) if v=='<reference>' else v for v in c['reference_command'][1:]]
    else:
     args=c['reference_command'][1:];args=args+(['-i',str(source),'-files',str(ref)] if c['operator']=='bed_annotate' else ['-a',str(source),'-b',str(ref)])
    out=subprocess.run([str(getattr(a,family).resolve()),*args],capture_output=True,check=True,timeout=30).stdout.decode();assert out==c.get('native_output',c['expected']),(family,i,c['operator'],out)
   results.append(dict(reference=family,case=i,operator=c['operator'],status='passed'))
 a.report.write_text(json.dumps(dict(status='passed',case_count=len(results),cases=results),indent=2)+'\n');print('PASS',len(results),'two-input native oracle cases')
if __name__=='__main__':main()
