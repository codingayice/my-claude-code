# Veyra LangChain4j 技术栈架构设计

## 1. 文档信息

| 项目 | 内容 |
| --- | --- |
| 文档名称 | Veyra LangChain4j 技术栈架构设计 |
| 适用范围 | `cn.ayice.veyra` Agent 底座 |
| 当前状态 | 设计基线 |
| 编写日期 | 2026-07-28 |

## 2. 背景与结论

Veyra 已经确定使用 LangChain4j 作为模型调用、消息模型、工具调用和流式响应框架。LangChain4j 本身已经提供了稳定的框架抽象，再围绕它重新实现一套消息适配器、模型适配器或工具适配器，会产生重复抽象和重复转换。

本设计的核心结论是：

> Veyra 直接使用 LangChain4j，不建立覆盖 LangChain4j 的通用 Adapter 层。

Veyra 自己只保留 Agent 领域需要的应用服务和编排组件。`AIService` 是 Veyra 的 Agent 应用服务，不是 LangChain4j 的包装适配器；它负责 Agent 调用流程，但直接接收和返回 LangChain4j 类型。

## 3. 设计目标

### 3.1 目标

- 固定 LangChain4j 为 Agent 底座的唯一技术栈。
- 直接使用 LangChain4j 的消息、工具、流式和模型能力。
- 保持现有 Controller/Service MVC 模式。
- 不改变 Agent 核心业务逻辑，只调整模块边界和依赖方向。
- 让 Agent Loop、上下文压缩和工具协调拥有清晰职责。
- 让 LangChain4j 升级、配置和异常处理有明确归属。
- 让测试可以替换模型调用，但不需要构造第二套消息模型。

### 3.2 非目标

- 不同时支持 LangChain4j、LangChain、Spring AI 等多个 Agent 框架。
- 不提供供应商无关的自定义消息协议。
- 不把 LangChain4j 的每个类型重新包装成 Veyra 类型。
- 不预先设计未来可能使用但当前不存在的 Provider Adapter。
- 不修改 Agent 的工具调用顺序、并行规则和核心提示词业务逻辑。

## 4. 为什么不需要 Adapter

典型的重复结构如下：

```text
AgentService
    -> VeyraModelAdapter
        -> VeyraMessage
            -> LangChain4j ChatMessage
                -> LangChain4j ChatModel
```

这类结构的问题是：

1. 消息、工具调用、流式事件和异常都需要双向转换。
2. LangChain4j 新增能力无法直接进入 Agent 流程。
3. 业务代码同时依赖 Veyra 自定义模型和 LangChain4j 模型，理解成本更高。
4. Adapter 层会逐渐变成第二个 Agent 框架。
5. 框架升级时需要维护大量无业务价值的映射代码。

LangChain4j 已经承担了模型供应商差异、消息结构和工具协议的框架抽象。Veyra 需要隔离的是 Agent 业务流程，而不是再次隔离 LangChain4j。

只有在未来需要同时支持多个 Agent 框架，或者明确替换 LangChain4j 时，才重新评估 Adapter。当前不为假设的替换成本增加结构复杂度。

## 5. 总体架构

```text
HTTP/SSE
  |
  v
api
  AgentController
  |
  v
service
  AgentService
  |
  v
kernel.agent
  AgentLoop
  AgentTurnPreparer
  AgentToolCoordinator
  |
  +--------------------+
  |                    |
  v                    v
conversation        tool
  ContextBuilder       ToolRegistry
  MessageHistory       ToolExecutor
  Compaction           LangChain4j tool definitions
  |
  v
llm
  AIService
  PromptBuilder
  LangChain4jConfig
  |
  v
LangChain4j ChatModel / StreamingChatModel
```

Veyra 的 Agent 内部模块可以直接使用 LangChain4j 的 `ChatMessage`、`ChatRequest`、`AiMessage` 和工具调用类型。HTTP 层仍然必须使用自己的 Request/Response DTO，不能把 LangChain4j 类型暴露给前端。

