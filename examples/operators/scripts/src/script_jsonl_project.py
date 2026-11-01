"""Select top-level keys from JSONL objects, one bounded line at a time."""
import json
from script_support import parameters, lines, infer, preview
from xstream_operator import run_operator


def validate(params):
    p = parameters(params, ('fields',), ('missing',))
    fields = p['fields']
    if (not isinstance(fields, list) or not 1 <= len(fields) <= 128
            or any(not isinstance(k, str) or not 1 <= len(k) <= 256 for k in fields)
            or len(set(fields)) != len(fields)):
        raise ValueError('fields must contain 1–128 unique nonempty keys, up to 256 characters each')
    if p.get('missing', 'null') not in ('null', 'error'):
        raise ValueError('missing must be null or error')
    return p


def reject_constant(value):
    raise ValueError('non-finite JSON constant: ' + value)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('duplicate JSON key: ' + key)
        result[key] = value
    return result


def process(ctx):
    p = validate(ctx.params)
    out = ctx.bytes_output('result', 'jsonl')
    for line in lines(ctx.input('source')):
        if not line.strip():
            continue
        row = json.loads(line, parse_constant=reject_constant, object_pairs_hook=unique_object)
        if not isinstance(row, dict):
            raise ValueError('each JSONL value must be an object')
        if p.get('missing', 'null') == 'error' and any(k not in row for k in p['fields']):
            raise ValueError('JSON object is missing a selected field')
        selected = {k: row.get(k) for k in p['fields']}
        out.write((json.dumps(selected, ensure_ascii=False, allow_nan=False, separators=(',', ':')) + '\n').encode('utf-8'))


def infer_outputs(inputs, params):
    return infer(inputs, params, validate, format='jsonl')


def preview_outputs(inputs, params, limits):
    return preview(process, inputs, params, limits)


if __name__ == '__main__':
    run_operator(process, infer_outputs, preview_outputs=preview_outputs)
