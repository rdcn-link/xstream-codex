from xstream_operator import BytesOperator


class UpperAscii(BytesOperator):
    def transform(self, chunk, params):
        return chunk.upper() if params.get('uppercase', True) else chunk


if __name__ == '__main__':
    UpperAscii().main()