## 6. 包结构与职责

```text
cn.ayice.veyra
|-- api
|   |-- AgentController.java
|   |-- AgentStreamController.java
|   `-- dto
|       |-- AgentMessageRequest.java
|       `-- AgentMessageResponse.java
|-- service
|   `-- AgentService.java
|-- kernel
|   `-- agent
|       |-- AgentLoop.java
|       |-- AgentTurnPreparer.java
|       `-- AgentToolCoordinator.java
|-- conversation
|   |-- context
|   |   |-- ContextBuilder.java
|   |   |-- TokenEstimator.java
|   |   `-- compaction
|   |       |-- CompactionCoordinator.java
|   |       |-- MicroCompactor.java
|   |       |-- SessionMemoryCompactor.java
|   |       `-- LlmSummaryCompactor.java
|   `-- history
|       `-- MessageHistory.java
|-- tool
|   |-- ToolRegistry.java
|   |-- ToolExecutionCoordinator.java
|   `-- builtin
|-- llm
|   |-- AIService.java
|   |-- PromptBuilder.java
|   |-- LangChain4jConfig.java
|   `-- LangChain4jUsageMapper.java
|-- exception
|   |-- AgentException.java
|   |-- ContextCompactionException.java
|   `-- GlobalExceptionHandler.java
`-- config
    `-- VeyraAgentProperties.java
```

### 6.1 `api`

只负责 HTTP/SSE 入口、参数校验、调用 `AgentService` 和返回 DTO。不得直接使用 `ChatModel`、执行工具、操作历史消息或处理上下文压缩。

### 6.2 `service`

负责一次用户请求的应用级编排和 Session 权限边界。它不实现工具执行细节，也不拼接底层模型请求。

### 6.3 `kernel.agent`

负责 Agent Loop：

- 追加用户消息。
- 调用 `AgentTurnPreparer` 准备上下文。
- 调用 `AIService` 请求模型。
- 处理模型返回的文本和工具请求。
- 将一批工具请求交给 `AgentToolCoordinator`。
- 等待整批并行工具完成后进入下一轮。

### 6.4 `conversation`

负责会话消息、上下文构建和上下文压缩。可以直接使用 LangChain4j 的 `ChatMessage`，但不依赖 Controller、HTTP Session 或前端 DTO。

### 6.5 `llm`

负责 LangChain4j 的配置和 Agent 级模型调用服务：

- 创建和配置 `ChatModel`、`StreamingChatModel`。
- 组装 `ChatRequest`。
- 发起同步或流式调用。
- 提取 `AiMessage` 和 usage。
- 将 LangChain4j 异常转换为 Veyra 异常。

这里允许直接依赖 LangChain4j。禁止创建 `VeyraChatMessage`、`VeyraToolCall` 等重复模型。

### 6.6 `tool`

负责工具注册、工具权限和工具执行。工具的声明和调用使用 LangChain4j 支持的工具模型，但工具内部业务逻辑不依赖 Controller 或 `AIService`。

## 7. 关键依赖规则

依赖方向固定为：

```text
api -> service -> kernel.agent -> conversation / tool / llm
```

具体规则：

1. `api` 不得反向依赖 `tool`、`conversation` 的实现细节。
2. `conversation` 不得依赖 `api` 和 `service`。
3. `tool` 不得调用 `AgentLoop` 重新发起模型请求。
4. `AIService` 可以依赖 LangChain4j，但 LangChain4j 不得反向依赖 Veyra 业务包。
5. `CompactService` 不得调用 `AIService` 以外的工具执行组件。
6. `AIService` 不负责决定何时执行 Micro Compact 或 LLM Summary Compact。
7. 所有模块只能通过明确的应用服务或组件依赖通信，不使用静态全局 Session 状态。

## 8. AIService 定位

