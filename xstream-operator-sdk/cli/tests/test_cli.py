import contextlib
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch
import zipfile

from xstream_operator_cli.cli import main, submit


class CommandTests(unittest.TestCase):
    def test_help_and_required_command(self):
        for args in (['--help'], ['submit', '--help'], ['--version']):
            with contextlib.redirect_stdout(io.StringIO()) as out, self.assertRaises(SystemExit) as status:
                main(args)
            self.assertEqual(status.exception.code, 0)
            self.assertIn('xstream-operator', out.getvalue())
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as status:
            main([])
        self.assertEqual(status.exception.code, 2)

    def test_directory_and_archive_submission(self):
        requests = []
        class Repository(BaseHTTPRequestHandler):
            def log_message(self, *_): pass
            def do_GET(self):
                if self.path == '/v1/build-languages': value = [{'language': 'python', 'executor': 'python'}]
                elif self.path == '/v1/runtimes': value = [{'name': 'python-arrow', 'version': '1.0', 'image': 'sha256:' + 'a'*64, 'archive': {'sha256': 'b'*64, 'size': 1, 'filename': 'runtime.tar'}, 'build': {'languages': ['python']}}]
                else: self.send_error(404); return
                self.reply(200, value)
            def do_PUT(self):
                body = self.rfile.read(int(self.headers['Content-Length']))
                requests.append(('PUT', self.headers.get('Authorization'), body))
                assert self.path == '/v1/blobs/sha256/' + hashlib.sha256(body).hexdigest()
                self.reply(201, {})
            def do_POST(self):
                requests.append(('POST', self.headers.get('Authorization'), self.rfile.read(int(self.headers['Content-Length']))))
                self.reply(202, {'submission_id': 'example-submission', 'status': 'pending'})
            def reply(self, status, value):
                b=json.dumps(value).encode();self.send_response(status);self.send_header('Content-Length',str(len(b)));self.end_headers();self.wfile.write(b)
        server = ThreadingHTTPServer(('127.0.0.1', 0), Repository)
        thread = threading.Thread(target=server.serve_forever, daemon=True);thread.start()
        try:
            with tempfile.TemporaryDirectory() as temp:
                root=Path(temp);(root/'src').mkdir();(root/'src/main.py').write_text('pass\n')
                (root/'local.yaml').write_text('private settings')
                definition={'name':'example','version':'1.0','description':'Example','language':'python','entrypoint':'main','runtime':'python-arrow@1.0','source_public':True,'icon':{'path':'M1 1h2','color':'#123456'},'inputs':{},'parameters':{'type':'object','properties':{}}}
                (root/'operator.yaml').write_text(json.dumps(definition));token=root/'token';token.write_text('test-submitter')
                origin=f'http://localhost:{server.server_port}'
                with contextlib.redirect_stdout(io.StringIO()) as output:
                    main(['submit',str(root),'--url',origin,'--token-file',str(token)])
                self.assertEqual(json.loads(output.getvalue())['status'],'pending')
                self.assertTrue(all(auth=='Bearer test-submitter' for _,auth,_ in requests))
                with zipfile.ZipFile(io.BytesIO(requests[0][2])) as z:self.assertEqual(z.namelist(),['src/main.py'])
                manifest=json.loads(requests[1][2]);self.assertNotIn('artifact',manifest);self.assertNotIn('categories',manifest)
                source=root/'sources.zip';source.write_bytes(requests[0][2]);requests.clear()
                with patch.dict(os.environ, {'TEST_TOKEN':'test-submitter'}), contextlib.redirect_stdout(io.StringIO()):
                    main(['submit',str(root/'operator.yaml'),'--source',str(source),'--url',origin,'--token-env','TEST_TOKEN'])
                self.assertEqual([r[0] for r in requests],['PUT','POST'])
                with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as status:
                    main(['submit',str(root),'--source',str(source),'--url',origin,'--token-file',str(token)])
                self.assertEqual(status.exception.code,2)
        finally:server.shutdown();server.server_close();thread.join()

    def test_templates_check_and_include_dependency_declarations(self):
        from xstream_operator_cli.cli import create_template, check_directory, package_directory
        import ast
        for language in ('python', 'java', 'shell'):
            with tempfile.TemporaryDirectory() as temp:
                root=Path(temp)/'sample'
                create_template(root, language)
                check_directory(root)
                for path in (root/'src').glob('*.py'):
                    ast.parse(path.read_text())
                archive=package_directory(root,Path(temp)/'sources.zip')
                with zipfile.ZipFile(archive) as z:
                    self.assertNotIn('local.yaml',z.namelist())
                    self.assertIn('pyproject.toml' if language!='java' else 'pom.xml',z.namelist())
                with contextlib.redirect_stdout(io.StringIO()):
                    main(['check',str(root)])
                (root/'lib').mkdir();(root/'lib/escape').symlink_to('/etc/passwd')
                with self.assertRaises(ValueError):
                    package_directory(root,Path(temp)/'unsafe.zip')

    def test_remote_plain_http_is_rejected_before_upload(self):
        with self.assertRaisesRegex(ValueError,'HTTPS'):
            submit(Path('/unused'),Path('/unused'),'http://operators.example.org','test-token')


if __name__ == '__main__': unittest.main()
