"""Bounded UTF-8 line processing and mandatory inference/preview contracts."""
from xstream_operator import descriptor, result, preview_process

MAX_LINE_BYTES = 1024 * 1024


def parameters(params, required=(), optional=()):
    if not isinstance(params, dict) or set(params) - set(required) - set(optional) or set(required) - set(params):
        raise ValueError('missing or unexpected parameters')
    return params


def lines(source):
    """Yield UTF-8 lines including their ending; tolerate arbitrary stream chunking."""
    pending = bytearray()
    for block in source.bytes():
        start = 0
        while start < len(block):
            end = block.find(b'\n', start)
            stop = len(block) if end < 0 else end + 1
            if len(pending) + stop - start > MAX_LINE_BYTES:
                raise ValueError('line exceeds 1 MiB including its line ending')
            pending.extend(block[start:stop])
            start = stop
            if end >= 0:
                yield bytes(pending).decode('utf-8')
                pending.clear()
    if pending:
        yield bytes(pending).decode('utf-8')


def infer(inputs, params, validate, representation='bytes', format='text'):
    if set(inputs) != {'source'} or representation not in inputs['source']['representations']:
        raise ValueError('expected source ' + representation + ' input')
    validate(params)
    return result({'result': descriptor(['bytes'], [format], certainty='known')})


def preview(process, inputs, params, limits):
    return preview_process(process, inputs, params, limits)
