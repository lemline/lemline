# Extensions in Lemline: decision and implementation plan

Date: November 16, 2026

Status: implemented; validation details and environment limitations are recorded below.

This document revisits and updates "Extensions in Lemline: implementation proposal".
It sets the architecture and the Lemline conventions needed to move to implementation.
Sections 12 and 13 define the technical contracts and the reference traces to turn into tests.

## 1. Decision

**Prepare extensions at parse time and execute them with a dedicated controller attached to the existing nodes.**

Workflow compilation builds the `before` and `after` blocks and determines the candidate extensions
for each task from its type. It keeps the original tasks at their current positions.
At runtime, an `ExtensionController` evaluates the conditions and drives the before, task and after phases.
Its state travels with the workflow stack.

The added blocks have positions under `_ext`, for example:

```text
/do/0/getUser/_ext/0/before/do/0/sendLog
```

This choice adds extension subtrees to the execution tree. It does not replace each task with
a wrapper containing a relocated copy of the original task.

### Why not a parse-time wrapper?

Building an execution tree distinct from the source definition is possible. The initial claim
that any tree transformation would necessarily move the visible references was too categorical:
a mapping between source positions and execution positions could preserve them.

A specialized wrapper would have one advantage: its before/after blocks would naturally sit outside the
branches of a `fork` and the protected body of a `try`. However, in the current engine:

- `NodePosition` is used for node resolution, the serialized stack, messages, listeners,
  errors, history and identifier derivation;
- introducing a distinct position for the original body would require handling that mapping
  in all these uses; keeping two frames at the same position would not solve the problem;
- the wrapper would still need specific state to keep inputs/outputs, apply transformations
  only once, handle the short circuit and defer the end of the task;
- a plain `do` containing the added steps does not fit: it passes one child's output to the next
  and consumes `exit` at its own boundary.

The wrapper would therefore move part of the complexity to node identity and the shared lifecycle.
For this feature, we keep positions and task processors unchanged, with explicit extension boundaries.
The extension logic stays grouped in a controller rather than scattered across every processor.

## 2. Reference and scope

Lemline already reads `use.extensions`, but its engine does not execute it yet. A targeted task must be able
to run steps before and after its body, or be short-circuited by an extension.

The reference for this implementation is DSL **1.0.0**, consistent with the embedded schemas.
Links are pinned to that version, because the specification's `main` branch evolves independently.