`AIService` 保留，但职责必须限定为 Agent 的模型调用服务，而不是 Adapter。

### 8.1 允许职责

- 接收 LangChain4j 的 `ChatRequest` 或 Veyra 已构建的消息列表。
- 调用注入的 `ChatModel` 或 `StreamingChatModel`。
- 统一配置模型参数、超时、重试和流式回调。
- 返回 LangChain4j 的 `ChatResponse`、`AiMessage` 或流式事件。
- 统一提取 token usage。
- 将底层异常映射为 Veyra 的 `LlmCallException`。

### 8.2 禁止职责

- 重新定义一套消息、工具调用或响应模型。
- 决定上下文压缩策略。
- 直接执行工具。
- 保存 Session 历史。
- 处理 HTTP 响应。
- 捕获异常后返回 `null` 或空回复。

推荐调用关系：

```java
ChatRequest request = contextBuilder.build(messages, workingDir);
ChatResponse response = aiService.chat(request);
AiMessage aiMessage = response.aiMessage();
```

这里的 `ChatRequest`、`ChatResponse` 和 `AiMessage` 直接使用 LangChain4j 类型。

## 9. 用户消息完整流转

```text
AgentController
    -> AgentService.send(request)
    -> AgentLoop.run(userMessage)
    -> MessageHistory.append(userMessage)
    -> AgentTurnPreparer.prepare(history)
    -> ContextBuilder.build(history)
    -> CompactionCoordinator.compactIfNeeded(...)
    -> AIService.chat(ChatRequest)
    -> LangChain4j ChatModel
```

如果模型返回工具请求：

```text
AiMessage.toolExecutionRequests()
    -> AgentToolCoordinator.execute(...)
    -> 工具并行执行
    -> 等待全部工具完成
    -> 按原请求顺序生成 ToolExecutionResultMessage
    -> MessageHistory.append(results)
    -> 进入下一轮 AgentTurnPreparer
```

如果模型返回最终文本：

```text
AiMessage.text()
    -> AgentLoop 完成当前请求
    -> AgentService 转换为响应 DTO
    -> Controller 返回或通过 SSE 推送
```

工具可以并行，但同一批工具必须全部完成后，才能执行下一轮上下文压缩和模型调用。

## 10. 上下文压缩与 LangChain4j 的边界

上下文压缩直接使用 LangChain4j 消息类型，但压缩策略归 Veyra 所有：

```text
ContextBuilder
    -> 生成当前请求草稿
    -> ContextBudgetSnapshot
    -> CompactionCoordinator
        -> MicroCompactor
        -> SessionMemoryCompactor
        -> LlmSummaryCompactor
    -> 重新构建 ChatRequest
    -> AIService
```

LangChain4j 只负责执行最终的模型调用。它不需要知道 Veyra 的 Micro Compact、Session Summary 或长期记忆规则。

LLM Summary Compact 可以通过 `AIService` 调用模型，但摘要内容仍然由 `conversation.context.compaction` 负责组织和校验。

## 11. 异常处理

异常处理分三层：

### 11.1 LangChain4j 调用层

`AIService` 捕获 LangChain4j 的网络、模型、解析和流式异常，转换为带有原始 cause 的 Veyra 异常：

```text
LlmTimeoutException
LlmAuthenticationException
LlmRateLimitException
LlmContextLengthException
LlmResponseParseException
```

不得吞掉异常，也不得把异常消息直接当作模型回复返回。

### 11.2 Agent 应用层

`AgentLoop` 根据异常类型决定：

- 上下文超限：触发一次 REACTIVE 压缩和重试。
- 工具执行失败：生成结构化工具失败结果，继续当前批次。
- 模型鉴权失败：立即结束当前请求。
- 普通网络错误：按明确的重试策略处理。

### 11.3 HTTP 层

