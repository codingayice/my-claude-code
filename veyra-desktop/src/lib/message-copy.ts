export type CopyableTool = {
  name?: string
  input?: unknown
  output?: unknown
  errorText?: string
}

export type CopyableSegment =
  | { type: 'text'; content?: string }
  | { type: 'reasoning'; content?: string }
  | { type: 'process'; tools?: CopyableTool[] }
  | { type: 'todo'; items?: unknown[] }
  | { type: 'approval'; toolName?: string; reason?: string; input?: unknown }
  | { type: 'subagent'; subagent?: { name?: string; segments?: CopyableSegment[] } }

export type CopyableChatEntry = {
  role: 'user' | 'assistant'
  content?: string
  segments?: CopyableSegment[]
  final?: boolean
}

export function buildChatEntryCopyText(entry: CopyableChatEntry) {
  if (entry.role === 'user') {
    return normalizeText(entry.content)
  }

  return joinBlocks(segmentsToCopyBlocks(entry.segments ?? []))
}

export function shouldShowChatEntryCopyAction(entry: CopyableChatEntry) {
  if (entry.role === 'assistant' && entry.final !== true) {
    return false
  }

  return Boolean(buildChatEntryCopyText(entry))
}

function segmentsToCopyBlocks(segments: CopyableSegment[]): string[] {
  const blocks: string[] = []

  for (const segment of segments) {
    if (segment.type === 'text' || segment.type === 'reasoning') {
      pushText(blocks, segment.content)
      continue
    }

    if (segment.type === 'process') {
      for (const tool of segment.tools ?? []) {
        blocks.push(...toolToCopyBlocks(tool))
      }
      continue
    }

    if (segment.type === 'approval') {
      const approvalBlocks = [
        segment.toolName ? `等待授权：${segment.toolName}` : '等待授权',
        normalizeText(segment.reason),
        segment.input === undefined ? '' : `input:\n${formatUnknown(segment.input)}`,
      ].filter(Boolean)
      blocks.push(...approvalBlocks)
      continue
    }

    if (segment.type === 'subagent') {
      const name = normalizeText(segment.subagent?.name)
      if (name) blocks.push(name)
      blocks.push(...segmentsToCopyBlocks(segment.subagent?.segments ?? []))
    }
  }

  return blocks
}

function toolToCopyBlocks(tool: CopyableTool): string[] {
  const blocks = [normalizeText(tool.name) || 'tool']

  if (tool.input !== undefined) {
    blocks.push(`input:\n${formatUnknown(tool.input)}`)
  }

  if (tool.output !== undefined) {
    blocks.push(`output:\n${formatUnknown(tool.output)}`)
  }

  pushText(blocks, tool.errorText)
  return blocks
}

function pushText(blocks: string[], text: string | undefined) {
  const normalized = normalizeText(text)
  if (normalized) {
    blocks.push(normalized)
  }
}

function joinBlocks(blocks: string[]) {
  return blocks
    .map(block => block.trim())
    .filter(Boolean)
    .join('\n\n')
}

function normalizeText(text: string | undefined) {
  return typeof text === 'string' ? text.trim() : ''
}

function formatUnknown(value: unknown) {
  if (typeof value === 'string') {
    const trimmed = value.trim()
    if (!trimmed) return ''
    try {
      return JSON.stringify(JSON.parse(trimmed), null, 2)
    } catch {
      return trimmed
    }
  }

  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}
