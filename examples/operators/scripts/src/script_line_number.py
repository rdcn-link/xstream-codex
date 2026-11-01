"""Prefix UTF-8 lines with consecutive numbers; preserve line endings."""
from script_support import parameters, lines, infer, preview
from xstream_operator import run_operator


def validate(params):
    p = parameters(params, optional=('start', 'separator'))
    n, sep = p.get('start', 1), p.get('separator', '\t')
    if type(n) is not int or not 0 <= n <= 9223372036854775807:
        raise ValueError('start must be a nonnegative int64')
    if not isinstance(sep, str) or len(sep) > 64:
        raise ValueError('separator must be text of at most 64 characters')
    return p


def process(ctx):
    p = validate(ctx.params)
    out = ctx.bytes_output('result', 'text')
    number = p.get('start', 1)
    for line in lines(ctx.input('source')):
        out.write((str(number) + p.get('separator', '\t')).encode('utf-8'))
        out.write(line.encode('utf-8'))
        number += 1


def infer_outputs(inputs, params):
    return infer(inputs, params, validate)


def preview_outputs(inputs, params, limits):
    return preview(process, inputs, params, limits)


if __name__ == '__main__':
    run_operator(process, infer_outputs, preview_outputs=preview_outputs)