- [DSL reference: extensions](https://github.com/open-workflow-specification/specification/blob/v1.0.0/dsl-reference.md#extension)
- [DSL overview: extensions](https://github.com/open-workflow-specification/specification/blob/v1.0.0/dsl.md#extensions)
- [DSL 1.0.0 schema](https://github.com/open-workflow-specification/specification/blob/v1.0.0/schema/workflow.yaml)
- [Lemline execution model](adr/0002-workflow-execution-model.md)
- [Fork error boundaries](adr/0008-fork-error-management.md)
- [Protobuf contracts](protobuf-messaging.md)

### Contract defined by the specification

| Field | Required | Role |
| --- | --- | --- |
| `extend` | Yes | Targeted task type |
| `when` | No | Expression deciding whether the extension applies in the current context |
| `before` | No | List of tasks executed before the targeted task |
| `after` | No | List of tasks executed after the targeted task |

The schema accepts `call`, `composite`, `emit`, `for`, `listen`, `raise`, `run`, `set`, `switch`, `try`,
`wait` and `all`. The 1.0.0 textual reference also mentions `extension`, which is absent from the schema: we follow
the schema and do not add that value.

The reference explicitly describes short-circuiting the extended task with `then: exit` and provides
a mocked-response example. The logging example states that extension tasks are not
extended in turn and uses `$task.reference` to designate the targeted task.

These examples clarify the expected behavior without defining every execution detail. The conventions
below are Lemline decisions, not additional requirements attributed to the specification.

## 3. Chosen semantics

| Topic | Lemline decision |
| --- | --- |
| Eligibility | `composite` targets declared `do` and `fork` tasks; `call` includes calls to reusable functions. |
| Task condition | If `if` is false, no extension runs. |
| Extension conditions | After input preparation, evaluate every `when` once per task invocation, before any `before`, then keep the selection. |
| Body initialization | Initialize the processor-specific state after the `before` blocks, with the updated context; never initialize it on a short circuit. |
| Order | `before 1`, `before 2`, task, `after 2`, `after 1`, in the order of the selected extensions. |
| Input of `before` blocks | Each block receives the task's transformed input; its final result is ignored, except on a short circuit. |
| Input of `after` blocks | Each block receives the task's final output; its final result is ignored. |
| Flow within a block | Steps of the same block pass their results to each other normally. |
| Context | Explicit exports from extensions modify the shared context. They are not reverted when returning to the task. |
| Short circuit in `before` | Skip the body and all remaining extension blocks; treat the block's result as the task's raw output. |
| Transformations on a short circuit | Apply the task's output transformations and validations, as well as its export, exactly once. |
| Error | `after` is not a `finally`: an uncaught error interrupts the sequence. |
| Retry | Replay the extensions of the tasks actually re-executed, with a fresh evaluation of their `when`. |
| Retry of a `try` body | Do not replay the extension of the `try` itself while that invocation of the `try` remains active. |
| Subworkflows | No implicit inheritance of the parent's extensions; each workflow uses its own definition. |
| Reusable functions | Function tasks executed in the workflow remain eligible, unless they are executed from an extension. |
| Extension recursion | No descendant of an extension block, including a function called by that block, receives an additional extension. |

Resuming from a suspension restores the saved selection and phase. It is not a new
invocation and re-evaluates neither `if`, nor `input.from`, nor the `when` conditions already evaluated.

## 4. Parse-time preparation

Build an immutable plan per workflow definition: declared extensions, prepared blocks and
candidates per node. Data-dependent expressions are still evaluated at runtime.

Existing tasks keep their positions. Added blocks are indexed under the position of their
owner task, with the extension's stable index in its declaration list. An extension that is not
selected at runtime therefore does not change the positions of the following ones.

Prepared nodes carry an explicit origin that distinguishes:

- tasks declared by the user;
- internal wrappers, such as the root, the root `do`, the synthetic blocks of `for`, `try`, `catch`
  and `foreach`, or a function's wrapper;
- extension blocks and tasks, with their owner and phase.

Extension selection excludes internal wrappers and extension subtrees. It must not
rely solely on substring searches in positions.

Remote and recursive functions keep their deferred resolution. Apply the same preparation
when their tree is built, without recursively expanding their calls when the workflow is loaded.
Mixed `_fn` and `_ext` paths must work in both directions.

The cache must be tied to the workflow's effective definition and follow its replacement or removal.
A shared function must not reuse a plan containing the extensions of another calling workflow.
Avoid a global extension cache with an invalidation policy independent from `WorkflowCache`.

## 5. Controller and durable state

### Responsibilities

`ExtensionController` drives transitions between phases. Existing processors keep executing
their task type. The controller receives immutable data and returns a transition and an updated state;
it keeps no instance state in its own fields.

Integration points remain shared: task entry, extension block return, synchronous or asynchronous
body result, finalization and error propagation. `AbstractProcessor` delegates these decisions
to the controller. `StepByStepOrchestrator` respects the boundaries defined in the next section.

### State to carry

Add an optional extension state on `StackFrame`, independent from the `DoState`, `ForState`,
`TryState`, etc. states. It contains the information needed for the continuation:

| Information | Purpose |
| --- | --- |
| Current phase and index | Resume the right block without replaying the previous ones |
| List of selected extensions | Do not re-evaluate `when` after a suspension |
| Raw and transformed task input | Rebuild `$task.input` and restore the input after the `before` blocks |
| Raw and final output, when they exist | Rebuild `$task.output` and restore the result after the `after` blocks |
| Pending navigation directive | Apply the task's `then` after its extensions finish |

The return message separately carries `ExtensionReturn` to distinguish normal completion, short circuit and
global termination; this marker is not duplicated in the frame.

The owner position and the start instant can be found in the frame: do not duplicate
data that is already available. Definitions and secrets are not copied into this state; they are
restored from the engine's usual sources.

Phase-dependent fields must keep their presence, including when their JSON value is
`null`. The absence of an extension state keeps the current execution path.

### Result preparation and finalization

Split the operations currently grouped in `completeTask`:

1. Receive the body's raw output, or the short circuit's.
2. Apply `output.as`, validate the output, apply and validate `export.as`.
3. Keep the prepared result in the continuation and the updated context in the stack.
4. Run the `after` blocks, except on a short circuit.
5. Emit the task's completion, pop its frame and pass on the kept result and the pending directive.

Returning from an `after` does not go through the transformations of step 2 again. A task is not announced
as completed until its last `after` has succeeded. An error in an `after` follows normal error handling
and must not first produce a success event for the owner task.

Extension returns are intercepted before `stateWhenReEnteringFromChild`: they must not advance
the index of a `do` or a `for`, nor move a `try` to the completed state.

### Serialization

Extend `StackFrameProto` with a new field number and the required types, then update
`NodeStackProtobufMapper`. Respect the presence rules and the `*_UNSPECIFIED = 0` enums.
Check the binary protobuf and ProtoJSON formats used in messages and durable operations.
The absence of the new field must remain decodable.
Also add the `PendingTaskState` state and the explicit `ExtensionReturn` return to the contracts described
in section 12. Serialization therefore involves `NodeStackProtobufMapper` and `WorkflowStateProtobufMapper`,
as well as the `TaskScheduled.resume()` conversion.

## 6. Control boundaries

### Errors in extensions

Extension errors follow the error mechanism of ordinary tasks. The controller neither hides them
nor turns them into successes. Suspensions and control directives remain distinct
from business or execution errors.

| Error origin | Chosen behavior |
| --- | --- |
| Invalid extension definition detectable at load time | Reject the definition during its validation; do not silently ignore the extension |
| Invalid `when` expression at runtime or non-boolean result | Expression error failing the owner invocation; do not interpret that error as `false` |
| Uncaught error in a `before` | Stop the remaining extensions; run neither the body nor the `after` blocks; fail the owner |
| Uncaught error in the task body | Do not run the `after` blocks; follow normal error propagation |
| Uncaught error during result preparation | Fail the owner; do not run the `after` blocks |
| Uncaught error in an `after` | Stop the remaining `after` blocks and fail the owner, even if its body succeeded |
| Error caught by a `try` inside the extension | Apply its catch/retry; if the block ends normally, resume the extension sequence |
| Failure received after a suspension | Same propagation as a synchronous error, from the saved phase and frames |
| No applicable handler | Fail the invocation, then the branch or the workflow according to the existing boundaries |

Extension task errors include expression and validation errors,
`raise`, failing external calls and the timeouts already supported by the engine. Keep
the type, status and position of the original error for `catch` filters and diagnostics.
Do not replace them all with a generic "extension" error.

Since every `when` is evaluated before the first `before`, a selection error prevents any
execution of an extension block and of the body for that invocation. The diagnostic must identify
the extension and its `when`. Handlers inside blocks not yet executed cannot
catch this error; it looks for a handler outside the owner, including when
the owner is itself a `try`.

### Retry scope and effects already produced

A retry inside an extension block only replays the scope of that `try`. In particular, a local
retry in an `after` keeps the task's output and does not re-execute its body.

A retry decided by an outer `try` re-executes its body according to Lemline's ordinary rules,
hence potentially several tasks, their extensions and the business call that already succeeded. There is no
implicit resumption limited to the failing `after` when a retry covers a wider scope.

Example: a payment succeeds, then its logging `after` fails. An outer retry surrounding
the payment may make a new payment call. If only the logging must be retried,
its retry must be placed in the extension. If its failure must be tolerated, its `catch` must handle it
explicitly. Extensions have no implicit error-ignoring policy.

External calls, emitted business events and context exports already applied are not reverted
by the failure of a later block. Deterministic IDs provide the existing redelivery guarantees;
they do not automatically make a business call idempotent when it is made again on a retry.

### `exit`, `end` and named jumps

The top-level block of an extension must pass an explicit outcome to the controller: normal completion
or short circuit. The ordinary behavior of `DoProcessor` is not enough, because it absorbs `exit` when leaving
its own block.

An `exit` in a nested block keeps its local scope. Only one that leaves the top-level `before` block
triggers the short circuit of the extended task. In a top-level `after` block, `exit` stops
the remaining `after` blocks and finalizes the task with its kept result.

`end` keeps its workflow-termination scope; it does not trigger additional `after` blocks
while unwinding. Named jumps remain resolved within their task sequence. The extended task's own
`then` stays pending until its finalization, including when it was short-circuited.

### `fork`

Identify the relationship between the child node and its parent: body branch or extension block.
A return from `before` or `after` to a `fork` does not produce `ForkBranchCompleted`.
An error in the `fork`'s extension does not produce `ForkBranchFaulted` for a fictitious branch.

On the other hand, extensions of tasks located in a real branch stay in that branch and
respect its error boundary. The competitive and cooperative fork behaviors remain those of ADR-0008.

### `try`

A `try`'s `catch` protects its body, not its own extensions. An error in a `before` or `after`
attached to the `try` therefore looks for an outer handler, after any handlers inside the extension
block. An error in the extension of a task in the body can be handled by this `try`.

Stack cleanup on retry also removes the extension states of abandoned tasks.
The extension state of a still-active `try` is kept when only its body is re-executed.

## 7. Expressions and task identity

In `when` and in extension blocks, `$task` describes the owner task: its name, its reference,
its original definition, its raw input, its start instant and its raw output when it exists.
Keep this descriptor across suspensions and function calls within the extension.

The block's local variables, the current data, `$input`, `$output` and `$context` follow the normal flow
of the executed steps. Updating the inputs/outputs of an extension step must not accidentally
overwrite its owner's `$task` descriptor.

The node actually executed keeps its `_ext` position for diagnostics, navigation and events.
The owner task keeps its historical position. Scope construction must therefore be adapted, rather than
globally replacing the executed step's position with the owner's.

Use `$task.definition.call` and `$task.definition.with`. The `$task.call` shorthand in the official
mocking example is inconsistent with the documented descriptor: fix the test examples without introducing
an additional alias. Also fix the example's expressions and endpoints so that it actually
triggers the mock.

## 8. Listeners, CloudEvents and dashboard

### Listens in extensions

Listen extraction must traverse the prepared extension blocks, in addition to the workflow's tasks
and functions. Adapt the filter cache, `DefinitionListenService`, `ListenerService` and
`ListenerQueryKey` to find positions tied to an owner, including under `_fn`.

Two extensions or functions with similar relative positions must not be confused.
A test must check the routing of an event to a listen created in an extension: an in-memory
engine test alone covers neither this indexing nor the SQL queries.

### Lifecycle CloudEvents contract

Reuse `LifecycleEventHook` and `CloudEventLifecycleHook`, as well as the existing `task.created`,
`task.started`, `task.completed`, `task.faulted` and `task.retried` types. The controller does not create
a separate channel or event family for extensions.

The owner task represents its complete invocation, extensions included. Tasks declared
in extension blocks each have their own lifecycle, at their `_ext` position.

| Moment | Expected emission |
| --- | --- |
| Scheduling and entry of the owner task | `task.created` then `task.started` at its original position, not repeated at each phase change |
| Execution of a `before` or `after` task | Ordinary events at its `_ext` position, with its own input, output or error |
| Return from a suspension or an extension block | No new `task.started` for the same owner invocation |
| Body finished, `after` still running | No `task.completed` for the owner |
| Last `after` finished | Owner `task.completed` with the task's kept output |
| Short circuit by `before` | Owner `task.completed` with the mocked output after transformation; no execution of the body or skipped blocks |
| Extension error caught within that extension | Failure of the step concerned; the owner may continue |
| Extension error that fails the owner | `task.faulted` for the step and the owner, with no premature owner success |

When unwinding on error, also close abandoned invocations, even if an outer `try` catches
the error and lets the workflow continue. This closing must happen before the frames concerned are lost
and must not duplicate events already emitted by the existing error paths. Otherwise, the dashboard may
leave a task running or classify it as interrupted when it actually failed.

A retry opens a new logical attempt for the tasks actually replayed.
Retry events must make it possible to attach sub-step failures to the right owner
and the right attempt. Do not replay the start or end of the owner `try` while it remains active.

The `data.task` field contains the position of the task actually described by the event. The `$task`
used in an extension's expressions may designate the owner, but must never be
used in place of that identity to build the CloudEvent. Keep the path of the failing
step in `error.instance` to precisely locate an extension error.

Check that stack counters and derived identifiers distinguish extensions, iterations
and attempts, while staying deterministic when the same command is redelivered. This is about
logical uniqueness and deduplication, not a guarantee of exactly-once physical delivery.

DSL `emit` tasks contained in extensions keep using the normal emission mechanism
for business CloudEvents. Their business event and their lifecycle events have distinct roles;
also test that an extension `emit` does not recursively trigger another extension.

### Transport, analytics and projection

The path remains the one already used: lifecycle hook, publication, analytics ingestion,
reading/streaming by the gateway, then projection in the dashboard.

`LifecycleAnalyticsService` keeps the complete CloudEvent and its workflow identity attributes.
The dashboard projection extracts the position from `data.task`. Keep the
`lemlineworkflowid`, `lemlineworkflownamespace`, `lemlineworkflowname` and `lemlineworkflowversion` attributes.
`_ext` paths remain strings in the existing contracts; check that they are preserved and
that events are deduplicated along this path before concluding that no contract change
is needed. No analytics migration is planned for the mere addition of these paths.

### Display in the dashboard

The dashboard keeps the rows of the original tasks and presents `_ext` tasks as their
sub-steps. This behavior requires an explicit frontend adaptation:

- `workflow-source.ts`: resolve `_ext/<index>/<phase>` segments to the right entry of
  `use.extensions`, then to its task list; support mixed paths with `_fn`;
- display the extension's name and the "before" or "after" phase, without counting these technical segments
  as additional tasks in the indentation computation;
- resolve the real type of each step: an extension `listen` or `wait` must be recognized as
  a wait, not as a task of unknown type;
- `timeline.ts`: keep the owner's total duration until the end of the `after` blocks, the inputs/outputs
  of the owner and of each sub-step, as well as the corresponding errors and attempts;
- do not turn the internal `_ext`, `before`, `after` blocks and their `do` wrappers into
  user task rows;
- avoid attaching the sub-steps of one iteration or attempt to another invocation of the same
  owner;
- keep a consistent result during streaming, and after reloading the complete history.

The presence of sub-steps currently makes `timeline.ts` classify a task as a group, and
groups are excluded from the activity periods computed by `buildScale`. An extended HTTP task must
therefore not have the time of its own body taken for an idle period and artificially compressed.
Adapt this rule without counting the durations of the owner and its sub-steps twice.
Group intervals describe the complete invocation; they do not claim to measure exclusively
the time spent in its body.

These rules also cover error states: a failing extension must be visible under its
owner, and the owner must appear neither successful nor indefinitely running if that error
ends its invocation.

## 9. Implementation steps

1. **Compile blocks and candidates.** Extend `WorkflowParser`, the `Node` metadata and
   `WorkflowCache`; classify nodes explicitly; integrate the deferred preparation of functions.
2. **Resolve the added positions.** Add `_ext` to `Token`, index the prepared blocks and check
   `Workflow.getNode` on mixed paths with `_fn`, without moving existing nodes.
3. **Introduce the continuation.** Add the optional state to `StackFrame`, its protobuf contract and
   its mappings, `PendingTaskState` and `ExtensionReturn`; test the different phases, the absence of the
   new fields and the event-to-command conversion.
4. **Implement the controller.** Wire entry, extension returns, asynchronous results and finalization;
   defer body initialization until after the `before` blocks, split result preparation from completion
   emission, adapt the `$task` scope and pass the updated stack to error handling.
5. **Handle the boundaries.** Adapt `AbstractProcessor`, `StepByStepOrchestrator` and retry cleanup
   to distinguish body from extension; cover selection/execution errors, local
   and outer retries, `exit`, `end`, `fork` and `try`.
6. **Integrate position consumers and the lifecycle.** Complete listener indexing and routing,
   check CloudEvents up to the analytics/gateway projection, then adapt
   `workflow-source.ts`, `timeline.ts` and their tests to display sub-steps, states and durations.
7. **Validate the feature.** Run the matrix below, the protobuf checks and the targeted
   integration tests in the existing suites.

## 10. Test plan

Use `FullOrchestrator` with `serde = true` for engine scenarios: commands and events
then go through protobuf at every transition. Add explicit resumptions after clearing/rebuilding
the caches to check that the continuation does not depend on an object kept in memory.

| Case | Check |
| --- | --- |
| Workflow without extensions | Existing results, positions and behavior unchanged |
| `extend: all` logging | Blocks run around declared tasks, with no recursion and no extension of internal wrappers |
| `call`, `composite` and simple-type selection | Type matching, including functions, `do` and `fork` |
| `when` true/false and `if` false | Correct selection; no effects when the task is skipped |
| Invalid extension at load time | Definition rejected with a diagnostic; no silent omission |
| Failing or non-boolean `when` | Expression error, no block or body executed, appropriate outer handler |
| Two extensions | Order before 1, before 2, task, after 2, after 1 |
| Block results different from the task's data | Original input and output preserved; explicit exports kept |
| Mock with `exit` | No call to the original task; mocked result transformed and exported once |
| Direct and nested `exit`, `end`, named jump | Scopes respected and the task's directive kept |
| Extended `wait`, HTTP, `listen` or subworkflow task | The `after` blocks follow the real asynchronous end |
| Suspension in `before` and in `after` | Phase, data, selection and `$task` preserved without replaying the body |
| Non-idempotent transformation and export | Applied only once during normal before/after progress |
| Loop and child returns | Extension blocks do not advance the processor's own state |
| Extension on `fork`, in both modes | No fictitious-branch return or failure; real-branch errors unchanged |
| Extension error in a `try` body | Error handled by the appropriate `try` |
| Extension error of the `try` itself | Outer handler used |
| Failure in a `before` | No body or `after` executed; steps already succeeded not reverted |
| Failure in one `after` among several | No remaining `after` executed; prepared output kept for diagnostics, no owner success |
| `try` inside the extension | Error handled locally without prematurely failing the owner |
| Local retry of an `after` | Only the inner scope is replayed; the business body runs once |
| Outer retry after an `after` failure | Outer scope replayed, including the business body; number of calls checked explicitly |
| Error after suspension with serialization | Same catch, same error information and same cleanup as in synchronous execution |
| `catch` filters | Original type, status and position preserved for an extension error |
| Retry | Extensions of replayed tasks reset; extension of the active `try` kept |
| Function in an extension and extension in a function | `_fn`/`_ext` resolution, scope and no recursion |
| Function shared between two workflows | No mixing of their extensions in the cache |
| Subworkflow | No inheritance of extensions; own scope restored |
| Resumption with rebuilt caches | Same continuation and same data |
| Deferred initialization of a `for` | The collection uses the context exported by the `before` blocks, even after suspension |
| Short circuit before initialization | No body-specific expression or creation of its state; mocked output handled normally |
| Explicit block return | `ExtensionReturn` preserved by `TaskScheduled.resume()`, protobuf and ProtoJSON; wrong owner or wrong phase rejected |
| Invalid states | `PendingTaskState` without extension, unknown phase and missing required data rejected without re-executing the body |
| Protobuf and ProtoJSON | Round trip of all phases, JSON `null`, and old message without extension state |
| Redelivery and retries | Stable identifiers for the same command and distinct ones for different invocations |
| Listener in an extension | Indexing, correlation and actual routing to the right owner |
| History | One logical end of the task after its `after` blocks, distinct sub-steps and original references kept |
| Capture of real CloudEvents | Correct types, `data.task`, input/output, workflow attributes and IDs for owner and sub-steps |
| `after` error caught by an outer `try` | Sub-step and owner closed as failed, no premature success, workflow allowed to continue |
| `emit` in an extension | Business CloudEvent emitted through the usual path, distinct from lifecycle events |
| Analytics and gateway streaming | `_ext` paths, errors and outputs preserved; redelivery deduplicated |
| Frontend extension resolution | Correct name, phase, type, definition and indentation, including mixed paths with `_fn` |
| Extended wait or HTTP call in the timeline | Correct sub-step state; total duration kept; body time not compressed as idle |
| Loops and retries in the timeline | Sub-steps attached to the right invocation and the right attempt |
| Streaming then reload | Same final state, same rows and same results |

Capture the real CloudEvents with `CloudEventLifecycleHook`, not only the calls to a fake
hook. Projection and timeline tests must use traces representative of the contract
emitted by the engine. Add the cases to `LifecycleEventHookTest`, the relevant analytics/gateway suites,
`tests/lib/timeline.test.ts` and `tests/hooks/use-watch-workflow-projection.test.ts`; also cover the
resolution functions of `workflow-source.ts` in the frontend tests.

Listener tests stay in the existing suites of the module concerned. Any query
or repository change must be tested with PostgreSQL, MySQL and H2. Do not create a test layout
or a CI workflow specific to this feature.

Capture the complete test logs in `/tmp/lemline-<module>-extensions.log`, then inspect them before
rerunning. Run `buf lint` from `lemline-messages-proto` when the contract changes.

## 11. Completion criteria

- The logging and mocking examples work with the documented syntax fixes.
- Every phase resumes on another worker from the message and the loaded definition alone.
- Original tasks keep their positions and extension results do not replace their data.
- Errors, retries and control boundaries satisfy the rules of this document.
- Listens in extensions receive their events and the history displays sub-steps correctly.
- CloudEvents and their projection correctly close owners as succeeded or failed,
  with no identity collision, and the dashboard shows the expected types, phases, attempts and durations.
- The targeted suites and serialization checks pass; the user documentation describes the chosen conventions.

## 12. Execution technical contract

### 12.1. Frame before body execution

Add `PendingTaskState(startedAt)` as an explicit variant of `NodeState`, with an empty scope.
This state represents a started invocation whose body has not been initialized. It is not an activity
`TaskState` and must never be passed to a processor expecting a `ForState`, `TryState`, etc.

For a node with candidate extensions, the entry order is as follows:

1. Build the task descriptor and apply the shared input-validation rules,
   `if` and `input.from`, in their current order. A skipped task does not create an extension continuation.
2. Create the owner frame with `PendingTaskState`, then evaluate the `when` conditions in the prepared context.
   This selection is a synchronous operation: no message carries a partial selection.
3. Keep the complete selection and start the first applicable `before`. If no extension is
   selected, continue on the ordinary path without repeating the task start or the transformations.
4. After the last `before`, rebuild the scope from the updated stack and the kept transformed
   input, then call `stateWhenEnteringFromParent` exactly once to initialize the body.
5. Replace `PendingTaskState` with the specific state in the same frame, move to `BODY` and start
   executing the body without going through `enterFromParent` again.

This rule also applies to expressions evaluated during initialization, such as `for.in`.
Exports from the `before` blocks are visible at this step. The extension selection, the transformed input
and the logical start instant are not recomputed. Make the state creations concerned accept that instant,
instead of substituting the body's start time.

A selection, initialization or result-preparation error uses the current stack at
the time of the error. `resumeWithHandler` must not blindly handle these errors with the
entry stack from before the frame was created or modified. Adapt the delegation to error
handling to keep the frames and context actually available, without global mutable state.

### 12.2. Minimal domain model

The following types set the contract; imports and serialization mechanisms follow the module's
conventions. Add `extension: ExtensionExecutionState? = null` to `StackFrame`.

```kotlin
data class PendingTaskState(override val startedAt: Instant) : NodeState()

enum class ExtensionPhase { BEFORE, BODY, AFTER, READY }

data class ExtensionExecutionState(
    val selectedExtensionIndices: List<Int>,
    val phase: ExtensionPhase,
    val activeExtensionIndex: Int?,
    val rawInput: JsonElement,
    val transformedInput: JsonElement,
    val preparedResult: PreparedTaskResult? = null,
)

data class PreparedTaskResult(
    val rawOutput: JsonElement,
    val transformedOutput: JsonElement,
    val pendingFlowDirective: FlowDirective?,
)

enum class ExtensionReturnKind { COMPLETE, EXIT, END }

data class ExtensionReturn(
    val blockPosition: NodePosition,
    val kind: ExtensionReturnKind,
)
```

`FlowDirective` here designates the domain type from `com.lemline.core.tasks`, not the SDK type nor a protobuf type.
Indices are those of the `use.extensions` declaration, not those of a renumbered filtered list.
The controller derives the expected block from the owner position, the phase and `activeExtensionIndex`.

| Phase | Frame-specific state | Required data and invariant |
| --- | --- | --- |
| `BEFORE` | `PendingTaskState` | Inputs kept; index of a selected extension that has a `before`; no prepared result |
| `BODY` | Original processor state | Inputs kept; no active index; no prepared result |
| `AFTER` | Body state kept | Prepared result present; index of a selected extension that has an `after` |
| `READY` | Body state, or `PendingTaskState` after a short circuit | Prepared result present; no active index; only the controller may finalize |

Transitions with no work to do, for example the absence of `before` or `after`, are executed
within the same processing. Do not publish a `BEFORE` or `AFTER` state with a nonexistent active block.
`READY` allows explicit finalization, without requiring an additional message or database write.
Result preparation and the context update precede any publication of an `AFTER` continuation.

### 12.3. Block return and resume commands

Add `extensionReturn: ExtensionReturn? = null` to `WorkflowEvent.TaskScheduled` and to
`WorkflowCommand.ResumeFromTask`. `TaskScheduled.resume()` copies this field without loss.
The block's result stays in the `rawInput` of that event or command; it is not duplicated
in `ExtensionReturn`.

The return of a block's top-level synthetic `do` produces:

- `nodePosition` = the owner's position;
- a stack unwound down to the owner;
- `extensionReturn.blockPosition` = the position of the block that just finished;
- `extensionReturn.kind` = `COMPLETE`, `EXIT` or `END`;
- `flowDirective = null`, since the extension return already carries the boundary decision.

Nested blocks keep using the ordinary directives; they do not build this marker.
A named jump is consumed within its sequence. An `END` goes up to the extension boundary while keeping
its global scope. Failures follow the existing error path, with no successful return marker.

On receipt, the controller checks that the owner is the current frame, that it is waiting for the indicated block
and that the phase accepts this return. It consumes the marker before any update of the specific state.
An inconsistent marker is a diagnosed internal error; it must not restart the body by default.

| Input received | Expected state | Handling |
| --- | --- | --- |
| `ResumeFromTask`, owner absent from the stack | New invocation | Entry path and selection described in 12.1 |
| `ResumeFromTask` with a `COMPLETE` return | `BEFORE` | Ignore the block's result; start the next `before` or initialize the body with the kept input |
| `ResumeFromTask` with an `EXIT` return | `BEFORE` | Prepare the result from the block's result; move to `READY` without initializing the body or running the `after` blocks |
| `ResumeFromTask` without marker, owner active | `BODY` | Return from a body child: call the original processor and keep its navigation logic |
| Synchronous result or `ResumeWithCompletedTask` of the owner | `BODY` | Prepare the output and export exactly once; start the `after` blocks in reverse order or move to `READY` |
| `ResumeFromTask` with a `COMPLETE` return | `AFTER` | Ignore the block's result; start the next `after` or move to `READY` |
| `ResumeFromTask` with an `EXIT` return | `AFTER` | Move to `READY` with the kept result; skip the remaining `after` blocks |
| `ResumeFromTask` with an `END` return | `BEFORE` or `AFTER` | Global termination with the received data, following the existing `end` path; do not start the body, the remaining blocks or a new output preparation |
| Local finalization | `READY` | Emit the completion, pop the frame and pass on the kept output and directive |
| `ResumeWithFaultedTask` or synchronous error | Any phase concerned | Use the failing node, the updated stack and the boundaries of section 6 |

An asynchronous resumption of a task located in an extension targets that task, which is at the top of the stack,
and not its owner directly. The owner stays in `BEFORE` or `AFTER` until its block returns.
A `ResumeWithCompletedTask` directly targeting an owner still in `BEFORE` is inconsistent.

On a block return, pop the block and increment the owner's counter once, then do not
increment it again when consuming the marker. Phase changes alone do not
modify the counters. Ordinary body returns keep their current rules.
The same replayed command must produce the same counters and identifiers; a new attempt
of the `try` remains distinguished by the state and counters of its scope.

### 12.4. Integration with existing points

| Point | Required adaptation |
| --- | --- |
| `AbstractProcessor.enterFromParent` | Split shared preparation, pending frame and body initialization |
| `AbstractProcessor.reEnterFromChild` | Intercept the extension return before the state cast, the index increment and the processor's navigation |
| `AbstractProcessor.completeTask` | Split result preparation, transition to the `after` blocks and actual finalization |
| Boundary of the synthetic extension block | Preserve `exit` and build `ExtensionReturn` before fork/foreach branch-end detection |
| `StepByStepOrchestrator.startTask` and `completeTask` | Route according to the continuation; do not recreate the task start or reinitialize the body on a resumption |
| `processTaskFaulted` and `hasForkBranchFaulted` | Examine the body/extension relationship and the phase before any `TryState` cast or branch handling |
| `TryProcessor.cleanStateStack` | Remove abandoned continuations; keep that of the still-active `try` and the ancestor frames, notably the owner's prepared result during a retry inside its `after` |
| `NodeStack` | Provide immutable updates of the continuation; state copies and updates must keep the `extension` field |
| `Scope.withTask` and input/output helpers | Rebuild the owner descriptor from the frame, without overwriting its data with that of an extension step |
| `TaskScheduled.resume()` and `WorkflowStateProtobufMapper` | Carry the block return to the next worker |

An owner `try` in the `BEFORE` phase has a `PendingTaskState`, not a `TryState`. It cannot
intercept errors from its own extensions. In the `AFTER` phase, its state is indeed a `TryState`,
but the rule stays the same. A handler's eligibility therefore depends on the boundary crossed,
not only on the type of its state.

### 12.5. Protobuf contract and compatibility

At the time of writing, the following additions use free numbers. Recheck the schemas
before changing them if the branch has evolved; never move a published field to free up a number.

| Existing message | Addition |
| --- | --- |
| `StackFrameProto` in `state/node_stack.proto` | `ExtensionExecutionStateProto extension = 4` |
| `NodeStateProto.state` in `state/node_state.proto` | `PendingTaskStateProto pending_task_state = 8` |
| `TaskScheduledProto` in `workflow/events.proto` | `ExtensionReturnProto extension_return = 5` |
| `ResumeFromTaskProto` in `workflow/commands.proto` | `ExtensionReturnProto extension_return = 5` |

Define `PendingTaskStateProto` with `google.protobuf.Timestamp started_at = 1`. Group the other
new types in `internal/state/extensions.proto`, with no dependency on command
or event messages, to avoid circular imports.

| New message | Fields |
| --- | --- |
| `ExtensionExecutionStateProto` | `repeated int32 selected_extension_indices = 1`, `ExtensionPhaseProto phase = 2`, `optional int32 active_extension_index = 3`, `ExtensionJsonValueProto raw_input_json = 4`, `ExtensionJsonValueProto transformed_input_json = 5`, `PreparedTaskResultProto prepared_result = 6` |
| `PreparedTaskResultProto` | `ExtensionJsonValueProto raw_output_json = 1`, `ExtensionJsonValueProto transformed_output_json = 2`, `FlowDirectiveProto pending_flow_directive = 3` |
| `ExtensionReturnProto` | `string block_position = 1`, `ExtensionReturnKindProto kind = 2` |

Enums use the Buf prefixes: `EXTENSION_PHASE_PROTO_UNSPECIFIED = 0`, then `BEFORE = 1`,
`BODY = 2`, `AFTER = 3`, `READY = 4` with the same prefix;
`EXTENSION_RETURN_KIND_PROTO_UNSPECIFIED = 0`, then `COMPLETE = 1`, `EXIT = 2`, `END = 3` with that prefix.

Nested messages have their own presence. Require inputs and outputs when their phase
requires them: a present `google.protobuf.Value` containing JSON `null` is not equivalent to an absent field.
Also require the presence of `active_extension_index` in `BEFORE`/`AFTER`, because index `0` is valid.
Reject unknown or `UNSPECIFIED` enums in a present continuation instead of guessing a phase.

An old message without `extension` follows the ordinary path for frames already active: do not
retroactively add `before` or `after` blocks to them. New task entries use the loaded
plan. `PendingTaskState` without a continuation is invalid at a serialization boundary.
Messages without `extension_return` keep their previous meaning; an owner resumption in
`BEFORE`/`AFTER` without the expected marker is rejected.

Reading old messages with the new engine is required. The reverse is not guaranteed: an old
worker cannot execute `_ext` nor the new `NodeState` variant. Deploy the workers that support
these contracts before enabling workflows that use extensions; a mixed deployment must
not hand these executions to old workers.

## 13. Reference traces for tests

These traces set the values and boundaries to check. Internal navigations with no extension
decision may be grouped in one table row. Every engine command and event
nevertheless goes through protobuf in the tests. The CloudEvents listed concern declared tasks;
creations precede their starts, even when they are grouped in the table.

Notation: `R` is the root frame, `D` the main `do` block; a stack is written from the root to the top.
Abbreviated CloudEvent names designate the existing types, with no new event type.

### 13.1. Suspension in a `before`, then initialization of a `for`

Fixture:

- input `I = {"keep":"input"}`, initial context `{}`;
- task `iterate`, position `P = /do/0/iterate`, whose `for.in` reads `${ $context.items }`;
- an `extend: for` extension at index `0`, with a `before` containing `pause` (`wait`), then
  `chooseItems` (`set` producing `{"items":[1,2]}` and exporting these items to the context);
- loop body: task `remember` producing `{"seen": $item, "keep": .keep}`;
- no `after`, no additional output transformation for the owner.

`B = P/_ext/0/before/do`, `W = B/0/pause`, `U = B/1/chooseItems`.

| Command or transition | Stack after processing | Owner phase and data | Emission or assertion |
| --- | --- | --- | --- |
| First entry into `P` | `[R,D,P]` | `PendingTaskState`, `BEFORE`, selection `[0]`, index `0`, inputs `I` | Logical start of `P`, scheduling of `B`; `for.in` not evaluated |
| Entry into `B`, then `W` | `[R,D,P,B,W]` | Unchanged | `created/started(W)`, then `WaitStarted(W)` |
| Serialization and cache rebuild | `[R,D,P,B,W]` | Identical, no `ForState` | No new execution or start emission for `P` |
| `ResumeWithCompletedTask` of `W` | `[R,D,P,B]` | Still `BEFORE` | `completed(W)`, ordinary return to `B` |
| Return to `B`, then execution of `U` | `[R,D,P,B]` | `P` inputs still `I`; context `{"items":[1,2]}` | `created/started/completed(U)`; block result `{"items":[1,2]}` |
| End of `B` | `[R,D,P]` | Still `BEFORE` until the return is consumed | `TaskScheduled(P, extensionReturn = COMPLETE(B))`, block result in `rawInput` |
| Conversion and receipt of `ResumeFromTask(P)` | `[R,D,P]` before body entry | Replaced by `ForState`, phase `BODY`, collection `[1,2]`, loop index `0` (no child executed yet) | `for.in` evaluated once with the updated context; body input = `I`, not the block result |
| Two normal iterations | Body frames under `[R,D,P]` | `BODY`; loop state progressing normally | Two invocations of `remember` with `seen = 1`, then `2` |
| End of the body and result preparation | `[R,D,P]` | `READY`, final result `{"seen":2,"keep":"input"}` | No second pass through `input.from` or the `when` conditions |
| Finalization of `P` | `[R,D]` | `P` continuation popped | One logical `completed(P)` with this result |

Check the `$task` scope in `W` and `U`: it describes `P`, while their CloudEvents describe their
own positions. Replaying the completion command of `W` from the same state must reproduce the same IDs.
A variant with `exit` in the `before` must produce the mocked output without ever evaluating `for.in`.

### 13.2. Failure of an `after` and outer retry

Fixture:

- `G = /do/0/guarded` is a `try` allowing one retry for a status `503` error;
- its body `T = G/try` contains an HTTP call `P = T/0/charge`;
- an `audit` extension at index `0` targets this call and defines only an `after`;
- `A = P/_ext/0/after/do` contains the call `L = A/0/writeAudit`;
- the payment mock returns `{"paymentId":"p-1"}`, then `{"paymentId":"p-2"}`;
- the audit mock fails once with a `503` error, then succeeds with `{"logged":true}`.

| Command or transition | Stack after processing | Continuation and result | CloudEvents and assertions |
| --- | --- | --- | --- |
| Entry into `G`, `T`, then `P` | `[R,D,G,T,P]` | `G.attemptIndex = 0`; `P` moves straight to `BODY`, selection `[0]` | Start of `G` and `P`; a single payment call at this point |
| `ResumeWithCompletedTask(P)` | `[R,D,G,T,P]` | Prepared result `p-1`, phase `AFTER`, index `0` | Scheduling of `A`, no `completed(P)` |
| Entry into `A`, then `L` | `[R,D,G,T,P,A,L]` | Result `p-1` kept | `created/started(L)`, audit call |
| `ResumeWithFaultedTask(L, 503)` | `[R,D,G]` after handling by `G` | Frames `T`, `P`, `A`, `L` abandoned; `G.attemptIndex = 1` | `faulted(L)` and `faulted(P)` before the frames are lost, no `completed(P)`; `TaskRetryScheduled` to `T` and retry notification |
| Retry resumption after serialization | `[R,D,G,T]`, then new frame `P` | New selection and new state for `P`; no `AFTER` continuation from the previous attempt | New attempt of `P`; `G` does not restart |
| Second payment call succeeds | `[R,D,G,T,P]` | Prepared result `p-2`, phase `AFTER` | Two payment calls in total; still no premature success of `P` |
| Second audit succeeds and `A` ends | `[R,D,G,T,P]` | Explicit return `COMPLETE(A)` with `{"logged":true}` | `completed(L)`; owner result still `p-2` |
| Return consumed, then finalization of `P` | `[R,D,G,T]` | `READY`, then continuation popped | `completed(P)` with `{"paymentId":"p-2"}` |
| End of `T`, `G` and the workflow | `[R]` at the end | Final output `{"paymentId":"p-2"}` | `G` and the workflow complete normally |

The existing retry notification targets the `T` scope. The dashboard projection must attach
the retry to the failing chain concerned, notably the owner `P` and its sub-step `L`, and not
stop at the deepest last error. Check two consistent attempts and no invocation
left running after the first audit failure.

In the variant with the retry placed in a `try` inside `A`, the frames of `P` and its prepared
result remain present during the retry: one payment call, two audit calls and a single
final success of `P`. This difference in call counts is part of the mandatory assertions.

## 14. Implementation clarifications

- Wire maps `google.protobuf.Value` to nullable `Any?`, collapsing absent values and JSON null. The four presence-sensitive continuation values therefore use `ExtensionJsonValueProto { google.protobuf.Value value = 1; }`. Presence belongs to the enclosing message: an empty present wrapper means JSON null; an absent wrapper is rejected. This adjustment preserves the domain contract and the proposed field numbers.
- The existing `ForProcessor` initializes its index to `0`, before scheduling its first child. The reference trace preserves that convention; it does not require changing iteration semantics for existing workflows.
- The dashboard source and timeline implementation from PR #69 is now merged into `main`, which is the base of the extensions PR.

- The current `InstanceMessageCodec` persists `pb64:`-prefixed protobuf bytes, despite the older repository policy describing ProtoJSON DB payloads. Extension continuations use that existing codec without a storage migration. Canonical ProtoJSON interoperability is checked independently with Buf-generated fixtures. The wrapper's presence is authoritative: an empty `ExtensionJsonValueProto` represents JSON null in Wire, while an absent wrapper is invalid for required data.
- Listener indexing now uses a definition-specific function reference in the stored filter key, while the serialized message retains its exact execution position. Legacy suffix keys remain readable; new registrations cannot collide merely because two functions share a relative task path. No table or column is added.
- Error unwinding also closes previously abandoned ordinary try-body blocks. This intentionally adds their missing `task.faulted` event; workflow results and control flow are unchanged.

- A fresh events worker hydrates remote-function listener filters only for active, persisted function registrations. This preserves lazy remote resolution. While remote references remain unresolved, ingestion performs an additional distinct-key lookup scoped to the arrival namespace; it does not scan or deserialize workflow payloads. A function that cannot be resolved is logged and skipped for that event, so the other listeners and event schedules of the namespace keep matching; its filter is hydrated again on the next event, and events arriving in between are not delivered to that listener.
- Remote function resolution errors encountered during workflow navigation now enter the caller's error boundary, including a local try inside an extension. They no longer escape the orchestrator before extension cleanup.

### Validation record

The implementation is rebased onto `main` after dashboard PR #69 merged. The new base has the same source tree as the previously tested dashboard branch; only the dependency notes changed during the rebase.

- Kotlin sources and Wire generated messages compile with the repository's Kotlin/Wire versions and JVM 17 bytecode target.
- The complete core/common test selection passes, including the existing workflow execution fixtures, lifecycle replay tests, and the new extension cases. Targeted serialization tests cover all phases, absent fields, index zero, JSON null, explicit returns, and a canonical ProtoJSON fixture produced by Buf.
- Listener repository and routing scenarios run in the existing H2/PostgreSQL/MySQL suites. H2 and service tests pass locally; Docker-dependent PostgreSQL/MySQL executions are skipped by the existing `RequiresDocker` condition in this environment.
- Transport and actual durable `pb64:` payload round trips pass, as do analytics payload and gateway streaming tests.
- Dashboard tests, TypeScript/production build, and ESLint pass. Source lookup, local/outer retries, loop ownership, streaming/reload, and active HTTP body durations are covered.
- `buf lint`, `buf build`, and `buf breaking` against the starting branch pass.
- Gradle cannot initialize its local lock/socket services in this sandbox. The checks above were run with the cached Kotlin compiler, Wire generator, and JUnit Platform/Kotest launchers. Full logs are in `/tmp/lemline-<module>-extensions.log`. A normal Gradle run and live PostgreSQL/MySQL validation remain required before merge.

### Runner end-to-end coverage

`ExtensionTaskTestCases` adds 14 shared workflow scenarios to the existing InMemory, Kafka, RabbitMQ,
and PGMQ execution suites (56 runner test instances), as well as `FullOrchestrator` with `serde = true`.
The runner scenarios exercise command/event transport, durable wait/retry/listener/parent/fork services,
and lifecycle ingestion into analytics. HTTP activities use the existing mock configuration mechanism.

Assertions cover frozen selection and block ordering, delayed loop initialization, short circuits,
prepared HTTP results across an after suspension, actual listener delivery, child-workflow completion,
mixed `_fn`/`_ext` paths after cache eviction, local versus outer retry invocation counts, both fork modes,
and error origin/owner closure after suspension. The lifecycle assertions read the persisted analytics
history, including subsequent pages, and wait for late event batches instead of treating workflow
completion alone as proof that its tasks were closed.

Run these cases through the normal runner suite, without a separate CI workflow:

```bash
set -o pipefail
./gradlew :lemline-runner:test --tests '*ExtensionExecutionTest' \
  --tests '*AnalyticsWorkflowResultAwaiterTest' 2>&1 | tee /tmp/lemline-runner-extensions.log
```

Validation after adding these cases: 762 core/common tests pass (6 existing skips), including all 14
shared extension scenarios; the runner test sources compile and the three analytics-awaiting harness
tests pass. A subsequent normal Gradle run executed 97 tests: 89 passed and 8 failed. Of the 56 runner
extension scenarios, 48 passed; both cache-eviction listener cases failed on each of the four transports.
The test cleared a live worker's catalog while leaving its startup guard initialized, then delivered
the event before rebuilding the catalog. The test now invokes the normal database-backed
`DefinitionCacheSync.refresh()` after eviction, matching the catalog initialization of a fresh worker.
The adjusted runner sources compile; a normal Gradle rerun remains required because the sandbox still
blocks the Gradle wrapper lock file. The original run is preserved in
`/tmp/lemline-runner-extensions.log`; local recheck output is in `/tmp/lemline-runner-extensions-recheck.log`.
