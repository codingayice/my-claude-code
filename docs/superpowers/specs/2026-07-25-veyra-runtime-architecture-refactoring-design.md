# Veyra Runtime Architecture Refactoring Design

## 1. Status

- Date: 2026-07-25
- Scope: `cn.ayice.veyra` only
- Status: Completed; Slices 1-6 implemented and runtime verified
- Compatibility: preserve Agent business behavior, HTTP routes, SSE event names, and transcript format

## 2. Problem

Veyra evolved from a small Claude Code inspired runtime into a Spring Boot hosted agent client. The current package names suggest separate capabilities, but runtime ownership and dependency direction are not explicit:

- `session.AgentSessionFactory` constructs LLM, memory, tools, permissions, tasks, transport, and loops.
- `session.AgentSession` owns HTTP-specific event and approval implementations.
- `server.application.RunApplicationService` owns asynchronous run lifecycle and its own exception conversion.
- `runtime.AgentLoop`, `runtime.ChatLoop`, and `runtime.AgentRuntime` duplicate parts of run lifecycle.
- `session`, `runtime`, `tool`, `permission`, `context`, and `memory` contain bidirectional dependencies.
- Mutable session state can be reached from multiple HTTP application services without one runtime owner.

The refactoring must solve those structural problems without changing the decisions made by the Agent loop.

## 3. Design Position

Veyra is an agent runtime, not a traditional CRUD domain. The architecture is therefore organized around runtime ownership and execution flow rather than DDD layers.

The target architecture has six major areas:

```text
cn.ayice.veyra
|-- control          Spring MVC, DTOs, SSE, request-level errors
|-- host             active session ownership and run scheduling
|-- kernel           Agent, Chat, and Subagent execution
|-- conversation     history, prompt, context, compaction, memory, transcript
|-- tooling          tool catalog, permission, approval, tasks, built-in tools
|-- llm              fixed LangChain4j model integration
`-- boot             Spring configuration and object composition
```

This design deliberately does not introduce generic adapter, repository, or gateway layers for technologies that are fixed by the project.

Existing behavioral interfaces such as `ChatStreamer`, `AgentEventSink`, `TranscriptRecorder`, and `ToolExecutionConfirmation` remain valid because they model callbacks and test seams, not speculative technology replacement.

## 4. Runtime Model

### 4.1 Control

The control area contains Spring MVC controllers, request/response DTOs, validation, SSE serialization, and application services.

Controllers may only:

1. Receive and validate a request.
2. Invoke one control service.
3. Convert the service result to HTTP.

Control services invoke `RuntimeHost`; they do not reach into `AgentLoop`, `ChatLoop`, tool managers, permission stores, or event buses.

### 4.2 Runtime Host

`RuntimeHost` is the only entry point from the control plane into the active runtime. It owns:

- `SessionRegistry`: active `sessionId -> SessionRuntime` mapping.
- Session creation and transcript-based restoration.
- Run submission.
- Approval resolution.
- Slash command access.
- Session settings access.

`SessionRuntime` is the single owner of mutable state for one session:

- Agent and Chat engines.
- Permission context.
- Pending approvals.
- Slash commands.
- Run event stream.
- Session run queue and lifecycle.

No controller or control service may retain those objects independently.

### 4.3 Execution Kernel

The kernel keeps the current Agent behavior and initially wraps the existing loops:

- `AgentLoop`: current tool-using loop.
- `ChatLoop`: current chat-only loop.
- `AgentRuntime`: current subagent/profile loop.
- `RunCoordinator`: common run selection, failure boundary, and lifecycle completion.

The three loops are not forcibly merged. Shared lifecycle is extracted first; internal turn stages are extracted only after characterization tests prove their behavior.

### 4.4 Conversation

Conversation owns everything that determines what the model sees and what conversation data survives:

- Conversation history.
- System prompt assembly.
- Token estimation and context windows.
- Compaction and post-compaction restoration.
- Session and long-term memory.
- Transcript persistence and restoration.

Session persistence remains at its current capability during this refactoring. Tool-call persistence and complete session snapshots are explicitly deferred.

### 4.5 Tooling

Tooling owns the complete tool call lifecycle:

```text
lookup -> parse -> validate -> permission -> approval -> execute -> normalize result
```

Permission and task management are part of tooling because they exist to govern tool execution. This removes the current top-level `permission <-> tool` ownership conflict.

### 4.6 LLM and Boot

`llm.AIService` remains the concrete LangChain4j implementation.

`boot` is the only area allowed to construct a complete session runtime and therefore the only area allowed to depend on every subsystem. Core classes remain free of Spring annotations.

## 5. Dependency Rules

Target dependencies:

```text
control -> host
host -> kernel, conversation, tooling, interaction
kernel -> conversation, tooling, llm, config
conversation -> llm, config
interaction -> conversation
llm -> config
boot -> all
```

Forbidden dependencies:

- `host` must not depend on `control` or Spring MVC.
- `kernel` must not depend on `control`, HTTP, SSE, or Spring.
- `conversation` must not depend on `host` or `control`.
- `tooling` must not depend on `host` or `control`.
- `tooling` must not depend on `kernel` or `conversation`.
- Only `boot` may perform cross-subsystem object assembly.
- New top-level `shared`, `common`, `util`, or `manager` dumping-ground packages are prohibited.

These rules will be enforced with architecture tests once the first package migration is complete.

## 6. Message Flow

```text
Tauri
  -> Controller
  -> Control Service
  -> RuntimeHost
  -> SessionRuntime queue
  -> RunCoordinator
  -> AgentLoop or ChatLoop
  -> Conversation / LLM / Tooling
  -> session event stream
  -> SSE Controller
  -> Tauri
