"""Job-coordinated staged sinks; callbacks run in the same isolated process."""
import json
import os
import socket
import struct
import sys
import threading
from . import OperatorContext


def _read(connection):
    def exact(size):
        data = bytearray()
        while len(data) < size:
            chunk = connection.recv(size - len(data))
            if not chunk:
                raise EOFError('Sink lifecycle channel closed')
            data.extend(chunk)
        return data
    size = struct.unpack('>I', exact(4))[0]
    if not 0 < size <= 8192:
        raise ValueError('invalid Sink control length')
    value = json.loads(exact(size))
    if not isinstance(value, dict):
        raise ValueError('invalid Sink control message')
    return value


def _write(connection, state):
    raw = json.dumps({'state': state}).encode()
    connection.sendall(struct.pack('>I', len(raw)) + raw)


def run_sink(process, on_complete, on_discard, args=None):
    """process(ctx) stages; on_complete(ctx) publishes; on_discard(ctx, error) cleans.

    process must cooperate with cancellation through context input iteration.
    Callbacks must finish within the engine's 30-second lifecycle deadline.
    """
    address = os.environ.get('XSTREAM_SINK_SOCKET')
    if not address:
        raise RuntimeError('Sink requires a Job lifecycle coordinator')
    context = OperatorContext(args=args)
    context.attempt_id = os.environ['XSTREAM_ATTEMPT_ID']
    context.job_id = os.environ['XSTREAM_JOB_ID']
    state, errors = [], []
    event = threading.Event()
    commit_started = False
    with socket.socket(socket.AF_UNIX) as connection:
        connection.connect(address)
        _write(connection, 'ready')
        def listen():
            try:
                decision = _read(connection)['state']
                if decision not in ('complete', 'discard'):
                    raise ValueError('invalid Sink decision')
                state.append(decision)
                if decision == 'discard':
                    context._sink_cancelled = True
            except BaseException as error:
                errors.append(error)
                context._sink_cancelled = True
            finally:
                event.set()
        listener = threading.Thread(target=listen, daemon=True)
        listener.start()
        entered = False
        try:
            context.__enter__()
            entered = True
            process(context)
            if context._outputs or context._index != len(context.input_names):
                raise ValueError('Sink must consume every input and declare no output ports')
            context._active()
            context._frame('', 'sink_prepared')
            event.wait()
            if errors:
                raise errors[0]
            if state != ['complete']:
                raise RuntimeError('Sink attempt discarded')
            commit_started = True
            on_complete(context)
            _write(connection, 'completed')
        except BaseException as error:
            if commit_started:
                try:
                    _write(connection, 'commit_failed')
                except OSError:
                    pass
            else:
                try:
                    on_discard(context, error)
                    _write(connection, 'discarded')
                except BaseException as cleanup:
                    try:
                        _write(connection, 'discard_failed')
                    except OSError:
                        pass
                    raise cleanup from error
            raise
        finally:
            # Resource release cannot emit end frames or pretend the Sink committed.
            try:
                if entered:
                    context.__exit__(RuntimeError, None, None)
            finally:
                try:
                    connection.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                listener.join(timeout=1)
