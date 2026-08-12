# Veyra 持久化审批挂起与统一 Run 控制设计

## 1. 文档状态

- 日期：2026-08-12
- 状态：设计提案，待实施
- 前置基线：`veyra-event-sourced-session-runtime-design.md` 已实施
- 适用范围：`runtime.agent`、`runtime.session`、`tool`、`session.state`、`session.persistence`、`control` 与桌面端审批 UI
- 变更性质：开发阶段破坏性改造，不兼容旧事件、旧 Snapshot、旧审批接口和旧开发数据

本文只定义工具审批的持久化挂起、恢复和统一控制协议，不重复定义 Session Event Stream、Run Snapshot、Run 树、检查点回退和 SessionView 的通用设计。

## 2. 设计结论

工具审批必须是 Agent 状态机中的持久化挂起点：

```text
tool.approval.requested
  -> AgentPhase.WAITING_APPROVAL
  -> AgentLoop 返回 SUSPENDED
  -> 当前执行任务结束并释放线程

统一 Run 控制请求 action=resume, cause=approval
  -> tool.approval.resolved
  -> 重新调度同一个 runId
  -> 从 Snapshot + Event Tail 重建 AgentState
  -> 继续授权或工具执行
```

系统不保留 Java 调用栈，不使用 `CompletableFuture#get()` 等待用户，也不把内存 Future 作为恢复凭证。事件中的待审批事实和可重建的 AgentState 是唯一恢复依据。

外部控制只提供一个统一入口和一个统一请求结构，通过 `action + cause + input` 表达恢复、审批、取消等操作。不得为每种控制情况分别创建 `ResolveApprovalCommand`、`ResumeRunCommand`、`CancelRunCommand` 等类。

## 3. 背景与问题

当前审批实现使用同步接口：

```java
Choice ask(ToolExecutionRequest request, String reason);
```

当权限策略返回 `ASK` 时，`ToolApprovalQueue` 创建 `CompletableFuture<Choice>`，写入审批事件后调用 `future.get()`；用户决定通过 `future.complete(choice)` 唤醒原调用栈。

该方式能用较小改动连接异步 UI 与同步 AgentLoop，但有以下结构性问题：

1. 审批期间占用 Run 执行任务和平台线程。
2. Future、线程和调用栈不能持久化，进程重启后无法继续原 Run。
3. `WAITING_APPROVAL` 已是领域状态，但运行控制仍依赖进程内对象，形成两套事实来源。
4. 重启恢复只能将未决审批和 Run 标记为 interrupted，无法继续等待用户。
5. `tool.approval.resolved` 与 `future.complete()` 之间存在持久化事实和内存 continuation 不一致的崩溃窗口。
6. 每增加一种恢复原因就创建一种 Command 类，会让简单的控制协议演化成大量只有字段差异的类型。

本设计将审批从“阻塞式回调”改造成“事件驱动的状态机让出与重新调度”。

## 4. 目标与非目标

### 4.1 目标

1. 审批等待期间不占用 Agent 执行线程。
2. 服务重启后保留待审批状态，用户仍可批准、拒绝并恢复同一个 Run。
3. `tool.approval.requested` 是挂起事实，`tool.approval.resolved` 是恢复输入。
4. AgentLoop 只根据重建后的 AgentState 决定下一步，不依赖旧调用栈。
5. 使用一个统一 Run 控制 HTTP 入口和一个统一请求结构。
6. `action`、`cause` 和 `input` 足以扩展控制行为，不为每个动作创建 Java Command 类。
7. 审批决定、状态推进和 Run 重新调度具有幂等、并发和崩溃安全边界。
8. 支持一轮多个工具审批，并在全部审批得到决定后恢复工具批次。

### 4.2 非目标

- 不恢复 Java 栈帧、线程、Future、Executor Task 或 HTTP 请求。
- 不实现任意代码位置的通用 continuation。
- 不保证外部工具 exactly-once。
- 不为每一种事件创建独立 Java 类。
- 不引入通用工作流图、节点 DSL 或 LangGraph 兼容层。
- 不兼容当前 Future 审批队列产生的旧开发数据。

