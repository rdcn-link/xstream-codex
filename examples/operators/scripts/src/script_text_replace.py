"""Replace literal text within UTF-8 lines without buffering a complete stream."""
from script_support import parameters, lines, infer, preview
from xstream_operator import run_operator


def validate(params):
    p = parameters(params, ('find',), ('replacement',))
    if not isinstance(p['find'], str) or not p['find'] or '\n' in p['find'] or '\r' in p['find']:
        raise ValueError('find must be nonempty text without line endings')
    if not isinstance(p.get('replacement', ''), str):
        raise ValueError('replacement must be text')
    if len(p['find']) > 4096 or len(p.get('replacement', '')) > 4096:
        raise ValueError('find and replacement are limited to 4096 characters')
    return p


def process(ctx):
    p = validate(ctx.params)
    out = ctx.bytes_output('result', 'text')
    for line in lines(ctx.input('source')):
        # Split/join avoids allocating an expanded line when a short match repeats.
        for index, part in enumerate(line.split(p['find'])):
            if index:
                out.write(p.get('replacement', '').encode('utf-8'))
            if part:
                out.write(part.encode('utf-8'))


def infer_outputs(inputs, params):
    return infer(inputs, params, validate)


def preview_outputs(inputs, params, limits):
    return preview(process, inputs, params, limits)


if __name__ == '__main__':
    run_operator(process, infer_outputs, preview_outputs=preview_outputs)
