# Claude Code Memory Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the old JSON-backed memory persistence model with Claude Code-style file-based memory.

**Architecture:** Markdown files are the source of truth. The backend owns paths, scanning, prompt construction, recall, and permission boundaries; agents write memory files through normal file tools. Compact summaries stay in conversation history and never become long-term memory.

**Tech Stack:** Java 21, JUnit 5, LangChain4j message types, existing veyra agent/tool abstractions.

---

### Task 1: Memory Paths And File Shape

**Files:**
- Modify: `src/main/java/cn/ayice/veyra/memory/MemoryManager.java`
- Modify: `src/main/java/cn/ayice/veyra/config/AppConfig.java`
- Test: `src/test/java/cn/ayice/veyra/memory/MemoryManagerTest.java`

- [ ] Write failing tests for Claude Code-style `projects/<sanitized-root>/memory/MEMORY.md`.
- [ ] Run `mvn -Dtest=MemoryManagerTest test` and verify failure on old `projects/<id>/memory/topics` layout.
- [ ] Implement flat memory directory, frontmatter topic files, and `MEMORY.md` index rows.
- [ ] Run `mvn -Dtest=MemoryManagerTest test` and verify pass.

### Task 2: Memory Prompt Injection

**Files:**
- Modify: `src/main/java/cn/ayice/veyra/context/systemprompt/MemorySection.java`
- Test: `src/test/java/cn/ayice/veyra/context/systemprompt/MemorySectionTest.java`

- [ ] Write failing tests that the prompt injects CLAUDE.md instructions, memory rules, and `MEMORY.md`, but not topic bodies.
- [ ] Implement Claude Code-style memory instructions in the system prompt.
- [ ] Run `mvn -Dtest=MemorySectionTest test`.

### Task 3: Instruction Memory Loading

**Files:**
- Modify: `src/main/java/cn/ayice/veyra/memory/InstructionMemoryLoader.java`
- Test: `src/test/java/cn/ayice/veyra/memory/InstructionMemoryLoaderTest.java`

- [ ] Add tests for `.claude/rules/*.md`, `@path` includes, and frontmatter `paths` matching.
- [ ] Implement upward project instruction discovery and conditional rule loading.
- [ ] Run `mvn -Dtest=InstructionMemoryLoaderTest test`.

### Task 4: Remove Backend JSON Memory Saves

**Files:**
- Modify: `src/main/java/cn/ayice/veyra/memory/MemoryCommandHandler.java`
- Modify: `src/main/java/cn/ayice/veyra/memory/MemoryExtractionService.java`
- Test: `src/test/java/cn/ayice/veyra/memory/MemoryCommandHandlerTest.java`
- Test: `src/test/java/cn/ayice/veyra/memory/MemoryExtractionServiceTest.java`

- [ ] Replace `/memory add` expectations with path/list/show/delete behavior.
- [ ] Make background extraction produce a constrained agent prompt instead of parsing JSON into `MemoryManager.save`.
- [ ] Run targeted memory tests.

### Task 5: Recall And Permission Boundary

**Files:**
- Create: `src/main/java/cn/ayice/veyra/memory/MemoryRecallService.java`
- Create: `src/main/java/cn/ayice/veyra/memory/MemoryPermissionPolicy.java`
- Test: `src/test/java/cn/ayice/veyra/memory/MemoryRecallServiceTest.java`
- Test: `src/test/java/cn/ayice/veyra/memory/MemoryPermissionPolicyTest.java`

- [ ] Add tests for at most five recalled topic files and excluding `MEMORY.md`.
- [ ] Add tests that Write/Edit outside memory dir are denied for memory extraction.
- [ ] Implement recall scanning and permission checks.

### Task 6: Agent Integration

**Files:**
- Modify: `src/main/java/cn/ayice/veyra/server/SessionManager.java`
- Modify: `src/main/java/cn/ayice/veyra/agent/AgentLoop.java`
- Test: `src/test/java/cn/ayice/veyra/agent/AgentLoopMemoryCommandTest.java`

- [ ] Pass canonical workspace path into memory paths.
- [ ] Detect main-agent memory writes and skip background extraction when they happen.
- [ ] Keep compact summary out of long-term memory files.
- [ ] Run `mvn test`.