## 5. 核心模型

### 5.1 审批是领域状态，不是等待对象

挂起状态必须完整存在于 `AgentState`：

```java
public record PendingApprovalState(
        String approvalId,
        String toolUseId,
        String tool,
        String arguments,
        String reason,
        ApprovalStatus status,
        String decision
) {
}
```

`PendingApprovalState` 是状态模型，不是控制命令。它可以由事件重建，也可以进入 Run Snapshot。

```java
public enum ApprovalStatus {
    PENDING,
    RESOLVED
}
```

工具与 Run 阶段保持明确：

```text
ToolCallPhase.DECLARED
  -> ToolCallPhase.WAITING_APPROVAL
  -> ToolCallPhase.AUTHORIZED 或 RESULT_RECORDED/REJECTED

AgentPhase.MODEL_RESULT_RECORDED
  -> AgentPhase.WAITING_APPROVAL
  -> AgentPhase.EXECUTING_TOOLS 或 TOOL_BATCH_COMPLETED
```

### 5.2 Loop 返回执行结果

AgentLoop 不再假设一次调用必须执行到终态。它返回统一步骤结果：

```java
public record AgentStepResult(
        String status,
        String reason,
        Map<String, Object> output
) {
}
```

约定：

| `status` | 含义 |
| --- | --- |
| `completed` | Run 已进入终态 |
| `suspended` | Run 仍活动，但当前没有可安全继续的动作 |
| `failed` | 本次推进失败，Run 已写入相应失败事实 |

审批挂起示例：

```json
{
  "status": "suspended",
  "reason": "approval",
  "output": {
    "pendingApprovalIds": ["approval-1", "approval-2"]
  }
}
```

这里使用统一结果结构，通过参数区分状态，不为 `SuspendedResult`、`CompletedResult` 和 `FailedResult` 分别创建类。

## 6. 统一 Run 控制协议

### 6.1 HTTP 入口

```http
POST /v1/sessions/{sessionId}/runs/{runId}/control
```

所有 Run 外部控制都进入该端点。原审批专用 decision 端点在本次破坏性改造中删除。

### 6.2 请求结构

```java
public record RunControlRequest(
        String action,
        String cause,
        Map<String, Object> input,
        Long expectedRevision,
        String commandId
) {
}
```

字段定义：

| 字段 | 必填 | 语义 |
| --- | --- | --- |
| `action` | 是 | 控制动作，首版支持 `resume`、`cancel` |
| `cause` | 是 | 动作原因，首版支持 `approval`、`recovery`、`user_requested` |
| `input` | 是 | 与当前动作相关的 JSON 数据；无输入时传空对象 |
| `expectedRevision` | 是 | 客户端看到的 Session revision，用于乐观并发控制 |
| `commandId` | 是 | 客户端生成的幂等键 |

审批决定并恢复：

```json
{
  "action": "resume",
  "cause": "approval",
  "input": {
    "approvalId": "approval-123",
    "decision": "allow_once"
  },
  "expectedRevision": 42,
  "commandId": "cmd-456"
}
```

用户取消：

```json
{
  "action": "cancel",
  "cause": "user_requested",
  "input": {},
  "expectedRevision": 42,
  "commandId": "cmd-457"
}
```

`decision` 首版允许：

- `allow_once`
- `allow_for_session`
- `deny`

### 6.3 统一分发

控制层保持一个应用服务入口：

```java
public RunControlResult controlRun(
        String sessionId,
        String runId,
        RunControlRequest request
) {
    return switch (request.action()) {
        case "resume" -> resume(sessionId, runId, request);
        case "cancel" -> cancel(sessionId, runId, request);
        default -> throw unsupportedAction(request.action());
    };
}
```

内部方法是行为分支，不是新的 Command 数据类型。新增控制场景时优先扩展允许值和输入校验；只有数据结构与生命周期真正不同，且共享结构已无法维持不变量时，才考虑新增类型。

