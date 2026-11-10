#!/usr/bin/env python3
"""Replay preserved golden cases using locally built pinned seqtk and BEDTools binaries."""
import argparse,json,subprocess,tempfile
from pathlib import Path
HERE=Path(__file__).resolve().parent

def main():
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--seqtk',type=Path,required=True)
 parser.add_argument('--bedtools',type=Path,required=True)
 parser.add_argument('--report',type=Path,default=HERE/'verification-upstream-reference.json')
 args=parser.parse_args();evidence=json.loads((HERE/'upstream-golden-cases.json').read_text())
 binaries={'seqtk':args.seqtk.resolve(),'bedtools2':args.bedtools.resolve()};results=[]
 with tempfile.TemporaryDirectory(prefix='galaxy-upstream-reference-') as directory:
  root=Path(directory);source=root/'input.txt';genome=root/'genome.txt'
  for index,case in enumerate(evidence['cases']):
   family=case.get('reference','seqtk');assert family in binaries
   source.write_text(case['data'])
   chromosomes=case.get('params',{}).get('chromosomes',[])
   genome.write_text(''.join(value.replace('=','\t',1)+'\n' for value in chromosomes))
   command=case['reference_command'];assert command[0] in ['seqtk','bedtools']
   substitutions={'<input>':str(source),'<genome>':str(genome)}
   actual=subprocess.run([str(binaries[family]),*[substitutions.get(value,value) for value in command[1:]]],capture_output=True,timeout=30,check=True).stdout.decode()
   assert actual==case['expected'],(index,case['operator'],actual,case['expected'])
   results.append({'case':index,'operator':case['operator'],'reference':family,'status':'passed'})
 args.report.write_text(json.dumps({'status':'passed','references':evidence['references'],'case_count':len(results),'cases':results},indent=2)+'\n')
 print('PASS',len(results),'upstream golden outputs')
if __name__=='__main__':main()
