#!/usr/bin/env python3
"""Replay pinned BEDTools and EMBOSS scientific reference cases (external tools are test-only)."""
import argparse,json,os,re,subprocess,tempfile
from pathlib import Path
HERE=Path(__file__).resolve().parent
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--bedtools',type=Path,required=True);p.add_argument('--emboss-root',type=Path,required=True,help='Prefix containing usr/bin and usr/share/EMBOSS');args=p.parse_args();root=args.emboss_root.resolve();env=dict(os.environ,LD_LIBRARY_PATH=str(root/'usr/lib/emboss/lib')+':'+str(root/'usr/lib/x86_64-linux-gnu')+':'+os.environ.get('LD_LIBRARY_PATH',''),EMBOSS_ACDROOT=str(root/'usr/share/EMBOSS/acd'),EMBOSS_DATA=str(root/'usr/share/EMBOSS/data'));results=[]
 for filename in ['upstream-genome-cases.json','upstream-emboss-cases.json','upstream-protein-cases.json','upstream-alignment-cases.json','upstream-nucleotide-cases.json']:
  for c in json.loads((HERE/filename).read_text())['cases']:
   with tempfile.TemporaryDirectory() as td:
    td=Path(td);(td/'input').write_text(c['data']);(td/'source').write_text(c['data']);(td/'reference').write_text(c.get('inputs',{}).get('reference',''));(td/'matrix').write_text('  A C G T N\n'+''.join(a+' '+' '.join(str(2 if a==b and a!='N' else -1) for b in 'ACGTN')+'\n' for a in 'ACGTN'))
    argv=c['reference_command'];binary=args.bedtools.resolve() if argv[0]=='bedtools' else root/'usr/bin'/argv[0];cmd=[str(binary)]+[str(td/a[1:-1]) if a.startswith('<') and a.endswith('>') else a for a in argv[1:]];proc=subprocess.run(cmd,env=env,capture_output=True,timeout=30,check=True);actual=(td/'output').read_text() if (td/'output').exists() else proc.stdout.decode()
    if filename=='upstream-alignment-cases.json':
     score=float(re.search(r'# Score:\s*([-0-9.]+)',actual)[1]);assert abs(score-c['native_score'])<1e-8,(c['operator'],score,c['native_score'])
    elif filename=='upstream-protein-cases.json':
     value=float(re.search(r'Isoelectric Point =\s*([0-9.]+)',actual)[1]);expected=float(re.search(r'Isoelectric Point =\s*([0-9.]+)',c['native_output'])[1]);assert value==expected
    elif filename=='upstream-nucleotide-cases.json' and c['operator']=='emboss_fuzznuc':
     def gff(text):return [line.split('\t')[0:9] for line in text.splitlines() if line and not line.startswith('#')]
     assert gff(actual)==gff(c['native_output']),(c['operator'],actual,c['native_output'])
    elif filename in ['upstream-emboss-cases.json','upstream-nucleotide-cases.json']:
     def sequences(text):
      out=[]
      for line in text.splitlines():
       if line.startswith('>'):out.append('')
       elif line.strip():
        assert out,text;out[-1]+=line.strip()
      return out
     assert sequences(actual)==sequences(c['native_output']),(c['operator'],actual,c['native_output'])
    else:assert actual==c['native_output'],(c['operator'],actual,c['native_output'])
    results.append(dict(operator=c['operator'],source=filename,status='passed'));print('PASS native scientific reference',c['operator'],flush=True)
 (HERE/'verification-scientific-reference.json').write_text(json.dumps(dict(status='passed',cases=len(results),results=results),indent=2)+'\n')
if __name__=='__main__':main()