### 6.4 返回结构

```java
public record RunControlResult(
        String status,
        String runId,
        long revision,
        Map<String, Object> output
) {
}
```

审批被接受后返回 `202 Accepted`：

```json
{
  "status": "accepted",
  "runId": "run-123",
  "revision": 43,
  "output": {
    "approvalId": "approval-123"
  }
}
```

接受表示审批事实已持久化且恢复任务已经可靠提交，不表示工具已经执行完成。

## 7. 事件协议

继续使用统一 `SessionEvent(type, payload)`，不为事件创建独立 Java 类。

### 7.1 `tool.approval.requested`

```json
{
  "approvalId": "approval-123",
  "toolUseId": "tool-123",
  "tool": "Bash",
  "arguments": "{...}",
  "reason": "需要用户确认"
}
```

Reducer 行为：

1. 对应 Tool 进入 `WAITING_APPROVAL`。
2. 添加 `PendingApprovalState(status=PENDING)`。
3. 只要当前批次存在未决审批，Run 进入 `WAITING_APPROVAL`。

### 7.2 `tool.approval.resolved`

```json
{
  "approvalId": "approval-123",
  "toolUseId": "tool-123",
  "decision": "allow_once",
  "commandId": "cmd-456"
}
```

Reducer 行为：

1. 将审批标记为 `RESOLVED`。
2. `deny` 将 Tool 变成待写拒绝结果的已决状态。
3. `allow_once` 和 `allow_for_session` 将 Tool 变成 `AUTHORIZED`。
4. 若仍有未决审批，Run 保持 `WAITING_APPROVAL`。
5. 若全部审批已解决，根据 ToolBatch 推导为 `EXECUTING_TOOLS` 前的可推进状态。

`tool.approval.resolved` 只记录用户决定，不意味着工具已经启动。真实副作用之前必须另写 `tool.execution.started`。

### 7.3 不新增通用恢复事件

重新调度是运行时动作，不是领域事实，因此不追加 `run.resumed`、`approval.resume.requested` 或 `checkpoint.restored`：

- `tool.approval.resolved` 已完整表达恢复输入。
- 当前是否已调度属于可重试运行时状态。
- Agent phase 由审批、工具和 Run 事件推导。
- `checkpoint.restored` 只用于用户切换历史 Run 路径，与审批恢复无关。

## 8. 正常审批流程

### 8.1 挂起

```mermaid
sequenceDiagram
    participant Loop as AgentLoop
    participant Engine as SessionEngine
    participant Store as EventStore
    participant UI as Desktop UI

    Loop->>Engine: 权限结果 ASK
    Engine->>Store: append tool.approval.requested
    Store-->>Engine: revision + 1
    Engine->>Engine: Projection => WAITING_APPROVAL
    Engine-->>UI: 发布最新 SessionView
    Loop-->>Engine: AgentStepResult(suspended, approval)
    Note over Loop,Engine: 本次执行结束，释放线程和调用栈
```

授权管线不得再调用同步 `ask()`。权限判断改为返回统一授权结果：

```java
public record Authorization(
        String status,
        PermissionDecision decision,
        ToolExecutionRequest request,
        String approvalId,
        String rejectionReason
) {
}
```

`status` 使用 `allowed`、`denied`、`approval_required`。`ToolService` 只判断和描述结果，不等待用户。

### 8.2 决定与恢复

```mermaid
sequenceDiagram
    participant UI as Desktop UI
    participant API as Run Control API
    participant Store as EventStore
    participant Queue as Session Run Queue
    participant Loop as AgentLoop

    UI->>API: action=resume, cause=approval
    API->>API: 校验 run、phase、approval、revision、commandId
    API->>Store: append tool.approval.resolved
    Store-->>API: durable revision
    API->>Queue: submit same runId
    API-->>UI: 202 Accepted
    Queue->>Loop: advance(rehydrated AgentState)
    Loop->>Store: tool.execution.started 或 tool.rejected
```

