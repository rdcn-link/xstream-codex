# Build a shared Python/Arrow environment; publish its image ID through the repository.
FROM python:3.12-slim
RUN python -m pip install --no-cache-dir pyarrow==23.0.1 uv==0.9.7

COPY xstream-operator-sdk/python/xstream_operator /opt/xstream-sdk/xstream_operator
ENV PYTHONPATH=/opt/xstream-sdk
