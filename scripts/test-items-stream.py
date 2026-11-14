#!/usr/bin/env python3
"""Real isolated JVM jobs: collection Sources and host-mediated lazy byte reads."""
import argparse
import json
import subprocess
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--runner', type=Path, default=ROOT/'xstream-engine/target/universal/stage/bin/xstream-runner')
parser.add_argument('--jar', type=Path, default=ROOT/'examples/operators/item-sources/target/scala-2.13/xstream-item-sources_2.13-1.0.jar')
parser.add_argument('--image', default='xstream/operator-jvm-items:1.0')
parser.add_argument('--published', action='store_true', help='Use approved repository packages instead of local inline definitions')
parser.add_argument('--repository', default='http://127.0.0.1:8820')
args = parser.parse_args()
with tempfile.TemporaryDirectory(prefix='items-stream-smoke-') as directory:
    base = Path(directory)
    data = base/'data'; data.mkdir()
    with zipfile.ZipFile(data/'input.zip', 'w') as archive:
        archive.writestr('labels/a.txt', 'hello')
        archive.writestr('labels/a.tfw', 'world!')
    (data/'manifest.csv').write_text('uri,tag\ninput.zip/labels/a.txt,first\ninput.zip/labels/a.txt,again\ninput.zip/labels/a.tfw,last\n')
    config = {'remote_operator_library_urls': [args.repository], 'inline_runtimes': {'jvm': {'image': args.image, 'executor': 'jvm'}},
              'operator_cache': str(base/'cache'), 'execution_directory': str(base/'history'),
              'operator_fusion_enabled': False,
              'datasets': {'science': {'provider': 'directory', 'source': {'root': str(data)}}}}
    (base/'engine.yaml').write_text(json.dumps(config))
    def run(cls, uri, params=None, consumer='ItemByteCount', success=True):
        definitions = {}
        for name, entry, role in [('list',cls,'source'),('consume',consumer,'transform')]:
            definitions[name] = {'type':'sdk','role':role,'runtime':'jvm',
                'program':{'path':str(args.jar.resolve()),'entrypoint':'xstream.examples.items.'+entry},
                'inputs':{} if role=='source' else {'source':{'representations':['items'],'formats':[]}}}
        job={'name':'Item streams regression','operators':definitions,'steps':[
            {'id':'list','operator':{'inline':'list'},'inputs':{},'params':{'uri':uri,**(params or {})}},
            {'id':'consume','operator':{'inline':'consume'},'inputs':{'source':'list:result'},'params':{}},
            {'id':'receive','operator':'client_sink@1.0','inputs':{'result':'consume:result'}}]}
        if args.published:
            names={'FolderItemsSource':'folder_items_source','CsvItemsSource':'csv_items_source','ItemInventory':'item_inventory','ItemByteCount':'item_byte_count'}
            job.pop('operators')
            job['steps'][0]['operator']=names[cls]+'@1.0'
            job['steps'][1]['operator']=names[consumer]+'@1.0'
        (base/'job.yaml').write_text(json.dumps(job))
        result=subprocess.run([str(args.runner.resolve()),str(base/'engine.yaml'),str(base/'job.yaml')],capture_output=True,timeout=180)
        assert (result.returncode==0)==success, result.stderr.decode()[-12000:]
        return result.stdout.decode()
    refs=run('CsvItemsSource','context:/science/manifest.csv',consumer='ItemInventory')
    assert refs.count('context:/science/input.zip/labels/a.txt')==2,refs
    csv=run('CsvItemsSource','context:/science/manifest.csv')
    assert csv.splitlines()==['context:/science/input.zip/labels/a.txt\t5']*2+['context:/science/input.zip/labels/a.tfw\t6'],csv
    print('PASS: CSV duplicates/order and lazy member bytes through isolated JVM operators')
    (data/'labels').mkdir()
    (data/'labels'/'one.txt').write_text('abc')
    (data/'labels'/'nested').mkdir()
    (data/'labels'/'nested'/'two.txt').write_text('abcd')
    physical=run('FolderItemsSource','context:/science/labels',{'recursive':True})
    assert sorted(physical.splitlines())==['context:/science/labels/nested/two.txt\t4','context:/science/labels/one.txt\t3'],physical
    print('PASS: physical directory enumeration uses the live provider namespace')
    folder=run('FolderItemsSource','context:/science/input.zip',{'recursive':True})
    assert sorted(folder.splitlines())==['context:/science/input.zip/labels/a.tfw\t6','context:/science/input.zip/labels/a.txt\t5'],folder
    print('PASS: archive directory references and incremental lazy content reads')
    inventory=run('FolderItemsSource','context:/science/input.zip',{'recursive':True},'ItemInventory')
    assert 'context:/science/input.zip/labels\tfolder' in inventory,inventory
    print('PASS: reference-only consumer retains folder and member metadata')
    (data/'manifest.csv').write_text('uri\nmissing.txt\n')
    run('CsvItemsSource','context:/science/manifest.csv',success=False)
    print('PASS: unavailable referenced content fails the Job')
    (data/'manifest.csv').write_text('uri\n../private.txt\n')
    run('CsvItemsSource','context:/science/manifest.csv',success=False)
    print('PASS: traversal is rejected before content access')