应用服务必须在同一个 Session 单写者临界区内完成：

```text
校验状态
  -> 幂等追加 tool.approval.resolved
  -> 更新内存 Projection
  -> 确保同一 runId 已加入恢复队列
```

事件落盘后、队列提交前若进程崩溃，重启扫描会看到“审批已解决且 Run 可推进”，并自动重新调度。不得依赖一次性的内存 enqueue 才能保证恢复。

## 9. 多工具审批

同一 Assistant Message 可以声明多个 ToolUse。授权阶段先稳定计算整个批次，再一次性写出需要人工处理的审批请求：

```text
tool-1 = AUTHORIZED
tool-2 = WAITING_APPROVAL
tool-3 = WAITING_APPROVAL
tool-4 = DENIED
```

规则如下：

1. 批次中任一 Tool 等待审批时，Run 为 `WAITING_APPROVAL`。
2. UI 可以逐项提交决定，每次仍使用统一 Run 控制入口。
3. 每次决定后都可以提交恢复调度；Session 单写者与 runId 去重保证不会并发推进同一 Run。
4. AgentLoop 发现仍有 `PENDING` 审批时立即再次返回 `suspended`。
5. 全部审批解决后，先为拒绝项写入 `tool.rejected`，再执行已授权工具。
6. 工具结果仍按模型声明顺序写回上下文；允许并行执行不改变稳定结果顺序。

第一版不增加“批量审批 Command 类”。如 UI 需要一次处理多个审批，仍使用相同结构：

```json
{
  "action": "resume",
  "cause": "approval",
  "input": {
    "decisions": [
      {"approvalId": "a1", "decision": "allow_once"},
      {"approvalId": "a2", "decision": "deny"}
    ]
  },
  "expectedRevision": 42,
  "commandId": "cmd-batch-1"
}
```

单项 `approvalId + decision` 和批量 `decisions` 必须互斥，由统一请求校验器处理。

## 10. 并发、幂等与事务边界

### 10.1 乐观并发

`expectedRevision` 必须等于当前 Session revision。否则返回 `409 Conflict` 和最新 SessionView/revision，客户端刷新后重新决定。

这可以阻止用户在以下情况下批准已经失效的工具：

- Run 已被取消；
- Session 已回退到其他检查点；
- 当前活动路径已切换；
- 另一客户端已经处理该审批；
- 工具批次已被其他恢复动作推进。

### 10.2 命令幂等

`commandId` 在 Session 内唯一：

- 相同 `commandId` 和相同规范化请求：返回第一次结果，不重复追加事件和调度。
- 相同 `commandId` 和不同请求：返回 `409 COMMAND_ID_REUSED`。
- 不同 `commandId` 重复解决同一审批：返回 `409 APPROVAL_ALREADY_RESOLVED`。

幂等记录可以进入可重建 SessionIndex；事件中的 `commandId` 是最终校验依据。

### 10.3 调度去重

Session Run Queue 对 `(sessionId, runId)` 只允许一个 `queued/running` 推进任务。重复 resume 不产生并行 AgentLoop。

调度不是事实来源。重启时只要状态满足“活动、非终态、无未决审批且存在可推进行为”，就应重新提交。

## 11. 重启恢复

重启时不再把未决审批自动写成 `tool.approval.interrupted`。

| 重建状态 | 恢复行为 |
| --- | --- |
| `WAITING_APPROVAL` 且存在 PENDING | 保持挂起，SessionView 重新展示审批 |
| `WAITING_APPROVAL` 但审批均 RESOLVED | 自动重新调度同一 Run |
| 存在 AUTHORIZED 且未 started 的 Tool | 可以安全调度并在执行前写 started |
| Tool 已 `EXECUTION_STARTED` 无结果 | 写 `tool.execution.uncertain`，不得盲目重试 |
| Run 已终态 | 不调度 |

