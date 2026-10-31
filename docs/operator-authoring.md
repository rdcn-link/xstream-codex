# Write, submit and use an operator

[中文](operator-authoring_zh.md)

XStream operators run in the standalone engine or DACP nodes. Developers submit source only; the repository scans, builds in isolation and publishes after administrator approval. Administrators assign categories and the final trust score.

## Choose an interface and runtime

Select an actually registered runtime and version at `/runtimes`. Execution and build dependencies must match; JVM environments provide the XStream SDK and Scala build environments also provide the Scala compiler. Keep business code in the small operator package and dependencies in the shared runtime.

Python offers `BytesOperator` and `RecordsOperator`; Java/Scala implement `Operator` or use the `BytesOperator`, `ByteStreamOperator` and `RecordsOperator` templates. Templates supply schema and bounded-sample inference; override the relevant method when changing the output structure. Low-level implementations must provide processing, structure inference and output samples.

## Python

Install the SDK and save this as `src/upper_ascii.py`. Input `abc\n` produces `ABC\n`; `uppercase: false` preserves the input. This example handles ASCII: byte chunks do not guarantee UTF-8 character or business-record boundaries.

```bash
python -m pip install ./xstream-operator-sdk/python
```

```python
from xstream_operator import BytesOperator

class UpperAscii(BytesOperator):
    def transform(self, chunk, params):
        return chunk.upper() if params.get('uppercase', True) else chunk

if __name__ == '__main__':
    UpperAscii().main()
```

## Scala

Save this as `src/example/Identity.scala`. It transfers an `InputStream` to an `OutputStream` without buffering the whole Item. Submit with `language: scala`, `entrypoint: example.Identity` and a registered JVM runtime. Java can implement the same JVM SDK `Operator` interface.

```scala
package example

import java.io.{InputStream, OutputStream}
import com.fasterxml.jackson.databind.JsonNode
import xstream.operator.ByteStreamOperator

object Identity extends ByteStreamOperator {
  override def transform(input: InputStream, output: OutputStream,
                         params: JsonNode): Unit = {
    input.transferTo(output)
  }
}
```

## Inputs, parameters and outputs

`inputs` declares named input ports and representations; `parameters` declares JSON parameter types, defaults and constraints. Do not declare `outputs` in a manifest: `inferOutputs`/`infer_outputs` returns output ports and structures. Parameter values come from Job `params`, such as `uppercase: true`, rather than the input data stream.

Record templates process Arrow RecordBatch/VectorSchemaRoot tables with field types intact; implement `output_schema`/`outputSchema` when changing fields. General contexts provide `ctx.input(name)`, `ctx.records_output`/`ctx.recordsOutput` and byte equivalents. Fully consume multiple inputs one at a time in lexical port-name order; declare all outputs before writing data.

See the complete [split_orders](../examples/operators/python/split_orders/1.0/split_orders.py) example: `left` and `right` accept `id:int64, amount:float64`, then route rows into `accepted` and `rejected` using `min_amount`. Job bindings such as `left: step_a:result` and `right: step_b:result` reference upstream outputs; omitted ports use the default. Sources have `inputs: {}`; operators without outputs are also allowed.

## Prepare a source submission

Use a directory containing `submission.yaml`, `src/upper_ascii.py` and `LICENSE.txt`. Replace `python-arrow@1.0` with an available repository runtime. Declare the actual source license.

```yaml
name: upper_ascii
version: '1.0'
description: Uppercase an ASCII byte stream
language: python
entrypoint: upper_ascii
runtime: python-arrow@1.0
source_public: true
icon:
  path: M4 19L12 5l8 14M7 14h10
  color: '#14866b'
inputs:
  source:
    representations: [bytes]
    formats: []
parameters:
  type: object
  properties:
    uppercase:
      type: boolean
      default: true
      description: Convert ASCII letters to uppercase
  required: []
  additionalProperties: false
provenance:
  license: Apache-2.0
  license_file: LICENSE.txt
```
Source archives cannot contain prebuilt JARs, wheels, executables, nested archives or symlinks. Package from the operator project, then run the helper from the XStream checkout:

```bash
python -m zipfile -c sources.zip src
python scripts/submit-operator.py /path/to/submission.yaml \
  --source /path/to/sources.zip --url https://operators.example.org \
  --token-file /secure/submitter.token
```
The repository administrator issues the Token. Submission returns a submission ID, not a published operator. Administrators inspect source, scan and build results, select categories and a trust score, then approve public download. `source_public` controls public source visibility; administrators can always inspect source. See [source submission and review](operator-submission.md) for APIs, isolation and review details.

## Use in a Job

```yaml
name: Uppercase observations
tasks:
- id: read
  operator: read_item@1.0
  inputs: {}
  params:
    uri: context:/science/observations.csv
    view: bytes
- id: upper
  operator: upper_ascii@1.0
  inputs:
    source: read
  params:
    uppercase: true
outputs:
  result: upper
```

Replace the URI with an actual Item in the execution catalog. `context:` uses the bound catalog; a remote URI specifies its DACP address explicitly. In DACP Shell, `submit workflow.yaml` receives and prints the result stream incrementally. Standalone execution uses `xstream-runner CONFIG.yaml JOB.yaml [VARIABLES.yaml] [POLICY.yaml]`.

## Startup arguments and protocol boundaries

An equivalent launcher call is `/usr/local/bin/python /operator/upper_ascii.py operator /input /input/params.json`: `operator` is the fixed mode, `/input` holds the generated input manifest and stream channels, and `/input/params.json` contains a parameter object such as `{"uppercase":true}`. Single input/output may use standard streams; the framework arranges FIFO channels for multiple ports. Use the SDK rather than managing channels in business code.

stdout is reserved for the stream protocol; logs go to stderr. Consume inputs through EOS; successful completion emits end events and errors must not claim success. Byte output is chunked; record batches must fit protocol limits, and samples have additional row/byte budgets. Do not collect an entire stream in memory or create persistent files for ordinary intermediate steps.

Further reading: [SDK](../xstream-operator-sdk/README.md) · [Parameters](operator-parameters.md) · [Structure inference](structure-inference.md) · [Stream protocol](operator-streams.md)

## Local development

The standalone engine supports source-directory references, reusable builds and direct source submission; see [local operator libraries](local-operators.md). `LocalOperatorBuilder` is an implemented build extension point with built-in `python` and `command` providers registered through ServiceLoader.