```

The HTTP run request remains asynchronous. It returns `runId` immediately, while progress and completion continue over the existing SSE protocol.

## 7. State and Concurrency

One `SessionRuntime` owns one conversation state. Runs for the same session must execute in submission order. Runs belonging to different sessions may execute concurrently.

The first implementation slice introduces the ownership boundary and a serial execution queue without changing Agent decisions. Subagent and background task concurrency remains governed by their existing rules until the tooling migration.

All executors must be created by Spring configuration or by a runtime owner with an explicit `close` path. New unmanaged `new Thread` or `Executors.new*` calls are prohibited outside `boot` during the migration.

## 8. Failure Boundaries

The final system has three failure boundaries:

- Request failure: synchronous HTTP validation and resource errors.
- Run failure: asynchronous Agent/Chat execution failures.
- Tool failure: expected tool validation, permission, timeout, and execution failures.

The current external response and event content remains compatible during migration. Internally, exceptions must retain their cause and be logged once with `requestId`, `sessionId`, `runId`, `agentId`, and `toolUseId` when applicable.

Empty catches and logging only `e.getMessage()` are prohibited in new code.

## 9. Compatibility Invariants

Every migration slice must preserve:

- Existing `/v1` routes and response bodies.
- `202 Accepted` run submission behavior.
- Existing SSE event type strings and payload keys.
- Agent, Chat, and Subagent decision logic.
- Tool order, permission decisions, and result messages.
- Context compaction thresholds and recovery behavior.
- Current JSONL transcript location and line format.
- Existing tests, plus new characterization tests.

No feature improvement is combined with an architectural move. Session persistence improvements are deferred.

## 10. Migration Slices

### Slice 1: Runtime Ownership

Introduce:

- `host.RuntimeHost`
- `host.SessionRegistry`
- `host.SessionRuntime`
- `kernel.RunCommand`
- `kernel.RunSubmission`
- `kernel.RunCoordinator`
- `boot.SessionRuntimeFactory`

Move active-session ownership out of `session.SessionService`, move cross-subsystem construction out of `session.AgentSessionFactory`, and make all control services depend on `RuntimeHost`.

The current `session` package is reduced to transcript persistence models and operations.

### Slice 2: Control Package

Move `server.api`, `server.application`, DTOs, error handling, and SSE serialization under `control`. Keep route compatibility and update Spring scanning.

### Slice 3: Tooling System

Create `ToolEngine`, move permission and task behavior under tooling, and remove direct tool orchestration from `AgentLoop` without changing tool execution order.

### Slice 4: Conversation System

Move context, compaction, memory, history, and transcript under conversation. Resolve `context <-> memory` by making token measurement a conversation-owned service and memory extraction a kernel lifecycle hook.

### Slice 5: Kernel Decomposition

Extract turn preparation, model invocation, tool-call coordination, and lifecycle hooks from the current loops. Keep separate Agent, Chat, and Subagent strategies.

### Slice 6: Enforcement and Cleanup

Add ArchUnit dependency tests, complete executor lifecycle management, remove compatibility facades, and document contribution rules.

## 11. Slice 1 Acceptance Criteria

- All control services and SSE controllers obtain active sessions through `RuntimeHost`.
- `session` contains no active runtime map and no object graph factory.
- Complete session construction lives in `boot.SessionRuntimeFactory`.
- Asynchronous run selection and final failure conversion live in `kernel.RunCoordinator`.
- Runs submitted to one session execute serially.
- Runs from different sessions can use the shared executor concurrently.
- Existing HTTP, SSE, runtime, and transcript tests pass.
- New host/kernel tests cover session restoration, serial run submission, and failure event emission.

## 12. Non-Goals

- Changing Agent prompts, tool descriptions, permission rules, or termination behavior.
- Completing session persistence.
- Changing the frontend protocol.
- Replacing LangChain4j, file persistence, Spring MVC, or SSE.
- Splitting the project into Maven modules in the first migration.
- Introducing DDD aggregates or generic adapter abstractions.

## 13. Implementation Record

Slice 1 was completed on 2026-07-25:

- `RuntimeHost` is the only server entry point to active runtime state.
- `SessionRegistry` is the only owner of the active session map and transcript restoration.
- `SessionRuntime` owns one session's mutable loops, settings, approvals, events, and serial run queue.
- `RunCoordinator` owns Agent/Chat selection and the asynchronous run failure boundary.
- `boot.SessionRuntimeFactory` is the only complete runtime object-graph constructor.
- `boot.RuntimeConfiguration` owns the shared executor lifecycle and Spring wiring.
- The former `SessionService`, `AgentSession`, `AgentSessionFactory`, and HTTP event-bus types were removed.
- Session persistence remains intentionally unchanged and incomplete as described in the non-goals.

Slice 2 was completed on 2026-07-25:

- HTTP controllers, application services, DTOs, exception handling, SSE, web configuration, and document export moved under `control`.
- `server` now contains only `AgentServerApplication` as the Spring Boot entry point.
- Control services consume Host-owned read models instead of transcript persistence or kernel models.
- Runtime log streaming moved under Host observability ownership.
- Executable dependency tests prevent Control, Host, Kernel, and transcript persistence from violating their allowed dependency directions.

Slice 3 was completed on 2026-07-25:

- Tool lookup, validation, permission, approval, execution, and empty-result normalization are centralized in `tooling.ToolEngine`.
- Permission, built-in tools, tool state, background commands, and subagent task state moved under `tooling`.
- Main Agent tool calls remain parallel with ordered collection; Subagent tool calls remain sequential.
- Tooling task execution depends on the narrow `SubagentExecution` callback instead of a concrete Kernel runtime.
- The former top-level `tool`, `permission`, and `runtime.task` packages were removed.

Slice 4 was completed on 2026-07-25:

- Context, system prompt, compaction, memory, and transcript persistence moved under `conversation`.
- Long-term memory extraction moved to `kernel.lifecycle` because it launches a Subagent.
- Memory file authorization moved to `tooling.permission` because it governs tool execution.
- `ContextBuilder` now consumes immutable tool schema/description values and working directory values instead of ToolRegistry and PermissionContext.
- Conversation is protected from Kernel, Tooling, Host, Control, Server, and Spring dependencies by ArchUnit.

Slice 5 was completed on 2026-07-25:

- The former `runtime` package was decomposed into `kernel.agent`, `kernel.chat`, `kernel.subagent`, `kernel.model`, `kernel.lifecycle`, and `kernel.event`.
- `ModelCallExecutor` owns streaming timeout, cancellation, and root-cause extraction.
- `AgentTurnPreparer` owns auto/reactive compaction, post-compaction restoration, token evaluation, and request preparation.
- `AgentToolCoordinator` owns main Agent authorization, parallel execution, and ordered result collection.
- Agent, Chat, and Subagent strategies remain separate and keep their existing decisions and event protocols.

Slice 6 was completed on 2026-07-25:

- Spring owns bounded run, task, and I/O executors; business packages no longer create executors or raw threads.
- HTTP, Run, model, memory, and tool failure boundaries retain causes and write diagnostic logs.
- Unexpected HTTP failures return the stable `B0001` response without exposing Java exception details.
- Request, session, and run identifiers are added to logging context.
- Wildcard imports and legacy package entry points were removed.
- ArchUnit prevents legacy packages and reverse Control, Host, Kernel, Conversation, and Tooling dependencies from returning.
- Team contribution rules are documented in `docs/veyra-architecture-development-guidelines.md`.

Runtime verification was completed on 2026-07-25:

- The current-source shaded JAR was rebuilt and started on `127.0.0.1:17361`.
- `GET /v1/health` returned the unified `00000` success response.
- The Vite frontend started on `http://localhost:5173` and returned HTTP 200 with the application root.
- Tauri completed its native development build and started a responsive `veyra_desktop` desktop process with a valid window handle.
- `POST /v1/sessions` created an active session and `GET /v1/sessions/{sessionId}/events` emitted the compatible `session.ready` SSE event.