恢复入口与正常审批后的恢复共用同一个 `advance(sessionId, runId)`，不得分别维护“正常 Loop”和“恢复 Loop”。

## 12. 检查点、回退与 Run 路径

审批恢复和检查点恢复是不同语义：

- 审批恢复：继续当前活动路径上的同一个非终态 Run。
- 检查点回退：将 Session head 切换到历史终态 Run。
- 从检查点继续：以历史终态 Run 为 `parentRunId` 创建新的 Run 子节点。

当 Run 正在 `WAITING_APPROVAL` 时，第一版禁止直接执行检查点回退。用户必须先取消当前 Run，形成终态后才能切换路径。这样可以避免同一审批同时属于失效路径和活动路径。

若后续允许“取消并回退”原子操作，也继续使用统一控制协议，通过参数表达组合意图，不创建新的命令类；但必须在一个 Session 单写事务中先追加 `run.cancelled`，再追加路径切换事件。

## 13. UI 行为

UI 是 SessionState 之上的体验层，不持久化 UI 事件。

1. SessionView 中 `phase=WAITING_APPROVAL` 时展示所有 PENDING 审批。
2. 冷启动和 SSE 重连都从完整 SessionView 恢复审批卡片。
3. 点击决定后发送统一 Run 控制请求。
4. 收到 `202 Accepted` 后按钮进入 submitting 状态，但不本地假定工具已经执行。
5. 新 SessionView 显示审批已解决后移除卡片。
6. `409` 时刷新最新 View，并提示审批已处理或 Run 状态已变化。
7. 后端重启期间审批卡片可以保持；连接恢复后以新 SessionView 校正。

实时 `permission.requested/resolved` 可以继续作为非持久化提示，但 UI 正确性只能依赖 SessionView，不能依赖是否收到某次实时事件。

## 14. 接口与代码改造

### 14.1 删除

- `ToolExecutionConfirmation.ask(...)` 同步等待接口。
- `ToolApprovalQueue` 中的 `ConcurrentHashMap<String, CompletableFuture<Choice>>`。
- 审批路径中的 `future.get()` 和 `future.complete()`。
- `/sessions/{sessionId}/approvals/{approvalId}/decision` 专用接口。
- 重启时自动追加未决 `tool.approval.interrupted` 的逻辑。

### 14.2 保留并调整

- 权限规则、`PermissionDecision` 和 `allow_once/allow_for_session/deny` 语义。
- 统一 `SessionEvent` 与 `payload` 事件载体。
- `tool.approval.requested/resolved` 领域事件。
- `AgentPhase.WAITING_APPROVAL` 和 `ToolCallPhase.WAITING_APPROVAL`。
- Session 单写者和 Session Run Queue。
- SessionView 的待审批投影。

### 14.3 新增的最小类型

本设计只要求下列共享边界类型：

1. `RunControlRequest`：所有外部 Run 控制输入。
2. `RunControlResult`：所有 Run 控制结果。
3. `AgentStepResult`：AgentLoop 的统一执行结果。
4. `PendingApprovalState`：可持久化、可投影的领域状态。

事件、动作、原因、决定和输出通过字符串/枚举值与 `Map<String, Object>` 区分。禁止为每个动作、原因和事件建立一一对应的数据类层级。

## 15. 错误协议

| HTTP | 错误码 | 条件 |
| --- | --- | --- |
| `400` | `INVALID_RUN_CONTROL` | 缺少字段、action/cause 组合非法或 input 结构错误 |
| `404` | `RUN_NOT_FOUND` | runId 不属于该 Session |
| `404` | `APPROVAL_NOT_FOUND` | approvalId 不存在 |
| `409` | `REVISION_CONFLICT` | expectedRevision 过期 |
| `409` | `RUN_NOT_ACTIVE` | 目标不是当前活动 Run |
| `409` | `RUN_NOT_SUSPENDED` | 审批恢复时 Run 不处于等待状态 |
| `409` | `APPROVAL_ALREADY_RESOLVED` | 审批已经处理 |
| `409` | `COMMAND_ID_REUSED` | commandId 对应不同请求 |
| `422` | `UNSUPPORTED_RUN_CONTROL` | action/cause 已识别但当前阶段不支持 |
| `500` | `EVENT_APPEND_FAILED` | 事件未可靠落盘，不得调度恢复 |

