"""Serialize record batches as bounded UTF-8 JSONL rows."""
import base64
import datetime
import decimal
import json
from script_support import parameters, infer, preview, MAX_LINE_BYTES
from xstream_operator import run_operator


def validate(params):
    return parameters(params)


def json_value(value):
    if isinstance(value, (datetime.date, datetime.time, datetime.datetime)):
        return value.isoformat()
    if isinstance(value, decimal.Decimal):
        return str(value)
    if isinstance(value, bytes):
        return base64.b64encode(value).decode('ascii')
    raise TypeError('unsupported JSON value: ' + type(value).__name__)


def process(ctx):
    validate(ctx.params)
    out = ctx.bytes_output('result', 'jsonl')
    source = ctx.input('source')
    if len(source.schema.names) != len(set(source.schema.names)):
        raise ValueError('record field names must be unique')
    for batch in source.records():
        for index in range(batch.num_rows):
            row = {field.name: batch.column(i)[index].as_py() for i, field in enumerate(batch.schema)}
            # Incremental encoding enforces an output-row bound before joining chunks.
            chunks, size = [], 1
            encoder = json.JSONEncoder(ensure_ascii=False, allow_nan=False, separators=(',', ':'), default=json_value)
            for part in encoder.iterencode(row):
                value = part.encode('utf-8')
                size += len(value)
                if size > MAX_LINE_BYTES:
                    raise ValueError('JSONL output row exceeds 1 MiB including newline')
                chunks.append(value)
            out.write(b''.join(chunks) + b'\n')


def infer_outputs(inputs, params):
    return infer(inputs, params, validate, representation='records', format='jsonl')


def preview_outputs(inputs, params, limits):
    return preview(process, inputs, params, limits)


if __name__ == '__main__':
    run_operator(process, infer_outputs, preview_outputs=preview_outputs)
