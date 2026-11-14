"""Execute ordinary programs inside the runtime; host code never invokes the tool."""
import json, os, shutil, stat, struct, subprocess, sys, tempfile, threading
import pyarrow.ipc as ipc

config = json.loads(sys.argv[1])
if len(sys.argv) > 2 and sys.argv[2].startswith('infer'):
    from xstream_operator.inference import descriptor, result, run_inference
    def infer_outputs(inputs, params):
        if set(inputs) != set(config['inputs']):
            raise ValueError('tool input ports differ')
        for value in inputs.values():
            if value['representations'] and value['representations'] != ['bytes']:
                raise ValueError('tool requires bytes inputs')
        return result({p: descriptor(['bytes'], [v['format']], certainty='known')
                       for p, v in config['outputs'].items()})
    def preview_outputs(inputs, params, limits):
        raise ValueError('Ordinary tools are not executed for previews; provide an SDK preview implementation')
    run_inference(infer_outputs, sys.argv[2:], preview=preview_outputs)
    sys.exit(0)


def frame(kind, port, data=b'', **metadata):
    header = json.dumps(dict(type=kind, port=port, **metadata)).encode()
    if len(header) > 65536:
        raise ValueError('output header too large')
    sys.stdout.buffer.write(struct.pack('>II', len(header), len(data)) + header + data)
    sys.stdout.buffer.flush()


def consume(source, target):
    with open(source, 'rb') as f, ipc.open_stream(f) as reader:
        for batch in reader:
            for cell in batch.column(0):
                value = memoryview(cell.as_py())
                while value:
                    n = target.write(value)
                    if not n:
                        raise BrokenPipeError('tool closed input')
                    value = value[n:]


with open('/input/manifest.json') as f:
    manifest = {v['port']: v for v in json.load(f)}
if set(manifest) != set(config['inputs']) or any(v['representation'] != 'bytes' for v in manifest.values()):
    raise ValueError('tool input stream contracts differ')
with open('/input/params.json') as f:
    params = json.load(f)

with tempfile.TemporaryDirectory(prefix='xstream-tool-') as work:
    input_paths = {p: os.path.join(work, 'input-' + str(i)) for i, (p, v) in enumerate(config['inputs'].items()) if v['binding'] == 'file'}
    output_paths = {p: os.path.join(work, 'output-' + str(i)) for i, (p, v) in enumerate(config['outputs'].items()) if v['binding'] == 'file'}
    argv = [config['executable']]
    if config['artifact_mode'] == 'script':
        argv.append(config['artifact'])
    elif config['artifact_mode'] == 'executable':
        target = '/operator-work/program'
        shutil.copyfile(config['artifact'], target)
        os.chmod(target, 0o500)
        argv[0] = target
    for argument in config['args']:
        if isinstance(argument, str):
            value = argument
        elif 'param' in argument:
            value = params[argument['param']]
            if value is None or isinstance(value, (dict, list)):
                raise ValueError('command parameters must be scalar values')
            value = str(value) if not isinstance(value, bool) else str(value).lower()
        elif 'input' in argument:
            value = input_paths[argument['input']]
        else:
            value = output_paths[argument['output']]
        if '\x00' in value:
            raise ValueError('command argument contains NUL')
        argv.append(value)
    errors = []
    def materialize(port, path):
        try:
            with open(path, 'wb', buffering=0) as target:
                consume('/input/' + manifest[port]['file'], target)
        except BaseException as error:
            errors.append(error)
    workers = [threading.Thread(target=materialize, args=(p, path), daemon=True) for p, path in input_paths.items()]
    for worker in workers:
        worker.start()
    for worker in workers:
        worker.join()
    if errors:
        raise errors[0]
    stdin_port = next((p for p, v in config['inputs'].items() if v['binding'] == 'stdin'), None)
    stdout_port = next((p for p, v in config['outputs'].items() if v['binding'] == 'stdout'), None)
    process = subprocess.Popen(argv, stdin=subprocess.PIPE if stdin_port else subprocess.DEVNULL,
                               stdout=subprocess.PIPE, stderr=None, cwd=work, bufsize=0)
    def feed():
        if stdin_port is None:
            return
        try:
            consume('/input/' + manifest[stdin_port]['file'], process.stdin)
        except BaseException as error:
            errors.append(error)
            process.kill()
        finally:
            process.stdin.close()
    pump = threading.Thread(target=feed, daemon=True)
    try:
        for port, definition in config['outputs'].items():
            frame('start', port, representation='bytes', format=definition['format'])
        pump.start()
        while True:
            chunk = process.stdout.read(65536)
            if not chunk:
                break
            if stdout_port:
                frame('data', stdout_port, chunk)
        status = process.wait()
        pump.join()
        if errors:
            raise errors[0]
        if status != 0:
            raise RuntimeError('tool exited with status ' + str(status))
        for port, path in output_paths.items():
            if not stat.S_ISREG(os.lstat(path).st_mode):
                raise ValueError('tool output must be a regular file')
            with open(path, 'rb') as source:
                while True:
                    chunk = source.read(65536)
                    if not chunk:
                        break
                    frame('data', port, chunk)
        for port in config['outputs']:
            frame('end', port)
    finally:
        if process.poll() is None:
            process.kill()
        process.wait()
        process.stdout.close()
