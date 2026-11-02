# XStream Designer

[中文](README_zh.md)

`xstream-designer` is an independent Scala HTTP module providing an English browser-based visual Job editor without login or user management. It uses the ordinary Scala SDK to access nodes or a Union, does not execute jobs, and does not depend on server internals.

For editing instructions, screenshots and export examples, see the [Designer user guide](../docs/designer-guide.md).

## Start

Requires JDK 17 and sbt 1.10.x. Copy and edit [example.yaml](example.yaml), setting real service addresses, CA and Token files. Credentials remain in server configuration and are not returned to browsers.

```bash
cd xstream-designer
sbt test stage
target/universal/stage/bin/xstream-designer /etc/dacp/designer.yaml
```

The default address is `http://127.0.0.1:8840/`. Set `host: 0.0.0.0` for network access. Administrators maintain configuration; visitors do not log in. Configured ordinary upstream client credentials determine the catalogs and inference capabilities available to the website.

The distribution bundles `xstream-data-archives` for the Target catalog: ZIP, TAR, TAR.GZ/TGZ and GZIP Items expose their children when expanded. GZIP exposes one decompressed child; TAR.GZ exposes archive members directly. Nested archives are not recursively expanded. Dataset and Item rows use compact spacing.

## Design workflow

1. Target defaults to **Local** and can select a configured remote node. The Target catalog follows Target and generates `context:` Item URIs; other catalog roots exclude that target. Catalog branches load on expansion. Target selects the authoring context; DACP provides remote Job submission and Union owns automatic placement.
2. The configured `repository` loads automatically through `/v1/operators`. Search names and descriptions, choose versions and drag operators onto the canvas. `read_item@1.0` is a built-in data source.
3. Open the left **Catalogs** tab. Expand a catalog to load its datasets, then expand datasets and folders to browse Items. Click an Item to reveal floating action icons at the lower right of the end of its name: one per available view and one for **Copy path**. Hover or keyboard focus identifies each icon. Choosing a view sets its URI and explicit view on the selected or a new read step. Copying preserves the complete path including dataset name, such as `/science/climate.nc/temperature`. Folders expand or collapse only through their left arrow; clicking a folder name reveals its actions without expanding. Item rows use compact spacing. Union connections use public Union URIs.
4. Drag cards, click an output to start a pointer-following preview line, then click an input to establish the stream dependency. Right-click or press Esc to cancel. Edit titles and parameters in the inspector. Delete steps, remove connections, undo, redo and arrange automatically.
5. The canvas always contains exactly one **Outlet**, which can be moved but cannot be deleted or duplicated. Click an operator output port, then click Outlet to create a Job output binding. Outlet accepts one output port from one step; a new connection replaces the existing binding. The sidebar only edits step properties and parameters; result stream names can be edited in Job YAML. Select an Outlet connection and press Delete to remove its binding; the Outlet stays on the canvas. Imported Job outputs are drawn as connections to the same Outlet. Layout is saved in the browser draft and included in optional `designer` metadata by default. Empty `outputs: {}` is allowed; each producer port has at most one consumer. Each output port displays its representation, format and known Schema fields below it; unknown structures are labeled explicitly.
6. Edit or load a separate YAML variable file under **Variables**. Parameters support complete values such as `$INPUT_URI`, preserving numeric, boolean and compound types. Variables are not embedded in template exports; use **Export variables** separately.
7. Output structures are inferred automatically after parameters, variables or connections change. Parameter edits trigger inference after a 650 ms pause with valid edits saved automatically into the exported Job; incomplete or invalid input stays in the form with a message. The designer uses the shared `xstream-operator-inference` module to start and reuse local inference workers on demand, obtaining code from the configured operator repository. Ordinary operators are inferred without Union or member-node requests. `read_item` queries Item metadata from the local catalog or the configured service; upstream changes also update downstream structures. Requests are serialized and coalesced; stale responses are discarded. Use the right-hand **Outputs** tab to inspect each output port’s complete structure and bounded samples. Inference does not run a Job. Schemas are displayed before a separate bounded-sample request; `read_item` can read a limited number of source rows. Opening Outputs displays existing results without repeating inference. First use may start a worker. Incomplete parameters and inference failures are reported explicitly; stale fields are cleared and unknown structures are never guessed.
8. **Export job YAML** opens a YAML preview. **Include designer metadata** is checked by default; uncheck it to keep only the execution definition. Use **Copy**, **Print**, or **Download** for the selected content. The exported Job is accepted by Shell / SDKs. **Load YAML** loads an existing exported Job; alternatively edit the Job YAML panel and click **Apply YAML**. Imports accept the public Job format; internal subjob routing is reserved for service execution.