`GlobalExceptionHandler` 将 Veyra 异常映射为统一 API 错误。不得暴露 Java 异常栈、LangChain4j 原始异常文本或供应商密钥信息。

## 12. 配置原则

LangChain4j 的配置集中在 `LangChain4jConfig`：

- 模型名称。
- API 地址和密钥引用。
- 超时。
- 最大输出 token。
- 温度等模型参数。
- 流式和非流式模型 Bean。

上下文压缩、工具权限和 Agent 行为配置不放进 LangChain4j 配置类，而放在 Veyra 自己的配置中。

禁止在业务代码中散落创建模型实例：

```java
// 禁止
new OpenAiChatModel(...);

// 推荐
private final ChatModel chatModel;
```

## 13. 测试策略

### 13.1 AIService

- 使用 Mock `ChatModel` 验证请求转发。
- 验证流式事件顺序。
- 验证 usage 提取。
- 验证 LangChain4j 异常映射。
- 验证超时、限流、鉴权和上下文超限分类。

### 13.2 AgentLoop

- 模型直接返回文本。
- 模型返回单个工具请求。
- 模型返回并行工具请求。
- 工具全部完成后才进入下一轮。
- 工具失败、拒绝和取消都生成结果。
- 工具批次未完成时不得调用下一轮模型。

### 13.3 Context Compaction

- 直接使用 LangChain4j 消息类型构造测试数据。
- 验证消息顺序和工具请求/结果配对。
- 验证 Micro Compact 不调用真实模型。
- 验证压缩输入列表不被修改。
- 验证压缩失败不会静默丢失历史。
- 验证压缩器与 `AIService` 的依赖方向稳定。

## 14. 演进规则

后续开发必须遵守以下规则：

1. 新增 Agent 能力优先放在现有 `service`、`kernel.agent`、`conversation`、`tool` 或 `llm` 包内。
2. 只有业务职责发生变化时才新增服务，不因为 LangChain4j 类型存在就增加包装类。
3. LangChain4j 新能力可以直接在 `llm`、`tool` 或 `conversation` 内使用。
4. Controller 不得直接调用 LangChain4j。
5. 不得把 LangChain4j 类型暴露到 HTTP DTO。
6. 不得在 Veyra 内复制 LangChain4j 的消息和工具模型。
7. 不得以“未来可能替换框架”为理由预先增加 Adapter。
8. 如果未来需要支持第二个 Agent 框架，必须先提交技术选型和边界影响评估，再单独引入 Anti-Corruption Layer。

## 15. 迁移建议

迁移必须保持 Agent 核心业务逻辑不变，按以下顺序进行：

1. 确认所有 Agent 模块直接使用 LangChain4j 的消息和工具类型。
2. 将重复的消息、工具调用和响应包装类标记为迁移对象。
3. 保留 `AIService`，删除其中与业务无关的重复转换。
4. 将模型实例创建统一收敛到 `LangChain4jConfig`。
5. 将工具批次协调从模型调用服务中移回 `AgentToolCoordinator`。
6. 将上下文压缩从 `AIService` 中移回 `conversation.context.compaction`。
7. 补充 AgentLoop、并行工具、异常映射和压缩边界测试。
8. 通过回归测试确认提示词、工具调用顺序、流式输出和最终回复保持不变。

## 16. 验收标准

- 代码中不存在覆盖 LangChain4j 的通用 Adapter 层。
- Agent 内部消息直接使用 LangChain4j 类型。
- LangChain4j 只在 Veyra 内部模块使用，不出现在 HTTP DTO。
- 模型实例只由配置模块创建和注入。
- AgentLoop 可以正确处理文本回复和并行工具批次。
- 工具批次未全部完成时不会进入下一轮模型调用。
- LangChain4j 异常不会被静默吞掉。
- 上下文压缩策略不依赖 LangChain4j 的具体实现细节。
- 原有 Agent 核心业务逻辑、系统提示词和工具行为保持不变。
