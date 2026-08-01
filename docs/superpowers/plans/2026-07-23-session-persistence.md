# Session Persistence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Claude Code style local session persistence so agent sessions survive backend restarts and can be resumed from JSONL transcripts.

**Architecture:** Move session ownership out of the HTTP transport layer into `cn.ayice.veyra.session`. Store each session as append-only JSONL under `~/.mycc/projects/<workspace>/<sessionId>.jsonl`, and restore runtime history by reading transcript entries back into LangChain4j `ChatMessage` objects.

**Tech Stack:** Java 17, JUnit 5, Jackson, LangChain4j message types, existing JDK HTTP server and SSE transport.

---

### Task 1: Transcript Storage

**Files:**
- Create: `src/main/java/cn/ayice/veyra/session/SessionPathResolver.java`
- Create: `src/main/java/cn/ayice/veyra/session/TranscriptEntry.java`
- Create: `src/main/java/cn/ayice/veyra/session/TranscriptStore.java`
- Test: `src/test/java/cn/ayice/veyra/session/TranscriptStoreTest.java`

- [ ] Write failing tests for workspace-sanitized transcript paths and JSONL append/read.
- [ ] Implement the path resolver and append-only JSONL store.
- [ ] Run `mvn -q -Dtest=TranscriptStoreTest test`.

### Task 2: Transcript Restore

**Files:**
- Create: `src/main/java/cn/ayice/veyra/session/TranscriptMessageMapper.java`
- Create: `src/main/java/cn/ayice/veyra/session/TranscriptRestorer.java`
- Test: `src/test/java/cn/ayice/veyra/session/TranscriptRestorerTest.java`

- [ ] Write failing tests for user, assistant, and tool-result restoration.
- [ ] Implement message mapping from supported transcript roles to `ChatMessage`.
- [ ] Run `mvn -q -Dtest=TranscriptRestorerTest test`.

### Task 3: Runtime Recording Hooks

**Files:**
- Create: `src/main/java/cn/ayice/veyra/session/TranscriptRecorder.java`
- Modify: `src/main/java/cn/ayice/veyra/runtime/AgentLoop.java`
- Modify: `src/main/java/cn/ayice/veyra/runtime/ChatLoop.java`
- Test: `src/test/java/cn/ayice/veyra/runtime/ChatLoopTest.java`

- [ ] Write failing tests showing `ChatLoop` starts from restored history and records new user/assistant messages.
- [ ] Add constructor injection for initial history and recorder.
- [ ] Record main loop user, assistant, tool result, and compacted history snapshots.
- [ ] Run focused runtime tests.

### Task 4: Session Service Boundary

**Files:**
- Create: `src/main/java/cn/ayice/veyra/session/AgentSession.java`
- Create: `src/main/java/cn/ayice/veyra/session/AgentSessionFactory.java`
- Create: `src/main/java/cn/ayice/veyra/session/SessionRecord.java`
- Create: `src/main/java/cn/ayice/veyra/session/SessionService.java`
- Delete: `src/main/java/cn/ayice/veyra/transport/http/SessionManager.java`
- Modify: `src/main/java/cn/ayice/veyra/transport/http/AgentHttpServer.java`
- Test: `src/test/java/cn/ayice/veyra/session/SessionServiceTest.java`

- [ ] Write failing tests for create, list, and resume of persisted sessions.
- [ ] Move session lifecycle and agent assembly into `session`.
- [ ] Keep HTTP handlers as thin adapters over `SessionService`.
- [ ] Run focused session and HTTP tests.

### Task 5: Full Verification

**Files:**
- All changed backend files.

- [ ] Run `mvn -q test`.
- [ ] Fix any compile or behavior failures.
- [ ] Review package dependencies so transport does not own domain assembly.