Browser localStorage saves a local draft. Drafts are not shared between browsers and there is no server-side job history store. Loading a historical file means uploading an existing YAML file, not retrieving dashboard execution history. Export performs graph checks without inference; design-time inference checks actual input contracts and schemas. The canvas supports scrolling, zoom and fit-to-view.

Submission example:

```text
submit workflow.yaml --vars variables.yaml
```

The lower **Logs** panel records operation messages, API request start/completion and duration, request errors, schema inference diagnostics and browser exceptions for the current browser session. Entries are classified as Info / Warning / Error and support filtering and clearing. Logs appear newest first, with new messages at the top. Auto-scroll follows the top; when disabled, incoming messages preserve the current reading position where possible. The upper status bar displays only the latest message; full messages remain in the log panel. The latest 1,000 entries are kept in browser memory without disk writes and cleared on page reload. Full backend logs from nodes and service processes remain in their respective log files.

## Chat composition

Chat connects to a Chat Completions-compatible model API. Describe a complete processing or analysis requirement, then answer follow-up questions. The assistant proposes a complete Job YAML and typed variables when enough information is available. It does not execute Jobs. Review the proposal and click **Apply proposed changes** to update the canvas; Undo remains available.

Configure the full API endpoint and model on the Designer server:

```yaml
chat:
  url: https://model.example/v1/chat/completions
  model: your-model
  token_env: XSTREAM_MODEL_API_KEY
```

Set the named environment variable before starting the service. It supplies the Bearer credential and is never sent to the browser. Omit token_env for a model service that requires no credential. Without chat configuration, the editor works normally and Chat is disabled. Chat uses the configured language-model API to propose Jobs.

For example: “Read weather observations, keep valid records above a temperature threshold supplied as a variable, merge the regional streams and produce CSV.” Include actual Item paths or expand the relevant Catalog folders first. The model receives the current draft, variables, available operator declarations, up to 100 browsed Item descriptors and the latest 12 messages. Catalog contents are loaded lazily, so this context is not a complete inventory. Data contents and output samples are not automatically sent. If paths, meanings or required operators are missing, the assistant should ask rather than invent them. Metadata and conversation are sent to the configured model service.

The model returns a JSON object with reply, job_yaml and variables_yaml. For a clarification, both YAML fields are null and the canvas stays unchanged. A proposal supplies complete YAML strings, follows logical stream connections and uses available operators. Invalid, truncated or unsupported proposals cannot be applied. Model requests time out after 90 seconds; responses are limited to 1 MiB.

Third-party model providers can still implement xstream.designer.ChatPlanner through Java ServiceLoader, registered in META-INF/services/xstream.designer.ChatPlanner. Add the provider JAR to plugins/ and launch with `java --add-opens=java.base/java.nio=ALL-UNNAMED -cp "lib/*:plugins/*" xstream.designer.Main CONFIG.yaml`; select the provider name through chat.provider. The ordinary configuration above directly calls the model API and needs no adapter service.

## APIs and development

HTTP APIs do not authenticate website visitors and serve this designer. Except configuration and static assets, use JSON POST:

| Path | Purpose |
|---|---|
| `GET /api/config` | Public service / repository names and chat capability, without credential files |
| `/api/operators` | Query a live repository catalog using `repository` |
| `/api/datasets`, `/api/items` | Browse connected catalogs and Item metadata |
| `/api/document`, `/api/mapping` | Parse / normalize Job, parameter or variable YAML |
| `/api/export` | Check graph structure and return Job and variable YAML |
| `/api/infer` | Request real inference using `job_yaml`, `variables_yaml` |
| `/api/chat` | Generate proposals from the draft, messages and live catalogs |

