export type LlmContextMessage = {
  index: number
  role: string
  title: string
  content: string
}

export type LlmToolCall = {
  name: string
  arguments: string
}

export type LlmOutput = {
  text: string
  hasToolRequests: boolean
  toolCalls: LlmToolCall[]
}

export type LlmContextTurn = {
  turnIndex: number
  messageCount: number
  estimatedTokens: number
  messages: LlmContextMessage[]
  output?: LlmOutput
}

export type LlmContextEvent = {
  type: string
  payload: Record<string, unknown>
}

export function applyLlmContextEvent(turns: LlmContextTurn[], event: LlmContextEvent) {
  if (event.type === 'context.snapshot') {
    const snapshot = parseSnapshot(event.payload)
    if (!snapshot) return turns

    const next = turns.filter(turn => turn.turnIndex !== snapshot.turnIndex)
    next.push({
      ...snapshot,
      output: turns.find(turn => turn.turnIndex === snapshot.turnIndex)?.output,
    })
    return next.sort((a, b) => a.turnIndex - b.turnIndex)
  }

  if (event.type === 'llm.output') {
    const output = parseOutput(event.payload)
    if (!output) return turns

    return turns.map(turn =>
      turn.turnIndex === output.turnIndex
        ? { ...turn, output: output.output }
        : turn,
    )
  }

  return turns
}

function parseSnapshot(payload: Record<string, unknown>): LlmContextTurn | null {
  const turnIndex = numberValue(payload.turnIndex)
  if (turnIndex === null) return null

  const rawMessages = Array.isArray(payload.messages) ? payload.messages : []
  const messages = rawMessages
    .map(parseMessage)
    .filter((message): message is LlmContextMessage => message !== null)

  return {
    turnIndex,
    messageCount: numberValue(payload.messageCount) ?? messages.length,
    estimatedTokens: numberValue(payload.estimatedTokens) ?? 0,
    messages,
  }
}

function parseOutput(payload: Record<string, unknown>): { turnIndex: number; output: LlmOutput } | null {
  const turnIndex = numberValue(payload.turnIndex)
  if (turnIndex === null) return null

  const rawToolCalls = Array.isArray(payload.toolCalls) ? payload.toolCalls : []
  const toolCalls = rawToolCalls
    .map(parseToolCall)
    .filter((toolCall): toolCall is LlmToolCall => toolCall !== null)

  return {
    turnIndex,
    output: {
      text: stringValue(payload.text),
      hasToolRequests: payload.hasToolRequests === true,
      toolCalls,
    },
  }
}

function parseMessage(value: unknown): LlmContextMessage | null {
  if (!value || typeof value !== 'object') return null
  const message = value as Record<string, unknown>
  const index = numberValue(message.index)
  if (index === null) return null
  return {
    index,
    role: stringValue(message.role),
    title: stringValue(message.title),
    content: stringValue(message.content),
  }
}

function parseToolCall(value: unknown): LlmToolCall | null {
  if (!value || typeof value !== 'object') return null
  const toolCall = value as Record<string, unknown>
  return {
    name: stringValue(toolCall.name),
    arguments: stringValue(toolCall.arguments),
  }
}

function stringValue(value: unknown) {
  return typeof value === 'string' ? value : ''
}

function numberValue(value: unknown) {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}