错误响应继续使用项目统一 API Envelope，不为每个错误创建响应类。

## 16. 实施顺序

### 阶段一：状态与 Reducer

1. 补全 `tool.approval.requested/resolved` 对 AgentPhase、ToolCallPhase 和 PendingApprovalState 的投影。
2. 删除恢复扫描中对未决审批的 interrupted 收敛。
3. 增加“全部审批已解决后可推进”的纯状态判断。

### 阶段二：非阻塞授权

1. 将 `ToolService.authorize()` 改为返回 `allowed/denied/approval_required`。
2. 删除 `ToolExecutionConfirmation.ask()` 和审批 Future。
3. 让 `AgentToolCoordinator` 在存在审批时写事件并返回 suspended。
4. 让 AgentLoop 返回统一 `AgentStepResult`。

### 阶段三：统一控制入口

1. 增加 `/runs/{runId}/control`。
2. 实现 `RunControlRequest` 校验、expectedRevision 和 commandId 幂等。
3. 在审批事件可靠落盘后重新提交同一个 runId。
4. 删除旧审批 decision API。

### 阶段四：恢复与 UI

1. 重启后保留 Pending Approval。
2. 对“审批已解决但尚未调度”的 Run 自动补调度。
3. 桌面端改用统一控制 API。
4. 验证冷加载、SSE 重连、重复点击和 revision 冲突体验。

## 17. 测试要求

### 17.1 Reducer 测试

- requested 将 Tool 和 Run 推进到 `WAITING_APPROVAL`。
- 单项 resolved 正确更新审批和 Tool。
- 多项审批未全部解决时保持挂起。
- 全部解决后状态变为可推进。
- 重复 resolved、未知 approvalId 和终态 Run 上 resolved 被拒绝。

### 17.2 Runtime 测试

- AgentLoop 遇到审批后在限定时间内返回，不残留等待线程。
- Run Queue 可以在审批挂起期间执行其他 Session 的 Run。
- 决定审批后继续同一 runId，不创建新 Run。
- 重复 resume 不并行执行同一个 Run。
- deny 生成配对 ToolResult，不执行真实工具。
- allow_for_session 在恢复后更新权限上下文。

### 17.3 崩溃点测试

- requested 落盘后崩溃：重启后仍显示审批。
- resolved 落盘前崩溃：审批仍是 PENDING。
- resolved 落盘后、enqueue 前崩溃：重启后自动继续。
- enqueue 后、工具 started 前崩溃：安全重新调度。
- tool started 后崩溃：记录 uncertain，不自动重复副作用。

### 17.4 API 与 UI 测试

- 单项和批量审批请求校验。
- 过期 revision 返回 409。
- commandId 重试返回第一次结果。
- 双击审批只产生一个 resolved 事件。
- 页面刷新和后端重启后审批卡片保持一致。

## 18. 验收标准

满足以下条件后，本设计才算完成：

1. 生产代码的审批路径中不存在 `CompletableFuture#get()`。
2. 审批等待不占用 Run 工作线程。
3. `WAITING_APPROVAL` 能从事件流和 Snapshot 正确重建。
4. 后端重启后未决审批不会自动 interrupted，用户可以继续处理。
5. 审批后恢复的是同一个 runId。
6. resolved 落盘后即使未成功 enqueue，重启也能继续。
7. 所有 Run 控制使用同一个 HTTP 入口和 `RunControlRequest`。
8. 没有为审批、恢复、取消分别创建 Command 类。
9. 多工具审批、重复请求、并发客户端和关键崩溃窗口均有自动化测试。
10. 旧审批 API、旧 Future 队列和旧数据兼容代码已经删除。