POSTs are limited to 1 MiB; repository lists to 4 MiB. Browsers cannot supply arbitrary proxy destinations. CA and token_file are server-side files. The editor reads metadata and structures and provides no job execution, repository publication or user management APIs.

The backend is Scala; browser interaction uses bundled native JavaScript / SVG / CSS without external CDNs. Tests cover YAML round trips, variable types, cycles and duplicate consumers, empty outputs, real Arrow schema presentation, repository queries, chat proposals and credential non-disclosure. Browser checks additionally verify dragging, wiring, imports / exports and real Union inference.

Browser regression test (requires Playwright and Chromium):

```bash
python3 scripts/test-job-designer.py --url http://localhost:8840 \
  --item-uri dacp://localhost:19860/DATASET/observations
```

Run from the repository root and replace the URI with an actual records Item containing a `quality` column. The test infers structures without executing jobs.

The properties panel is a docked right sidebar, initially hidden, and opens when a step is double-clicked or Outlet is selected. It reserves its own column instead of covering the canvas; closing it returns that space to the canvas. Both sidebars fold and expand using arrows on persistent narrow edge rails, without close buttons. The right sidebar shows `>` when expanded and `<` when folded; the left sidebar uses the opposite directions. Clicking blank canvas space or connections, or pressing Esc, leaves the properties sidebar open. The header directly displays **Step** and **Parameters** tabs with distinct borders and raised styling. The bottom editor starts collapsed: click a tab to expand it and the arrow button to collapse it. The canvas takes priority within the viewport; the page itself does not scroll.

The inspector has **Step**, **Parameters**, and **Outputs** tabs. Parameters are generated from the operator’s required static `parameters` contract: names and types come from the declaration, alongside descriptions, defaults, enums and bounds. Enable optional fields to set them explicitly; omitted fields stay omitted. Nested objects, arrays and whole-value variables (`$VAR_NAME`) use forms. Valid property and parameter edits save automatically and synchronize YAML while preserving types. Consecutive edits are grouped for undo. See [parameter contracts](https://github.com/rdcn-link/xstream-codex/blob/main/docs/operator-parameters.md).

Startup configuration supplies `local_catalog`, `nodes` and `repository`. The browser Target selector defaults to Local and can select a configured node; arbitrary service addresses and credentials cannot be entered. Both services load independently when opening the page. The compact header shows status and failures; reload the page after service recovery.

Click a stream connection to highlight it, then press Delete / Backspace while the canvas has focus. Selection highlights only the connection, without a rectangular frame or extra toolbar button. Deletion supports undo and redo. Keyboard deletion never edits a connection while typing in a form field. A port already consumed by another step cannot simultaneously be a Job output; remove the connection first. Imported output bindings also appear as connections to Outlet.

Select a step and press Delete to remove it along with its incident connections and Outlet connections. Select a connection and press Delete to remove just that connection. Undo restores the full deleted state, and redo reapplies deletion. Typing in parameter and name fields does not trigger graph deletion.

The left operator library uses collapsible categories and icon grids directly from administrator-assigned operator `categories` and developer-provided `icon` metadata. This metadata determines the library grouping and icon rendering. Search expands matching categories and clearing it restores their previous state. Hover or focus an icon to see its version, description, input ports, parameter types and executor. Drag onto the canvas, double-click or press Enter to add a step.

Dragging an existing step only changes its position and does not expand a folded properties sidebar; a single click selects a step and a double-click opens its properties.

Drag with the left mouse button on blank canvas space to select fully enclosed steps and Outlet. Shift-drag adds to the selection; Shift-click a step toggles it. Drag any selected element to move the group; Delete removes selected steps and their connections in one undoable action, while Outlet remains permanent. Marquee selection and dragging do not expand the properties sidebar and work after zooming or scrolling.



Delete and Backspace both remove selected steps or connections; multiple selected steps are deleted together. Editing inputs, text areas or parameter values never triggers graph deletion. Keyboard focus on the canvas does not draw a blue frame around the entire drawing area.

Deleting multiple steps requires confirmation; cancel preserves selection, the job and undo history. Single-step or connection deletion needs no confirmation. Undo / Redo buttons in the toolbar above the canvas and shortcuts support Ctrl/⌘+Z, Ctrl/⌘+Shift+Z and Ctrl+Y for redo. Bulk deletion and valid movement each restore as one complete operation. Text fields retain native text undo behavior; history cannot change during a drag.

Dragging steps or Outlet, or adding an operator, automatically moves overlapping steps and Outlet downward, cascading as needed and leaving a gap. Selected steps keep their relative offsets and stay at the dragged destination. Each preview starts from the original layout, so moving away restores neighbors; cancelling restores the complete layout without changing history. Dropping commits the entire move as one undo/redo operation. Connections are not placement obstacles.

The top-right corner of each step card displays the operator’s declared icon and color, matching the operator library. The built-in `read_item` uses the same data-source icon. A gray placeholder appears while operator metadata is unavailable.

Parameters use a compact form with fixed name labels. Declared single-type values show a type label and a small `$` variable toggle. Descriptions, defaults and constraints are available from the info hint. Optional parameters are enabled by checkbox. Multiline strings, nested objects, arrays, enums and union types remain editable. Additional parameter names are entered only during creation; existing names are labels.

The left sidebar separates **Operators** and **Catalogs** into two tabs, using rounded icon buttons stacked vertically along the sidebar’s left edge, with content on the right, distinct from the horizontal right properties tabs. Operators open by default. Switching tabs retains search, category expansion, dataset expansion and each panel’s scroll position. Up/Down arrows, Home and End switch tabs.

Opening **Catalogs** automatically loads the directory on first use, waiting for the configured server connection if necessary. Switching tabs reuses the loaded list and expanded items. Reloading the page refreshes catalog roots; concurrent loads are coalesced and empty or unavailable directories show an explicit status.

### Exporting and restoring layout

`designer.version: 1` identifies the layout format; `generator.name` and `generator.version` identify the designer and its build version. `exported_at` records the current export time in UTC (ISO 8601). `positions` stores step coordinates, `outlet` stores the result outlet position, and `viewport` stores zoom and scroll offsets. Loading restores this layout; files without metadata are arranged automatically. Execution accepts and ignores the entire `designer` section. Toggling the preview checkbox updates the content used by copy, print, and download. Variables remain a separate export.

Local inference requires Docker access. Configure the local environment with `operator_cache`, `operator_max_bytes`, optional `plugin_directory`, and `inference_workers`. Idle workers are reclaimed automatically. Only changed steps and their descendants are inferred again. Unchanged results are reused within the current browser editing session; reloading the page starts a fresh session. Failures produce diagnostics without falling back to execution nodes.

### YAML editors

Job YAML, Variables, and the read-only export preview use CodeMirror 6. Features include YAML and variable highlighting, line numbers, folding, and search. Editable surfaces also support replacement, two-space indentation, Tab indentation, editor undo/redo, and syntax diagnostics. Editor shortcuts do not operate on canvas history. Diagnostics check YAML syntax only; Apply YAML and the server still check workflow structure and parameter rules. Editing does not automatically execute a job or trigger inference for an unapplied Job YAML draft.

Canvas changes synchronize to Job YAML. Once YAML is edited manually, canvas synchronization does not overwrite it before Apply YAML. Export preview is read-only; the metadata toggle, copy, print, and download use the same original YAML text without line numbers or highlighting markup.

Hovering, focusing, or clicking a question mark updates a description area below the parameter form with its description, type, required status, default, enum, and constraints. The information remains until another parameter is inspected or the step changes. Nested parameters share this area.

Frontend sources live in `frontend/`; `package-lock.json` locks dependency versions. After changing editor sources or dependencies, use Node.js 22 or newer:

```bash
cd xstream-designer/frontend
npm ci --ignore-scripts
npm run build
```

Commit generated `src/main/resources/web/yaml-editor.js` and `editor-licenses.txt` with the sources. Then run `sbt test stage` in the module directory. Regular Scala builds and deployment do not require Node.js. The Scala HTTP service serves all assets locally; browsers do not access a CDN. Third-party license notices remain available at `/editor-licenses.txt`.

### Output structures and samples

Output previews live in the right-hand **Outputs** tab. Parameter and connection changes update port structures automatically. Steps with incomplete inputs report their missing ports individually, preserving successful upstream and independent-branch structures.

Operators supply samples through `previewOutputs` (JVM) or `preview_outputs` (Python); the UI does not synthesize samples from Schema. All operators must implement the sample interface; unavailable samples for current inputs are reported with a reason. Defaults are 10 rows and 16 KiB per port, measured as serialized UTF-8 JSON, with a 256 KiB total sample budget per graph preview. Truncation is explicitly labeled. Structure inference and sample requests are separate: a subsequent sample request adds `preview: {max_rows: 10, max_bytes: 16384}`. Sample methods do not invoke complete-job processing. `read_item` samples use bounded source reads; records preserve field structure, while bytes are shown as Base64. See [structure inference and sample interfaces](https://github.com/rdcn-link/xstream-codex/blob/main/docs/structure-inference.md).

When `read_item` has no explicit `view`, Item metadata selects records when supported and bytes otherwise; explicit choices are preserved. The designer displays the selected view and omits automatically selected `view` values from YAML. Execution uses the same policy. For example, the records view of `dacp://localhost:19860/ds-6zmr-crvh/benchmark` provides station, time, temperature, humidity, and quality Schema fields.

Step cards show only output structure as compact field names and types, without data samples. Full structures and operator-provided samples are available in the right-hand **Outputs** tab.

Schema inference is tracked per step. Operator, parameter, input-binding and referenced-variable changes invalidate the affected step and its descendants; independent branches and upstream results remain visible and are reused. Removing a downstream step does not infer retained steps again. Moving, selecting, renaming, reordering, switching sidebars or editing Job output bindings does not trigger inference. Schema and sample results are tracked separately. The server compares resolved parameters and dependency fingerprints, so an unrelated variable change does not call unchanged operators.

See [Schema display type names](https://github.com/rdcn-link/xstream-codex/blob/main/docs/structure-inference.md#schema-type-names-in-the-interface). Step cards and Outputs use consistent names while preserving nesting, time units and nullability.

Step cards show compact two-column tables with field names and normalized types, without an extra header row. Longer schemas scroll within the table area; long names and types are ellipsized with their full text available on hover. Outputs provides the complete structure.

Step cards show an error icon with `Inference error` for failures, a question-mark icon with `Schema unknown` for unknown structures, and `Inferring…` while inference is pending. Detailed errors remain available in Outputs and Logs.

Outlet accepts at most one output port from one step. Connecting a new port replaces the existing binding; an unconnected Outlet is allowed. Imports and exports enforce this limit. Inference displays schemas first and requests bounded samples separately; slow or failed sample reads do not hide the displayed schemas.

## Validation, incremental inference and editing

Click **Validate** to list errors and warnings in the lower **Validation** panel. Click a step name in an issue to locate and open that step. Checks cover an unconnected Outlet, steps with no dataflow path to Outlet, missing or extra inputs, unknown step references, duplicate consumers, cycles, parameters and variables. Existing inference results also verify output ports and input representations/formats. Missing inference results produce an unverified warning rather than a successful check. Validation does not execute jobs, start inference workers or read data, and cannot guarantee runtime success. Unapplied Job YAML edits are excluded from canvas validation.

The designer receives incremental NDJSON events through `/api/infer_stream`: `step` carries a step name and its inference result, `complete` carries the full report, and `error` indicates an incomplete request. Inference follows dependencies from upstream to downstream and updates the canvas immediately after each step completes. Independent branches are still inferred serially. Request failures preserve structures already received for the current draft; stale draft results are discarded. Bounded samples are requested separately afterward. Outputs displays records samples as tables with fields as columns and records as rows; nested values use compact JSON, long values are available on hover, and bytes samples remain Base64.

Select one step and click **Duplicate step** or press **Ctrl/⌘+D** to create a copy with a new ID. The copy retains its operator, parameters, but not input connections or Outlet bindings, preventing duplicate consumers. Duplication supports undo. After marquee-selecting or Shift-clicking multiple steps, drag any selected step's header to move the group while preserving relative positions and updating connections. Group movement supports undo and can include the selected Outlet.

Editing-session reuse stays in memory, is isolated between browser sessions and expires after 30 minutes without inference requests. At most 32 sessions are retained, each holding up to 2 MiB of the latest step results. This does not add a persistent result cache to node or Union inference APIs. Reload the page to refresh source metadata independently of a parameter edit.

The left sidebar contains **Operators**, **Catalogs** and **Examples**. The canvas displays processing steps, stream connections and Outlet. Local reads use context URIs; remote reads use public service URIs, including public names when the service is a Union.

The data browser displays Target catalog and configured DACP services as a tree. Catalog roots are listed immediately, but datasets and Folder Items load only when expanded. Selecting an Item in Target catalog sets `read_item.params.uri` to `context:/dataset/item`; selecting an Item in another configured catalog retains its complete `dacp://host:port/dataset/item` URI. A context reference belongs to the eventual execution JVM, so its catalog must match the local preview catalog. Configure node credentials in `nodes`; they stay on the designer server. Optional `sql` uses `FROM source`, and schema/sample previews reflect the query.

```yaml
local_catalog:
  datasets:
    weather:
      provider: directory
      source:
        root: /srv/weather
nodes:
  - url: dacp://node-a.example.org:18861
    ca: /etc/dacp/ca.crt
    token_file: /etc/dacp/node-a.token
```

The designer binds one JVM-wide DataCatalog through XStream plugin contracts. `local_catalog.data_catalog` can configure a custom DataCatalogProvider instead of static datasets. See [XStream engine](../docs/xstream.md) and [Job read parameters](../docs/jobs.md).

The Catalogs tree distinguishes host roots, datasets, folders and data Items with separate icons. Roots load at startup and children load on expansion.

Item rows show only their leaf names and file/folder icons. Hover or keyboard focus exposes the complete Item path and available views. Click an Item to reveal compact view/copy icons on an independent floating layer below its row (above when space is limited). Selecting a view sets `read_item` parameters; copying does not change the Job. Escape or clicking outside hides the icons.

[Scientific view selection, structure and samples](../docs/designer-scientific.md): plugin-defined array and sequence forms with compact previews.

## Bundled website showcase

An optional `showcase` configuration loads `job_file` and optional `variables_file` as the first-time visitor's default draft. Both are server-local YAML files, validated at startup and limited to 1 MiB each. Only their public contents and the `label` are returned to the browser; configuration paths are omitted. With a showcase enabled, browser drafts use a separate storage key, survive reload and remain visitor-local. The Load demo button restores the default draft/variables using the ordinary undoable import path. Existing saved drafts take priority over the default. Use only public example data in these files.

```yaml
showcase:
  job_file: /srv/demo/showcase.yaml
  variables_file: /srv/demo/variables.yaml
  label: Load demo
```

Frontend assets and API requests use relative URLs, allowing deployment at a same-origin reverse-proxy prefix such as `/demo/`. The proxy strips that prefix, preserves the public Host/Origin pair and streams incremental responses without buffering the complete inference report. The independent XStream website supplies its own Designer distribution, sample catalog and deployment supervisor; this service does not need to connect to a production node.

## Step descriptions and operator trust

The Step tab provides a multiline Description, up to 8192 characters, saved automatically as the step’s `description` field. Descriptions survive duplication, undo/redo, export and reload; they do not affect inference or execution. Exporting without Designer metadata still retains them.

Operator-library hover details show public trust ratings and download counts. Ratings use five shield-check icons; canvas steps do not display ratings. Trust metadata is not part of the Job and is not a safety guarantee.

The left operator library groups versions by name and shows only the latest public version. Numeric runs sort numerically (`1.10` follows `1.9`), and a release follows its hyphenated prerelease. Existing Jobs continue to resolve their original versions and are not upgraded automatically.

Hover over a Dataset to see its description JSON’s `title` and `description`; the tree retains its addressable Dataset name.

Designer creates logical Jobs: select Items, connect operators, configure parameters, inspect schemas and export YAML.

The operator list displays icons, names and versions. Hover or focus an operator to see its trust shields, numeric score and current-version package download count in the tooltip. Missing counts display Unavailable; built-in operators display Not applicable.

Target is a browser-session selection, defaulting to Local. Every catalog, schema and sample request includes a `target` value (`local` or a configured node URL). The server validates it against `nodes` and keeps credentials private. Switching Target refreshes the catalog, schemas and output samples for the selected design context.

## Confirming and remembering Target

Click **Target: … Change…**, select a configured service, then click **Use selected target**. Changing the selection in the dialog has no effect until confirmed; **Keep current target** or Escape preserves the current context. Switching Target refreshes context-dependent structures and samples. Inference uses a valid browser session identifier and resolves `context:` against the confirmed Target.

Job YAML can store its design target:

```yaml
designer:
  version: 1
  target: dacp://node-a.example.org:18861
```

Use `local` for the Designer server’s local catalog. Loading a Job offers its design target for confirmation. Refreshing restores the draft’s previously confirmed target without a dialog, provided the target remains configured; otherwise the draft opens in Local with a warning to review context Item paths. Unconfigured addresses are never contacted. An unavailable design target prompts the user to select a configured target or retain the current one. Exports save the confirmed Target, and excluding designer metadata also excludes this design target. Execution engines ignore the entire `designer` section: this field neither routes nor submits a Job and grants no permissions.

## Example Jobs

The left sidebar’s **Examples** tab lists ten packaged Jobs: record-stream reading, filtering to CSV, a parameterized workflow, merging two streams, and a NetCDF array view. Each card describes its purpose and step count. **Load example** loads its graph and variable values into the current Target; loading does not execute or submit a Job. Existing canvas content, unapplied YAML or variable values require replacement confirmation. Undo restores the previous graph and variables.

Examples address the sample `weather` dataset through `context:`. Adjust Item paths or variables to match the chosen Target. The merge example reads the same sample Item twice so it works with one sample catalog; change either URI to combine distinct sources. Cards identify unavailable operator versions and disable loading until those versions are available from the configured repository. Built-in `read_item` examples remain available without repository operators.

`/api/examples` returns the packaged YAML documents, variables, descriptions and required operator references. Example files and their index reside in `xstream-designer/src/main/resources/examples/`; all are validated as Job definitions with resolved variables. These resources contain no server credentials or execution-node assignments.

Examples also include local/remote stream merging and a saved remote design target. Replace example.org addresses with configured services. Remote reads do not imply computation pushdown; only Union plans distributed execution. `designer.target` is design-only metadata, ignored during execution.

Schema inference resolves complete `$VAR_NAME` parameter values using the Variables YAML before calling operators, preserving numbers, booleans and structured values. Editing variable values refreshes affected steps and their downstream schemas. Missing variables are reported by name; define them in Variables to resume inference. Variable references remain in the exported Job.

Cross-node templates include two remote nodes, three remote nodes, and Union collaboration. The first two save `designer.target: local`; the Union template saves a remote Union target. Replace example addresses and public dataset paths before use. Loading asks for target confirmation. The same Job can be submitted to Union for automatic distribution; Designer does not assign steps to nodes.

### Deployment-specific examples

Set `examples_directory: /srv/xstream/designer-examples` in the startup configuration to supply an `index.yaml` plus the Job and variables files it names. The index uses `id`, `title`, `description`, `job` and `variables`, matching the packaged index. Files are read and validated at startup, each limited to 256 KiB; only simple YAML filenames within that directory are accepted. Without this option the packaged sample catalog examples are used.

A deployment must use its actual Item paths, configured service addresses and Union public dataset names. Local examples should save `designer.target: local`, so loading them from a remote design context asks to switch back. Remote examples save their configured service target. Validate each example with its variables and target using `/api/infer`, including source schemas and downstream diagnostics. Do not publish credentials in examples.

Example cards display compact feature tags such as **Cross-node**, **Variables**, **Local target**, **Remote target**, **Union**, **Multi-input** and **Scientific array**. Configure `tags: [Cross-node, Variables, Remote target]` on an index entry; labels are presentation metadata, not execution settings. Each example accepts up to eight labels of at most 40 characters each.

Examples are numbered `01`, `02`, … in index order for quick reference. The number is a display position; the example’s stable identifier remains its `id`.
