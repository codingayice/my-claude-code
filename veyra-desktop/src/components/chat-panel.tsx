import { memo, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Check, CheckCircle2, Copy, FileText, FolderOpen, ShieldCheck, XCircle } from 'lucide-react'
import { invoke } from '@tauri-apps/api/core'
import { open as openDialog } from '@tauri-apps/plugin-dialog'
import { Agent, AgentContent, AgentHeader } from '@/components/ai/agent'
import {
  ChainOfThought,
  ChainOfThoughtContent,
  ChainOfThoughtHeader,
  ChainOfThoughtStep,
} from '@/components/ai/chain-of-thought'
import {
  Confirmation,
  ConfirmationAccepted,
  ConfirmationAction,
  ConfirmationActions,
  ConfirmationRejected,
  ConfirmationRequest,
  ConfirmationTitle,
} from '@/components/ai/confirmation'
import {
  Conversation,
  ConversationContent,
  ConversationScrollButton,
} from '@/components/ai/conversation'
import { Message, MessageAction, MessageActions, MessageContent, MessageResponse } from '@/components/ai/message'
import {
  PromptInput,
  PromptInputBody,
  PromptInputFooter,
  PromptInputSubmit,
  PromptInputTextarea,
} from '@/components/ai/prompt-input'
import {
  Queue,
  QueueItem,
  QueueItemContent,
  QueueItemDescription,
  QueueItemIndicator,
  QueueList,
  QueueSection,
  QueueSectionContent,
  QueueSectionLabel,
  QueueSectionTrigger,
} from '@/components/ai/queue'
import { Terminal } from '@/components/ai/terminal'
import { Tool, ToolContent, ToolHeader, ToolInput, ToolOutput } from '@/components/ai/tool'
import { SubagentStack } from '@/components/ai/subagent-stack'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import {
  Dialog,
  DialogContent,
  DialogTitle,
} from '@/components/ui/dialog'
import { PlaceholdersAndVanishInput } from '@/components/ui/placeholders-and-vanish-input'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectPositioner,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { TextShimmer } from '@/components/agent-elements/text-shimmer'
import {
  Context,
  ContextContent,
  ContextContentHeader,
  ContextTrigger,
} from '@/components/ai/context'
import { cn } from '@/lib/utils'
import {
  isActiveStreamingSegment,
  shouldCloseProcessOnAssistantMessage,
} from '@/lib/agent-segments'
import { applyLlmContextEvent, type LlmContextMessage, type LlmContextTurn } from '@/lib/llm-context-view'
import { buildChatEntryCopyText, shouldShowChatEntryCopyAction } from '@/lib/message-copy'
import {
  agentApi,
  agentEventUrl,
  type AgentApprovalDecision,
  type AgentPermissionMode,
  type AgentRunMode,
} from '@/lib/agent-api'


const AGENT_START_TIMEOUT_MS = 10_000
const AGENT_START_POLL_MS = 250
const AGENT_EVENT_RECONNECT_GRACE_MS = 2_000
const AGENT_EVENT_CONNECTION_ERROR = '智能体事件连接中断，正在自动重连。'

const EMPTY_PROMPT_PLACEHOLDERS = [
  '梳理这份文档的结构和重点',
  '把当前内容改写得更清楚',
  '生成一份可执行的任务清单',
  '检查项目里最近的改动风险',
  '让助手帮我完成一个具体目标',
]

type Props = {
  width?: number | string
  onDocumentGenerated?: unknown
  initialInput?: string
  workspaceRoot?: string | null
}

type PanelState = 'connecting' | 'ready' | 'running' | 'error'
type RunModeValue = AgentRunMode
type PermissionModeValue = AgentPermissionMode
type ToolState =
  | 'input-available'
  | 'output-available'
  | 'output-error'
  | 'output-denied'
  | 'approval-requested'
  | 'approval-responded'
type TodoStatus = 'pending' | 'in_progress' | 'completed'
type ApprovalDecision = AgentApprovalDecision
type ProcessStatus = 'running' | 'completed' | 'failed'
export type SubagentStatus = 'running' | 'completed' | 'failed' | 'killed'

type TodoItem = {
  content: string
  status: TodoStatus
  activeForm?: string
}

type ToolApproval = {
  id: string
  approved?: boolean
  reason?: string
}

type ToolEntry = {
  id: string
  name: string
  state: ToolState
  input?: unknown
  output?: unknown
  errorText?: string
  approval?: ToolApproval
}

type TextSegment = {
  id: string
  type: 'text'
  content: string
  streaming?: boolean
}

type ReasoningSegment = {
  id: string
  type: 'reasoning'
  content: string
  streaming?: boolean
}

type ProcessSegment = {
  id: string
  type: 'process'
  status: ProcessStatus
  startedAtMs: number
  endedAtMs?: number
  tools: ToolEntry[]
  stepLabel?: string
}

type TodoSegment = {
  id: string
  type: 'todo'
  items: TodoItem[]
  updatedAtMs: number
}

export type SubagentEntry = {
  taskId: string
  name: string
  description?: string
  subagentType?: string
  status: SubagentStatus
  startedAtMs: number
  endedAtMs?: number
  segments: AssistantSegment[]
  totalDurationMs?: number
  totalToolUseCount?: number
}

type SubagentSegment = {
  id: string
  type: 'subagent'
  subagent: SubagentEntry
}

type ApprovalSegment = {
  id: string
  type: 'approval'
  approvalId: string
  toolName: string
  reason?: string
  input?: unknown
  approved?: boolean
}

type AssistantSegment = TextSegment | ReasoningSegment | ProcessSegment | TodoSegment | SubagentSegment | ApprovalSegment

type ChatEntry = {
  id: string
  role: 'user' | 'assistant'
  content?: string
  runId?: string
  streaming?: boolean
  final?: boolean
  segments?: AssistantSegment[]
}

type AgentEvent = {
  seq: number
  sessionId: string
  runId?: string | null
  type: string
  timestampMs: number
  payload: Record<string, unknown>
}

const PERMISSION_MODE_OPTIONS: Array<{ value: PermissionModeValue; label: string }> = [
  { value: 'ask_every_time', label: '每次询问' },
  { value: 'project_auto', label: '项目内自动批准' },
  { value: 'auto_approve', label: '自动批准' },
]

const RUN_MODE_OPTIONS: Array<{ value: RunModeValue; label: string }> = [
  { value: 'chat', label: 'Chat' },
  { value: 'agent', label: 'Agent' },
]

const SELECT_WORKSPACE_VALUE = '__select_workspace__'

function newId(prefix: string) {
  return `${prefix}-${crypto.randomUUID()}`
}

function normalizePermissionMode(value: unknown): PermissionModeValue {
  return PERMISSION_MODE_OPTIONS.some(option => option.value === value)
    ? value as PermissionModeValue
    : 'ask_every_time'
}

function formatPathLabel(path: string | null | undefined) {
  if (!path) return '未选择工作目录'
  const normalized = path.replace(/\\/g, '/')
  const parts = normalized.split('/').filter(Boolean)
  if (parts.length <= 2) return path
  return `${parts.at(-2)}/${parts.at(-1)}`
}

function permissionModeLabel(mode: PermissionModeValue) {
  return PERMISSION_MODE_OPTIONS.find(option => option.value === mode)?.label ?? '每次询问'
}

function runModeLabel(mode: RunModeValue) {
  return RUN_MODE_OPTIONS.find(option => option.value === mode)?.label ?? 'Agent'
}

function findLastIndex<T>(items: T[], matcher: (item: T) => boolean) {
  for (let index = items.length - 1; index >= 0; index -= 1) {
    if (matcher(items[index])) return index
  }
  return -1
}

function normalizeToolValue(value: unknown) {
  if (typeof value !== 'string') return value

  const trimmed = value.trim()
  if (!trimmed) return ''

  try {
    return JSON.parse(trimmed)
  } catch {
    return trimmed
      .replace(/^<(success|error|rejected)>/i, '')
      .replace(/<\/(success|error|rejected)>$/i, '')
      .trim()
  }
}

function isTerminalToolState(state: ToolState) {
  return state === 'output-available' || state === 'output-error' || state === 'output-denied'
}

function isSubagentLaunchTool(name: string) {
  return name.trim().toLowerCase() === 'agent'
}

function isTodoTool(name: string) {
  return name.trim().toLowerCase() === 'todowrite'
}

function isBashTool(name: string) {
  return name.trim().toLowerCase() === 'bash'
}

function commandFromApprovalInput(input: unknown): string | null {
  const normalized = normalizeToolValue(input)

  if (typeof normalized === 'string') {
    return normalized.trim() || null
  }

  if (!normalized || typeof normalized !== 'object' || Array.isArray(normalized)) {
    return null
  }

  const value = (normalized as Record<string, unknown>).command
  return typeof value === 'string' && value.trim() ? value.trim() : null
}

function createProcessSegment(startedAtMs: number): ProcessSegment {
  return {
    id: newId('process'),
    type: 'process',
    status: 'running',
    startedAtMs,
    tools: [],
  }
}

function appendTool(tools: ToolEntry[], name: string, state: ToolState, input?: unknown) {
  return [
    ...tools,
    {
      id: newId('tool'),
      name,
      state,
      input,
    },
  ]
}

function updateLatestTool(
  tools: ToolEntry[],
  name: string,
  updater: (tool: ToolEntry | null) => ToolEntry,
) {
  const next = [...tools]
  const index = findLastIndex(
    next,
    tool => tool.name === name && !isTerminalToolState(tool.state),
  )

  if (index >= 0) {
    next[index] = updater(next[index])
  } else {
    next.push(updater(null))
  }

  return next
}

function closeRunningProcessSegments(segments: AssistantSegment[] | undefined, endedAtMs: number) {
  return [...(segments ?? [])].map(segment =>
    segment.type === 'process' && segment.status === 'running'
      ? { ...segment, status: 'completed' as const, endedAtMs }
      : segment,
  )
}

function ensureOpenProcessSegment(segments: AssistantSegment[] | undefined, timestampMs: number) {
  const next = [...(segments ?? [])]
  const runningIndex = findLastIndex(
    next,
    segment => segment.type === 'process' && segment.status === 'running',
  )

  if (runningIndex >= 0) {
    return next
  }

  next.push(createProcessSegment(timestampMs))
  return next
}

function updateProcessSegment(
  segments: AssistantSegment[] | undefined,
  timestampMs: number,
  updater: (segment: ProcessSegment) => ProcessSegment,
) {
  const next = ensureOpenProcessSegment(segments, timestampMs)
  const index = findLastIndex(next, segment => segment.type === 'process' && segment.status === 'running')

  if (index >= 0 && next[index].type === 'process') {
    next[index] = updater(next[index])
  }

  return next
}

function updateToolInProcessSegment(
  segments: AssistantSegment[] | undefined,
  name: string,
  timestampMs: number,
  updater: (tool: ToolEntry | null) => ToolEntry,
) {
  const next = [...(segments ?? [])]
  const processIndex = findLastIndex(
    next,
    segment =>
      segment.type === 'process' &&
      segment.tools.some(tool => tool.name === name && !isTerminalToolState(tool.state)),
  )

  if (processIndex >= 0 && next[processIndex].type === 'process') {
    const process = next[processIndex]
    next[processIndex] = {
      ...process,
      tools: updateLatestTool(process.tools, name, updater),
    }
    return next
  }

  return updateProcessSegment(next, timestampMs, process => ({
    ...process,
    tools: updateLatestTool(process.tools, name, updater),
  }))
}

function appendApprovalSegment(
  segments: AssistantSegment[] | undefined,
  _timestampMs: number,
  approval: Omit<ApprovalSegment, 'type'>,
) {
  const next = [...(segments ?? [])]
  const index = findLastIndex(
    next,
    segment => segment.type === 'approval' && segment.approvalId === approval.approvalId,
  )

  if (index >= 0 && next[index].type === 'approval') {
    next[index] = {
      ...next[index],
      ...approval,
      type: 'approval',
    }
    return next
  }

  next.push({
    ...approval,
    type: 'approval',
  })
  return next
}

function updateTodoSegment(
  segments: AssistantSegment[] | undefined,
  items: TodoItem[],
  timestampMs: number,
) {
  const next = [...(segments ?? [])]
  const index = findLastIndex(next, segment => segment.type === 'todo')

  if (index >= 0 && next[index].type === 'todo') {
    next[index] = {
      ...next[index],
      items,
      updatedAtMs: timestampMs,
    }
    return next
  }

  next.push({
    id: newId('todo'),
    type: 'todo',
    items,
    updatedAtMs: timestampMs,
  })

  return next
}

function updateApprovalSegment(
  segments: AssistantSegment[] | undefined,
  approvalId: string,
  approved: boolean,
): AssistantSegment[] {
  return [...(segments ?? [])].map(segment =>
    segment.type === 'approval' && segment.approvalId === approvalId
      ? { ...segment, approved }
      : segment.type === 'subagent'
        ? {
            ...segment,
            subagent: {
              ...segment.subagent,
              segments: updateApprovalSegment(segment.subagent.segments, approvalId, approved),
            },
          }
      : segment,
  )
}

function hydrateLatestPendingApproval(
  segments: AssistantSegment[] | undefined,
  approvalId: string,
  toolName: string,
  reason?: string,
  input?: unknown,
): { segments: AssistantSegment[] | undefined; hydrated: boolean } {
  let hydrated = false

  const next = [...(segments ?? [])]
  for (let index = next.length - 1; index >= 0; index -= 1) {
    const segment = next[index]

    if (
      segment.type === 'approval' &&
      segment.approved === undefined &&
      segment.toolName === toolName &&
      (segment.reason === reason || !segment.reason || !reason)
    ) {
      next[index] = {
        ...segment,
        approvalId,
        input: segment.input ?? input,
        reason: segment.reason ?? reason,
      }
      hydrated = true
      break
    }

    if (segment.type === 'subagent') {
      const hydratedSubagent = hydrateLatestPendingApproval(
        segment.subagent.segments,
        approvalId,
        toolName,
        reason,
        input,
      )

      if (hydratedSubagent.hydrated) {
        next[index] = {
          ...segment,
          subagent: {
            ...segment.subagent,
            segments: hydratedSubagent.segments ?? [],
          },
        }
        hydrated = true
        break
      }
    }
  }

  return { segments: next, hydrated }
}

function appendTextSegment(
  segments: AssistantSegment[] | undefined,
  text: string,
  timestampMs: number,
) {
  const next = closeRunningProcessSegments([...(segments ?? [])], timestampMs)
  const last = next[next.length - 1]

  if (last?.type === 'text' && isActiveStreamingSegment(last)) {
    next[next.length - 1] = {
      ...last,
      content: `${last.content}${text}`,
      streaming: true,
    }
  } else {
    next.push({
      id: newId('text'),
      type: 'text',
      content: text,
      streaming: true,
    })
  }

  return next
}

function appendReasoningSegment(
  segments: AssistantSegment[] | undefined,
  text: string,
) {
  const next = [...(segments ?? [])]
  const last = next[next.length - 1]

  if (last?.type === 'reasoning' && isActiveStreamingSegment(last)) {
    next[next.length - 1] = {
      ...last,
      content: `${last.content}${text}`,
      streaming: true,
    }
  } else {
    next.push({
      id: newId('reasoning'),
      type: 'reasoning',
      content: text,
      streaming: true,
    })
  }

  return next
}

function completeReasoningSegment(
  segments: AssistantSegment[] | undefined,
  text: string,
) {
  const next = [...(segments ?? [])]
  const index = findLastIndex(
    next,
    segment => segment.type === 'reasoning' && isActiveStreamingSegment(segment),
  )

  if (index >= 0 && next[index].type === 'reasoning') {
    next[index] = {
      ...next[index],
      content: text || next[index].content,
      streaming: false,
    }
    return next
  }

  if (text) {
    next.push({
      id: newId('reasoning'),
      type: 'reasoning',
      content: text,
      streaming: false,
    })
  }

  return next
}

function completeTextSegment(
  segments: AssistantSegment[] | undefined,
  text: string,
  timestampMs: number,
  closeProcess = true,
) {
  const next = closeProcess
    ? closeRunningProcessSegments([...(segments ?? [])], timestampMs)
    : [...(segments ?? [])]
  const last = next[next.length - 1]

  if (text) {
    if (last?.type === 'text' && isActiveStreamingSegment(last)) {
      next[next.length - 1] = {
        ...last,
        content: text || last.content,
        streaming: false,
      }
    } else {
      next.push({
        id: newId('text'),
        type: 'text',
        content: text,
        streaming: false,
      })
    }
  } else if (last?.type === 'text' && isActiveStreamingSegment(last)) {
    next[next.length - 1] = { ...last, streaming: false }
  }

  return next
}

function failOpenProcessSegments(segments: AssistantSegment[] | undefined, endedAtMs: number) {
  return [...(segments ?? [])].map(segment =>
    segment.type === 'process' && segment.status === 'running'
      ? { ...segment, status: 'failed' as const, endedAtMs }
      : segment,
  )
}

function updateAssistantEntry(
  entries: ChatEntry[],
  runId: string | undefined,
  updater: (entry: ChatEntry) => ChatEntry,
) {
  const next = [...entries]
  const index = runId
    ? findLastIndex(next, entry => entry.role === 'assistant' && entry.runId === runId)
    : findLastIndex(next, entry => entry.role === 'assistant')

  if (index >= 0) {
    next[index] = updater(next[index])
    return next
  }

  next.push(
    updater({
      id: newId('assistant'),
      role: 'assistant',
      runId,
      streaming: true,
      final: false,
      segments: [],
    }),
  )
  return next
}

function updateAssistantProcess(
  entries: ChatEntry[],
  runId: string | undefined,
  timestampMs: number,
  updater: (segment: ProcessSegment) => ProcessSegment,
) {
  return updateAssistantEntry(entries, runId, entry => ({
    ...entry,
    segments: updateProcessSegment(entry.segments, timestampMs, updater),
  }))
}

function updateSubagentInSegments(
  segments: AssistantSegment[] | undefined,
  taskId: string,
  timestampMs: number,
  updater: (subagent: SubagentEntry) => SubagentEntry,
) {
  const next = [...(segments ?? [])]
  const index = next.findIndex(segment => segment.type === 'subagent' && segment.subagent.taskId === taskId)
  const base: SubagentEntry =
    index >= 0 && next[index].type === 'subagent'
      ? next[index].subagent
      : {
          taskId,
          name: '子 Agent',
          status: 'running',
          startedAtMs: timestampMs,
          segments: [],
        }
  const updated = updater(base)

  if (index >= 0) {
    next[index] = {
      id: next[index].id,
      type: 'subagent',
      subagent: updated,
    }
  } else {
    next.push({
      id: newId('subagent'),
      type: 'subagent',
      subagent: updated,
    })
  }

  return next
}

async function copyTextToClipboard(text: string) {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text)
    return
  }

  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.setAttribute('readonly', '')
  textarea.style.position = 'fixed'
  textarea.style.left = '-9999px'
  textarea.style.top = '0'
  document.body.appendChild(textarea)
  textarea.select()

  try {
    document.execCommand('copy')
  } finally {
    document.body.removeChild(textarea)
  }
}

async function waitForAgentService(timeoutMs = AGENT_START_TIMEOUT_MS) {
  const startedAt = Date.now()
  let lastError: unknown

  while (Date.now() - startedAt < timeoutMs) {
    try {
      await agentApi.health()
      return
    } catch (cause) {
      lastError = cause
      await new Promise(resolve => window.setTimeout(resolve, AGENT_START_POLL_MS))
    }
  }

  throw lastError instanceof Error
    ? lastError
    : new Error('本地智能体服务启动超时。')
}

async function ensureAgentService() {
  try {
    await agentApi.health()
    return
  } catch {
    // Not running yet; let the Tauri shell start the bundled agent service.
  }

  try {
    await invoke<string>('agent_start')
  } catch (cause) {
    throw new Error(cause instanceof Error ? cause.message : String(cause), { cause })
  }

  await waitForAgentService()
}

function toConfirmationApproval(approval: ToolApproval) {
  if (approval.approved === undefined) {
    return { id: approval.id }
  }

  return {
    id: approval.id,
    approved: approval.approved,
    reason: approval.reason,
  }
}

function processLabel(status: ProcessStatus) {
  if (status === 'completed') return '思考完成'
  if (status === 'failed') return '思考失败'
  return '思考中'
}

function subagentStatusLabel(status: SubagentStatus) {
  if (status === 'completed') return '已完成'
  if (status === 'failed') return '失败'
  if (status === 'killed') return '已终止'
  return '执行中'
}

function formatDuration(durationMs: number) {
  const totalSeconds = Math.max(0, Math.floor(durationMs / 1000))
  const hours = Math.floor(totalSeconds / 3600)
  const minutes = Math.floor((totalSeconds % 3600) / 60)
  const seconds = totalSeconds % 60

  if (hours > 0) {
    return `${String(hours).padStart(2, '0')}:${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
  }

  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
}

function formatSubagentType(subagentType?: string) {
  if (!subagentType) return '子 agent'
  if (subagentType === 'general-purpose') return '通用'
  return subagentType
}

function useNow(active: boolean) {
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    if (!active) return

    const timer = window.setInterval(() => {
      setNow(Date.now())
    }, 1000)

    return () => window.clearInterval(timer)
  }, [active])

  useEffect(() => {
    if (!active) {
      setNow(Date.now())
    }
  }, [active])

  return now
}

function renderToolCard(
  tool: ToolEntry,
) {
  return (
    <Tool className="mb-0" defaultOpen={false} key={tool.id}>
      <ToolHeader
        title={tool.name}
        type={'tool-invocation' as never}
        state={tool.state as never}
      />
      <ToolContent>
        {tool.input !== undefined ? <ToolInput input={tool.input as never} /> : null}

        {tool.state === 'approval-requested' && !tool.approval && tool.errorText ? (
          <div className="pb-4 text-xs text-muted-foreground">
            等待授权：{tool.errorText}
          </div>
        ) : null}

        {tool.output !== undefined || tool.errorText ? (
          <ToolOutput errorText={tool.errorText as never} output={tool.output as never} />
        ) : null}
      </ToolContent>
    </Tool>
  )
}

function ProcessPanel({
  process,
  titlePrefix,
}: {
  process: ProcessSegment
  titlePrefix?: string
}) {
  const [open, setOpen] = useState(false)
  const nowMs = useNow(process.status === 'running')

  useEffect(() => {
    if (process.status !== 'running') {
      setOpen(false)
    }
  }, [process.status, process.endedAtMs])

  const visibleTools = useMemo(
    () => process.tools.filter(tool => !isSubagentLaunchTool(tool.name)),
    [process.tools],
  )
  const totalDurationMs =
    process.endedAtMs !== undefined
      ? process.endedAtMs - process.startedAtMs
      : nowMs - process.startedAtMs
  const summaryBits = [
    visibleTools.length > 0 ? `${visibleTools.length} 个工具` : '',
  ].filter(Boolean)

  return (
    <ChainOfThought onOpenChange={setOpen} open={open}>
      <ChainOfThoughtHeader>
        <span className="flex min-w-0 items-center gap-2">
          {process.status === 'running' ? (
            <TextShimmer as="span" className="text-sm">
              {titlePrefix ? `${titlePrefix}：思考中` : '思考中'}
            </TextShimmer>
          ) : (
            <span>{titlePrefix ? `${titlePrefix}：${processLabel(process.status)}` : processLabel(process.status)}</span>
          )}
          <span className="tabular-nums text-xs text-muted-foreground">
            {formatDuration(totalDurationMs)}
          </span>
          {summaryBits.length > 0 ? (
            <span className="truncate text-xs text-muted-foreground">
              {summaryBits.join(' / ')}
            </span>
          ) : null}
        </span>
      </ChainOfThoughtHeader>

      <ChainOfThoughtContent>
        <div className="space-y-4">
          {process.stepLabel ? (
            <ChainOfThoughtStep
              label={titlePrefix ? `${titlePrefix}：${process.stepLabel}` : process.stepLabel}
              status={process.status === 'running' ? 'active' : 'complete'}
            />
          ) : null}

          {visibleTools.length > 0 ? (
            <div className="space-y-2">
              {visibleTools.map(tool => renderToolCard(tool))}
            </div>
          ) : null}

        </div>
      </ChainOfThoughtContent>
    </ChainOfThought>
  )
}

function ReasoningPanel({ reasoning }: { reasoning: ReasoningSegment }) {
  return (
    <ChainOfThought defaultOpen={reasoning.streaming}>
      <ChainOfThoughtHeader>
        <span className="flex min-w-0 items-center gap-2">
          {reasoning.streaming ? (
            <TextShimmer as="span" className="text-sm">
              思考中
            </TextShimmer>
          ) : (
            <span>思考过程</span>
          )}
        </span>
      </ChainOfThoughtHeader>
      <ChainOfThoughtContent>
        <div className="whitespace-pre-wrap break-words rounded-md bg-muted/40 px-3 py-2 text-sm leading-6 text-muted-foreground">
          {reasoning.content}
        </div>
      </ChainOfThoughtContent>
    </ChainOfThought>
  )
}

function TodoQueuePanel({ segment }: { segment: TodoSegment }) {
  if (segment.items.length === 0) {
    return null
  }

  return (
    <Queue className="mx-auto w-full max-w-xl">
      <QueueSection defaultOpen>
        <QueueSectionTrigger>
          <QueueSectionLabel count={segment.items.length} label="任务清单" />
        </QueueSectionTrigger>
        <QueueSectionContent>
          <QueueList>
            {segment.items.map((item, index) => {
              const completed = item.status === 'completed'
              const inProgress = item.status === 'in_progress'

              return (
                <QueueItem key={`${item.content}-${index}`}>
                  <div className="flex items-center gap-2">
                    <QueueItemIndicator completed={completed} />
                    <QueueItemContent
                      className={cn(inProgress && 'text-foreground')}
                      completed={completed}
                    >
                      {item.content}
                    </QueueItemContent>
                  </div>
                  {item.activeForm ? (
                    <QueueItemDescription completed={completed}>
                      {item.activeForm}
                    </QueueItemDescription>
                  ) : null}
                </QueueItem>
              )
            })}
          </QueueList>
        </QueueSectionContent>
      </QueueSection>
    </Queue>
  )
}

function ApprovalPanel({
  approval,
  resolvingApprovals,
  resolveApproval,
}: {
  approval: ApprovalSegment
  resolvingApprovals: string[]
  resolveApproval?: (approvalId: string, decision: ApprovalDecision) => void
}) {
  const isResolving = resolvingApprovals.includes(approval.approvalId)
  const command = isBashTool(approval.toolName) ? commandFromApprovalInput(approval.input) : null
  const state =
    approval.approved === undefined
      ? 'approval-requested'
      : approval.approved
        ? 'approval-responded'
        : 'output-denied'

  return (
    <div className="space-y-2">
      <Confirmation
        approval={toConfirmationApproval({
          id: approval.approvalId,
          approved: approval.approved,
          reason: approval.reason,
        })}
        state={state as never}
      >
        <ConfirmationTitle>
          <ConfirmationRequest>
            工具 <code>{approval.toolName}</code> 需要授权{approval.reason ? `：${approval.reason}` : '。'}
          </ConfirmationRequest>
          <ConfirmationAccepted>
            <span className="inline-flex items-center gap-2">
              <CheckCircle2 size={14} />
              已允许 {approval.toolName}
            </span>
          </ConfirmationAccepted>
          <ConfirmationRejected>
            <span className="inline-flex items-center gap-2">
              <XCircle size={14} />
              已拒绝 {approval.toolName}
            </span>
          </ConfirmationRejected>
        </ConfirmationTitle>

        {resolveApproval ? (
          <ConfirmationActions>
            <ConfirmationAction
              disabled={isResolving}
              onClick={() => void resolveApproval(approval.approvalId, 'deny')}
              variant="ghost"
            >
              拒绝
            </ConfirmationAction>
            <ConfirmationAction
              disabled={isResolving}
              onClick={() => void resolveApproval(approval.approvalId, 'allow_once')}
              variant="default"
            >
              允许
            </ConfirmationAction>
            <ConfirmationAction
              disabled={isResolving}
              onClick={() => void resolveApproval(approval.approvalId, 'allow_for_session')}
              variant="secondary"
            >
              本会话允许
            </ConfirmationAction>
          </ConfirmationActions>
        ) : null}
      </Confirmation>

      {command ? <Terminal className="text-left" output={command} /> : null}
    </div>
  )
}

function SubagentInfoCard({ subagent }: { subagent: SubagentEntry }) {
  const nowMs = useNow(subagent.status === 'running')
  const elapsedMs =
    subagent.totalDurationMs ??
    ((subagent.endedAtMs ?? nowMs) - subagent.startedAtMs)

  return (
    <Agent className="border-0 bg-transparent">
      <AgentHeader
        className="p-0"
        model={formatSubagentType(subagent.subagentType)}
        name={`启动子 agent：${subagent.name}`}
      />
      <AgentContent className="space-y-2 px-6 pt-1 pb-0">
        <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-muted-foreground">
          {subagent.description && subagent.description !== subagent.name ? (
            <span>任务：{subagent.description}</span>
          ) : null}
          <span>状态：{subagentStatusLabel(subagent.status)}</span>
          <span className="tabular-nums">耗时：{formatDuration(elapsedMs)}</span>
          {typeof subagent.totalToolUseCount === 'number' ? (
            <span>工具调用：{subagent.totalToolUseCount}</span>
          ) : null}
        </div>
      </AgentContent>
    </Agent>
  )
}

function SubagentExecutionFlow({ subagent }: { subagent: SubagentEntry }) {
  if (subagent.segments.length === 0) {
    return null
  }

  return (
    <div className="ml-2 max-h-[420px] space-y-3 overflow-y-auto border-l border-border/70 pl-5 pr-1">
      {subagent.segments.map(segment =>
        segment.type === 'process' ? (
          <ProcessPanel
            key={segment.id}
            process={segment}
          />
        ) : segment.type === 'approval' ? (
          <ApprovalPanel
            approval={segment}
            key={segment.id}
            resolvingApprovals={[]}
          />
        ) : segment.type === 'todo' ? (
          null
        ) : segment.type === 'reasoning' ? (
          <ReasoningPanel key={segment.id} reasoning={segment} />
        ) : segment.type === 'subagent' ? (
          <SubagentRunBlock key={segment.id} subagent={segment.subagent} />
        ) : (
          <div className="py-1" key={segment.id}>
            <div className="mb-2 text-xs font-medium text-muted-foreground">{subagent.name}：</div>
            <MessageResponse>{segment.content}</MessageResponse>
          </div>
        ),
      )}
    </div>
  )
}

function SubagentRunBlock({ subagent }: { subagent: SubagentEntry }) {
  return (
    <div className="min-w-0 space-y-2 rounded-md border border-border/70 bg-background px-3 py-3">
      <SubagentInfoCard subagent={subagent} />
      <SubagentExecutionFlow subagent={subagent} />
    </div>
  )
}

function AssistantSegmentsView({
  segments,
  resolvingApprovals,
  resolveApproval,
}: {
  segments: AssistantSegment[]
  resolvingApprovals: string[]
  resolveApproval?: (approvalId: string, decision: ApprovalDecision) => void
}) {
  const nodes = []

  for (let index = 0; index < segments.length; index += 1) {
    const segment = segments[index]

    if (segment.type === 'subagent') {
      while (segments[index + 1]?.type === 'subagent') {
        index += 1
      }
      continue
    }

    if (segment.type === 'todo') {
      continue
    }

    if (segment.type === 'process') {
      nodes.push(<ProcessPanel key={segment.id} process={segment} />)
      continue
    }

    if (segment.type === 'reasoning') {
      nodes.push(<ReasoningPanel key={segment.id} reasoning={segment} />)
      continue
    }

    if (segment.type === 'approval') {
      nodes.push(
        <ApprovalPanel
          approval={segment}
          key={segment.id}
          resolveApproval={resolveApproval}
          resolvingApprovals={resolvingApprovals}
        />,
      )
      continue
    }

    if (segment.streaming) {
      nodes.push(
        <div className="whitespace-pre-wrap break-words text-sm leading-6" key={segment.id}>
          {segment.content}
        </div>,
      )
      continue
    }

    nodes.push(<MessageResponse key={segment.id}>{segment.content}</MessageResponse>)
  }

  return <>{nodes}</>
}

function ChatMessageCopyAction({
  copied,
  onCopy,
}: {
  copied: boolean
  onCopy: () => void
}) {
  return (
    <MessageAction
      className={cn(
        'text-muted-foreground hover:text-foreground',
        copied && 'text-emerald-600 hover:text-emerald-700',
      )}
      label={copied ? '已复制' : '复制消息'}
      onClick={onCopy}
      tooltip={copied ? '已复制' : '复制'}
    >
      {copied ? <Check className="size-3.5" /> : <Copy className="size-3.5" />}
    </MessageAction>
  )
}

function latestTodoSegmentFromSegments(segments: AssistantSegment[] | undefined): TodoSegment | null {
  const next = segments ?? []

  for (let segmentIndex = next.length - 1; segmentIndex >= 0; segmentIndex -= 1) {
    const segment = next[segmentIndex]
    if (segment.type === 'todo' && segment.items.length > 0) {
      return segment
    }

    if (segment.type === 'subagent') {
      const subagentTodo = latestTodoSegmentFromSegments(segment.subagent.segments)
      if (subagentTodo) return subagentTodo
    }
  }

  return null
}

function latestTodoSegment(messages: ChatEntry[]) {
  for (let entryIndex = messages.length - 1; entryIndex >= 0; entryIndex -= 1) {
    const segment = latestTodoSegmentFromSegments(messages[entryIndex].segments)
    if (segment) return segment
  }

  return null
}

function RunModeControl({
  disabled,
  mode,
  onModeChange,
}: {
  disabled?: boolean
  mode: RunModeValue
  onModeChange: (mode: RunModeValue) => void
}) {
  const handleModeChange = useCallback((value: string | null) => {
    if (value !== 'chat' && value !== 'agent') return
    onModeChange(value)
  }, [onModeChange])

  return (
    <Select
      disabled={disabled}
      onValueChange={handleModeChange}
      value={mode}
    >
      <SelectTrigger
        aria-label="运行模式"
        className="h-7 max-w-[120px] gap-1 rounded-md border-transparent bg-muted/60 px-2 text-xs text-black shadow-none hover:bg-muted disabled:opacity-100"
        size="sm"
      >
        <span className="truncate text-black">{runModeLabel(mode)}</span>
      </SelectTrigger>
      <SelectPositioner align="start">
        <SelectContent className="text-black">
          {RUN_MODE_OPTIONS.map(option => (
            <SelectItem className="text-black" key={option.value} value={option.value}>
              {option.label}
            </SelectItem>
          ))}
        </SelectContent>
      </SelectPositioner>
    </Select>
  )
}

function SessionControls({
  disabled,
  onPermissionModeChange,
  onWorkspaceChange,
  permissionMode,
  workspaceOptions,
  workingDir,
}: {
  disabled?: boolean
  onPermissionModeChange: (mode: PermissionModeValue) => void
  onWorkspaceChange: (path: string) => void
  permissionMode: PermissionModeValue
  workspaceOptions: string[]
  workingDir: string
}) {
  const currentWorkspace = workingDir || workspaceOptions[0] || ''
  const options = Array.from(new Set([currentWorkspace, ...workspaceOptions].filter(Boolean)))
  const [confirmAutoApproveOpen, setConfirmAutoApproveOpen] = useState(false)

  const handleWorkspaceChange = useCallback(async (value: string | null) => {
    if (!value) return

    if (value === SELECT_WORKSPACE_VALUE) {
      const selected = await openDialog({
        multiple: false,
        directory: true,
        title: '选择助手工作目录',
      })
      if (selected && !Array.isArray(selected)) {
        onWorkspaceChange(selected)
      }
      return
    }

    onWorkspaceChange(value)
  }, [onWorkspaceChange])

  const handlePermissionModeChange = useCallback((value: string | null) => {
    const nextMode = normalizePermissionMode(value)
    if (nextMode === permissionMode) return

    if (nextMode === 'auto_approve') {
      setConfirmAutoApproveOpen(true)
      return
    }

    onPermissionModeChange(nextMode)
  }, [onPermissionModeChange, permissionMode])

  const confirmAutoApprove = useCallback(() => {
    setConfirmAutoApproveOpen(false)
    onPermissionModeChange('auto_approve')
  }, [onPermissionModeChange])

  return (
    <div className="flex min-w-0 flex-wrap items-center gap-2">
      <Select
        disabled={disabled}
        onValueChange={handleWorkspaceChange}
        value={currentWorkspace}
      >
        <SelectTrigger
          aria-label="工作目录"
          className="h-7 max-w-[220px] gap-1 rounded-md border-transparent bg-muted/60 px-2 text-xs text-black shadow-none hover:bg-muted disabled:opacity-100 *:data-[slot=select-value]:text-black"
          size="sm"
        >
          <FolderOpen className="size-3.5 text-muted-foreground" />
          <SelectValue className="text-black" placeholder="工作目录" />
        </SelectTrigger>
        <SelectPositioner align="start">
          <SelectContent className="text-black">
            {options.map(option => (
              <SelectItem className="text-black" key={option} value={option}>
                {formatPathLabel(option)}
              </SelectItem>
            ))}
            <SelectItem className="text-black" value={SELECT_WORKSPACE_VALUE}>选择文件夹...</SelectItem>
          </SelectContent>
        </SelectPositioner>
      </Select>

      <Select
        disabled={disabled}
        onValueChange={handlePermissionModeChange}
        value={permissionMode}
      >
        <SelectTrigger
          aria-label="权限模式"
          className="h-7 max-w-[170px] gap-1 rounded-md border-transparent bg-muted/60 px-2 text-xs text-black shadow-none hover:bg-muted disabled:opacity-100"
          size="sm"
        >
          <ShieldCheck className="size-3.5 text-muted-foreground" />
          <span className="truncate text-black">{permissionModeLabel(permissionMode)}</span>
        </SelectTrigger>
        <SelectPositioner align="start">
          <SelectContent className="text-black">
            {PERMISSION_MODE_OPTIONS.map(option => (
              <SelectItem className="text-black" key={option.value} value={option.value}>
                {option.label}
              </SelectItem>
            ))}
          </SelectContent>
        </SelectPositioner>
      </Select>

      <AlertDialog open={confirmAutoApproveOpen} onOpenChange={setConfirmAutoApproveOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认开启自动批准？</AlertDialogTitle>
            <AlertDialogDescription>
              自动批准会跳过工具权限询问，允许助手直接执行可用工具。请只在你信任当前任务和工作目录时开启。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction onClick={confirmAutoApprove}>
              开启自动批准
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}

function EmptyAssistantPrompt({
  controls,
  disabled,
  onSubmit,
}: {
  controls?: React.ReactNode
  disabled?: boolean
  onSubmit: (text: string) => void | Promise<void>
}) {
  const [value, setValue] = useState('')

  const handleSubmit = useCallback((event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault()

    const nextText = value.trim()
    if (!nextText || disabled) {
      return
    }

    setValue('')
    void onSubmit(nextText)
  }, [disabled, onSubmit, value])

  return (
    <div className="mx-auto flex min-h-[55vh] w-full max-w-3xl flex-col items-center justify-center px-4 text-center">
      <div className="mb-7 space-y-3">
        <div className="text-[11px] font-medium uppercase tracking-[0.24em] text-muted-foreground">
          Veyra Assistant
        </div>
        <h2 className="text-balance text-2xl font-semibold tracking-normal text-foreground sm:text-3xl">
          从一句话开始，把工作交给助手推进。
        </h2>
        <p className="mx-auto max-w-xl text-sm leading-6 text-muted-foreground">
          输入你想整理、改写、检查或执行的任务，助手会在这里展开过程和结果。
        </p>
      </div>

      <div className={cn('w-full max-w-xl transition-opacity', disabled && 'pointer-events-none opacity-60')}>
        <PlaceholdersAndVanishInput
          onChange={event => setValue(event.currentTarget.value)}
          onSubmit={handleSubmit}
          placeholders={EMPTY_PROMPT_PLACEHOLDERS}
        />
        {controls ? (
          <div className="mt-3 flex justify-start">
            {controls}
          </div>
        ) : null}
      </div>
    </div>
  )
}

function AgentContextConsole({
  contextTurns,
  open,
  onOpenChange,
}: {
  contextTurns: LlmContextTurn[]
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  return (
    <>
      <button
        aria-label="打开 LLM 上下文视图"
        className={cn(
          'absolute top-3 left-3 z-20 inline-flex h-8 items-center gap-2 rounded-md border border-border bg-white/95 px-2.5 text-xs font-medium shadow-sm backdrop-blur transition-colors',
          'hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/30',
          'text-muted-foreground',
        )}
        onClick={() => onOpenChange(true)}
        type="button"
      >
        <FileText className="size-3.5" />
        <span>上下文</span>
        <span className="tabular-nums">{contextTurns.length} 轮</span>
      </button>

      <Dialog onOpenChange={onOpenChange} open={open}>
        <DialogContent className="flex max-h-[82vh] max-w-5xl flex-col overflow-hidden p-0">
          <div className="border-b border-border px-5 py-4">
            <DialogTitle>Agent 上下文</DialogTitle>
            <div className="mt-2 flex flex-wrap gap-3 text-xs text-muted-foreground">
              <span>上下文轮次：{contextTurns.length}</span>
              <span>消息：{contextTurns.reduce((total, turn) => total + turn.messageCount, 0)}</span>
            </div>
          </div>

          <div className="min-h-0 flex-1 overflow-y-auto bg-muted/25 p-4">
            <LlmContextPanel turns={contextTurns} />
          </div>
        </DialogContent>
      </Dialog>
    </>
  )
}

function LlmContextPanel({ turns }: { turns: LlmContextTurn[] }) {
  if (turns.length === 0) {
    return (
      <div className="flex min-h-48 items-center justify-center rounded-lg border border-dashed border-border bg-white text-sm text-muted-foreground">
        暂无 LLM 上下文。
      </div>
    )
  }

  return (
    <div className="space-y-4">
      {turns.map(turn => (
        <div className="rounded-lg border bg-white shadow-xs" key={turn.turnIndex}>
          <div className="border-b border-border px-4 py-3">
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs">
              <span className="font-medium text-foreground">Turn {turn.turnIndex}</span>
              <span className="text-muted-foreground">{turn.messageCount} messages</span>
              <span className="text-muted-foreground">{turn.estimatedTokens} tokens</span>
            </div>
          </div>

          <div className="divide-y divide-border/60">
            {turn.messages.map(message => (
              <LlmMessageBlock key={`${turn.turnIndex}-${message.index}`} message={message} />
            ))}
          </div>

          {turn.output ? (
            <div className="border-t border-border bg-muted/20 px-4 py-3">
              <div className="mb-2 text-xs font-medium text-foreground">LLM OUTPUT</div>
              {turn.output.text ? (
                <pre className="max-h-64 overflow-auto whitespace-pre-wrap break-words rounded bg-white p-3 font-mono text-[11px] leading-5 text-foreground">
                  {turn.output.text}
                </pre>
              ) : null}
              {turn.output.toolCalls.length > 0 ? (
                <div className="mt-2 space-y-2">
                  {turn.output.toolCalls.map((toolCall, index) => (
                    <pre className="overflow-auto whitespace-pre-wrap break-words rounded bg-white p-3 font-mono text-[11px] leading-5 text-foreground" key={`${toolCall.name}-${index}`}>
                      {`${toolCall.name}\n${toolCall.arguments}`}
                    </pre>
                  ))}
                </div>
              ) : null}
              {!turn.output.text && turn.output.toolCalls.length === 0 ? (
                <div className="text-xs text-muted-foreground">空响应</div>
              ) : null}
            </div>
          ) : null}
        </div>
      ))}
    </div>
  )
}

function LlmMessageBlock({ message }: { message: LlmContextMessage }) {
  const lineCount = message.content.split('\n').length
  const shouldFold = message.content.length > 2400 || lineCount > 50
  const [expanded, setExpanded] = useState(!shouldFold)
  const visibleContent = expanded || !shouldFold
    ? message.content
    : message.content.split('\n').slice(0, 40).join('\n')

  return (
    <div className="px-4 py-3">
      <div className="mb-2 flex items-center justify-between gap-3">
        <div className="text-xs font-semibold uppercase tracking-normal text-muted-foreground">
          {message.title || message.role}
        </div>
        {shouldFold ? (
          <button
            className="shrink-0 rounded px-2 py-1 text-xs text-muted-foreground hover:bg-muted hover:text-foreground"
            onClick={() => setExpanded(open => !open)}
            type="button"
          >
            {expanded ? '收起' : '展开全文'}
          </button>
        ) : null}
      </div>
      <pre className="max-h-[28rem] overflow-auto whitespace-pre-wrap break-words rounded bg-muted/40 p-3 font-mono text-[11px] leading-5 text-foreground">
        {visibleContent}
        {!expanded && shouldFold ? '\n\n...' : ''}
      </pre>
    </div>
  )
}

function ChatPanel({ width, initialInput, workspaceRoot }: Props) {
  const [sessionId, setSessionId] = useState<string | null>(null)
  const [panelState, setPanelState] = useState<PanelState>('connecting')
  const [draft, setDraft] = useState(initialInput ?? '')
  const [messages, setMessages] = useState<ChatEntry[]>([])
  const [copiedMessageId, setCopiedMessageId] = useState<string | null>(null)
  const [contextOpen, setContextOpen] = useState(false)
  const [llmContextTurns, setLlmContextTurns] = useState<LlmContextTurn[]>([])
  const [contextWarning, setContextWarning] = useState<{
    tokenCount: number
    percentLeft: number
    aboveWarning: boolean
    aboveError: boolean
    aboveThreshold: boolean
    atBlockingLimit: boolean
    maxContextTokens: number
    threshold: number
  } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [resolvingApprovals, setResolvingApprovals] = useState<string[]>([])
  const [detailSubagent, setDetailSubagent] = useState<SubagentEntry | null>(null)
  const [workingDir, setWorkingDir] = useState('')
  const [permissionMode, setPermissionMode] = useState<PermissionModeValue>('ask_every_time')
  const [runMode, setRunMode] = useState<RunModeValue>('chat')
  const tokenBufferRef = useRef(new Map<string, { text: string; timestampMs: number }>())
  const tokenFlushTimerRef = useRef<number | null>(null)
  const copyResetTimerRef = useRef<number | null>(null)

  useEffect(() => {
    if (typeof initialInput === 'string') {
      setDraft(initialInput)
    }
  }, [initialInput])

  useEffect(() => {
    let closed = false
    let source: EventSource | null = null
    let reconnectTimer: number | null = null
    let connectionErrorVisible = false

    const flushTokenBuffer = () => {
      tokenFlushTimerRef.current = null
      const buffered = Array.from(tokenBufferRef.current.entries())
      tokenBufferRef.current.clear()
      if (buffered.length === 0) return

      setMessages(current => {
        let next = current
        for (const [bufferRunId, buffer] of buffered) {
          next = updateAssistantEntry(next, bufferRunId || undefined, entry => ({
            ...entry,
            streaming: true,
            segments: appendTextSegment(entry.segments, buffer.text, buffer.timestampMs),
          }))
        }
        return next
      })
    }

    const queueToken = (tokenRunId: string | undefined, text: string, timestampMs: number) => {
      const key = tokenRunId ?? ''
      const existing = tokenBufferRef.current.get(key)
      tokenBufferRef.current.set(key, {
        text: `${existing?.text ?? ''}${text}`,
        timestampMs,
      })

      if (tokenFlushTimerRef.current === null) {
        tokenFlushTimerRef.current = window.setTimeout(flushTokenBuffer, 50)
      }
    }

    const eventTypes = [
      'session.ready',
      'run.started',
      'assistant.thinking.token',
      'assistant.token',
      'assistant.message.completed',
      'tool.call.started',
      'tool.call.completed',
      'tool.call.failed',
      'tool.call.rejected',
      'permission.requested',
      'permission.resolved',
      'todo.updated',
      'task.started',
      'task.step.started',
      'task.assistant.message.completed',
      'task.tool.call.started',
      'task.tool.call.completed',
      'task.tool.call.rejected',
      'task.permission.requested',
      'task.permission.resolved',
      'task.completed',
      'task.failed',
      'task.killed',
      'run.completed',
      'run.failed',
      'context.warning',
      'context.snapshot',
      'llm.output',
    ] as const

    const handleAgentEvent = (event: MessageEvent<string>) => {
      const data = JSON.parse(event.data) as AgentEvent
      if (data.type === 'context.snapshot' || data.type === 'llm.output') {
        setLlmContextTurns(current => applyLlmContextEvent(current, {
          type: data.type,
          payload: data.payload ?? {},
        }).slice(-100))
      }

      const runId = data.runId ?? undefined
      const payload = data.payload ?? {}

      if (data.type === 'context.warning') {
        setContextWarning({
          tokenCount: payload.tokenCount as number,
          percentLeft: payload.percentLeft as number,
          aboveWarning: payload.aboveWarning as boolean,
          aboveError: payload.aboveError as boolean,
          aboveThreshold: payload.aboveThreshold as boolean,
          atBlockingLimit: payload.atBlockingLimit as boolean,
          maxContextTokens: payload.maxContextTokens as number,
          threshold: payload.threshold as number,
        })
        return
      }

      if (data.type === 'session.ready') {
        setPanelState(current => (current === 'connecting' ? 'ready' : current))
        setError(current => (current === AGENT_EVENT_CONNECTION_ERROR ? null : current))
        return
      }

      if (data.type === 'run.started') {
        setPanelState('running')
        setMessages(current =>
          updateAssistantEntry(current, runId, entry => ({
            ...entry,
            streaming: true,
            final: false,
            segments: entry.segments ?? [],
          })),
        )
        return
      }

      if (data.type === 'assistant.token') {
        const text = typeof payload.text === 'string' ? payload.text : ''
        if (!text) return

        queueToken(runId, text, data.timestampMs)
        return
      }

      if (data.type === 'assistant.thinking.token') {
        const text = typeof payload.text === 'string' ? payload.text : ''
        if (!text) return

        setMessages(current =>
          updateAssistantEntry(current, runId, entry => ({
            ...entry,
            streaming: true,
            segments: appendReasoningSegment(entry.segments, text),
          })),
        )
        return
      }

      if (data.type === 'assistant.message.completed') {
        const text = typeof payload.text === 'string' ? payload.text : ''
        const thinking = typeof payload.thinking === 'string' ? payload.thinking : ''
        const hasToolRequests = payload.hasToolRequests === true
        const closeProcess = shouldCloseProcessOnAssistantMessage(hasToolRequests)
        flushTokenBuffer()
        setMessages(current =>
          updateAssistantEntry(current, runId, entry => ({
            ...entry,
            streaming: false,
            segments: completeTextSegment(
              completeReasoningSegment(entry.segments, thinking),
              text,
              data.timestampMs,
              closeProcess,
            ),
          })),
        )
        return
      }

      if (data.type === 'tool.call.started') {
        const name = typeof payload.name === 'string' ? payload.name : '工具'
        if (isTodoTool(name)) return
        if (isSubagentLaunchTool(name)) {
          setMessages(current =>
            updateAssistantProcess(current, runId, data.timestampMs, process => process),
          )
          return
        }

        setMessages(current =>
          updateAssistantProcess(current, runId, data.timestampMs, process => ({
            ...process,
            tools: appendTool(process.tools, name, 'input-available', normalizeToolValue(payload.arguments)),
          })),
        )
        return
      }

      if (data.type === 'permission.requested') {
        const name = typeof payload.tool === 'string' ? payload.tool : '工具'
        if (isTodoTool(name)) return
        if (isSubagentLaunchTool(name)) return

        const approvalId = typeof payload.approvalId === 'string' ? payload.approvalId : newId('approval')
        const reason = typeof payload.reason === 'string' ? payload.reason : ''

        setMessages(current =>
          updateAssistantEntry(current, runId, entry => ({
            ...entry,
            segments: (() => {
              const input = normalizeToolValue(payload.arguments)
              const hydrated = hydrateLatestPendingApproval(entry.segments, approvalId, name, reason, input)

              if (hydrated.hydrated) {
                return hydrated.segments
              }

              return appendApprovalSegment(entry.segments, data.timestampMs, {
                id: newId('approval'),
                approvalId,
                toolName: name,
                reason,
                input,
              })
            })(),
          })),
        )
        return
      }

      if (data.type === 'permission.resolved') {
        const approvalId = typeof payload.approvalId === 'string' ? payload.approvalId : ''
        const decision = typeof payload.decision === 'string' ? payload.decision : ''
        const approved = decision.startsWith('allow')

        setResolvingApprovals(current => current.filter(item => item !== approvalId))
        setMessages(current =>
          current.map(entry => ({
            ...entry,
            segments: updateApprovalSegment(entry.segments, approvalId, approved),
          })),
        )
        return
      }

      if (data.type === 'tool.call.completed') {
        const name = typeof payload.name === 'string' ? payload.name : '工具'
        if (isTodoTool(name)) return
        if (isSubagentLaunchTool(name)) return

        setMessages(current =>
          updateAssistantEntry(current, runId, entry => ({
            ...entry,
            segments: updateToolInProcessSegment(entry.segments, name, data.timestampMs, tool => ({
              id: tool?.id ?? newId('tool'),
              name,
              state: 'output-available',
              input: tool?.input,
              output: normalizeToolValue(payload.content),
              errorText: undefined,
              approval: tool?.approval,
            })),
          })),
        )
        return
      }

      if (data.type === 'tool.call.failed') {
        const name = typeof payload.name === 'string' ? payload.name : '工具'
        if (isTodoTool(name)) return
        if (isSubagentLaunchTool(name)) return

        const message = typeof payload.error === 'string' ? payload.error : '工具执行失败'
        setMessages(current =>
          updateAssistantEntry(current, runId, entry => ({
            ...entry,
            segments: updateToolInProcessSegment(entry.segments, name, data.timestampMs, tool => ({
              id: tool?.id ?? newId('tool'),
              name,
              state: 'output-error',
              input: tool?.input,
              output: undefined,
              errorText: message,
              approval: tool?.approval,
            })),
          })),
        )
        return
      }

      if (data.type === 'tool.call.rejected') {
        const name = typeof payload.name === 'string' ? payload.name : '工具'
        if (isTodoTool(name)) return
        if (isSubagentLaunchTool(name)) return

        const message = typeof payload.reason === 'string' ? payload.reason : '工具执行被拒绝'
        setMessages(current =>
          updateAssistantEntry(current, runId, entry => ({
            ...entry,
            segments: updateToolInProcessSegment(entry.segments, name, data.timestampMs, tool => ({
              id: tool?.id ?? newId('tool'),
              name,
              state: 'output-denied',
              input: tool?.input,
              output: undefined,
              errorText: message,
              approval: tool?.approval,
            })),
          })),
        )
        return
      }

      if (data.type === 'todo.updated') {
        const items: TodoItem[] = []

        if (Array.isArray(payload.items)) {
          payload.items.forEach(item => {
            if (!item || typeof item !== 'object') return

            const todo = item as Record<string, unknown>
            const content = typeof todo.content === 'string' ? todo.content : ''
            const status = todo.status

            if (
              !content ||
              (status !== 'pending' && status !== 'in_progress' && status !== 'completed')
            ) {
              return
            }

            items.push({
              content,
              status,
              activeForm: typeof todo.activeForm === 'string' ? todo.activeForm : undefined,
            })
          })
        }

        setMessages(current =>
          updateAssistantEntry(current, runId, entry => ({
            ...entry,
            segments: updateTodoSegment(entry.segments, items, data.timestampMs),
          })),
        )
        return
      }

      if (data.type === 'task.started') {
        const taskId = typeof payload.taskId === 'string' ? payload.taskId : newId('task')
        const description = typeof payload.description === 'string' && payload.description.trim()
          ? payload.description.trim()
          : '子 Agent'
        const agentName = typeof payload.name === 'string' && payload.name.trim()
          ? payload.name.trim()
          : description
        const subagentType = typeof payload.subagentType === 'string' ? payload.subagentType : undefined

        setMessages(current =>
          updateAssistantEntry(current, runId, entry => ({
            ...entry,
            segments: updateSubagentInSegments(closeRunningProcessSegments(entry.segments, data.timestampMs), taskId, data.timestampMs, subagent => ({
              ...subagent,
              name: agentName,
              description,
              subagentType,
              status: 'running',
              startedAtMs: subagent.startedAtMs ?? data.timestampMs,
            })),
          })),
        )
        return
      }

      if (data.type === 'task.step.started') {
        const taskId = typeof payload.taskId === 'string' ? payload.taskId : ''
        const round = typeof payload.round === 'number' ? payload.round : undefined
        const label = round ? `第 ${round} 轮处理` : '继续处理中'
        if (!taskId) return

        setMessages(current =>
          updateAssistantEntry(current, runId, entry => ({
            ...entry,
            segments: updateSubagentInSegments(entry.segments, taskId, data.timestampMs, subagent => ({
              ...subagent,
              status: 'running',
              segments: updateProcessSegment(subagent.segments, data.timestampMs, process => ({
                ...process,
                stepLabel: label,
              })),
            })),
          })),
        )
        return
      }

      if (data.type === 'task.assistant.message.completed') {
        const taskId = typeof payload.taskId === 'string' ? payload.taskId : ''
        const text = typeof payload.text === 'string' ? payload.text : ''
        if (!taskId || !text) return

        setMessages(current =>
          updateAssistantEntry(current, runId, entry => ({
            ...entry,
            segments: updateSubagentInSegments(entry.segments, taskId, data.timestampMs, subagent => ({
              ...subagent,
              segments: completeTextSegment(subagent.segments, text, data.timestampMs),
            })),
          })),
        )
        return
      }

      if (data.type === 'task.tool.call.started') {
        const taskId = typeof payload.taskId === 'string' ? payload.taskId : ''
        const name = typeof payload.name === 'string' ? payload.name : '工具'
        if (!taskId) return

        setMessages(current =>
          updateAssistantEntry(current, runId, entry => ({
            ...entry,
            segments: updateSubagentInSegments(entry.segments, taskId, data.timestampMs, subagent => ({
              ...subagent,
              segments: updateProcessSegment(subagent.segments, data.timestampMs, process => ({
                ...process,
                tools: appendTool(
                  process.tools,
                  name,
                  'input-available',
                  normalizeToolValue(payload.arguments),
                ),
              })),
            })),
          })),
        )
        return
      }

      if (data.type === 'task.permission.requested') {
        const taskId = typeof payload.taskId === 'string' ? payload.taskId : ''
        const name = typeof payload.name === 'string' ? payload.name : '工具'
        const reason = typeof payload.reason === 'string' ? payload.reason : '等待授权'
        if (!taskId) return

        setMessages(current =>
          updateAssistantEntry(current, runId, entry => ({
            ...entry,
            segments: updateSubagentInSegments(entry.segments, taskId, data.timestampMs, subagent => ({
              ...subagent,
              segments: appendApprovalSegment(subagent.segments, data.timestampMs, {
                id: newId('approval'),
                approvalId: `task:${taskId}:${name}`,
                toolName: name,
                reason,
                input: normalizeToolValue(payload.arguments),
              }),
            })),
          })),
        )
        return
      }

      if (data.type === 'task.permission.resolved') {
        const taskId = typeof payload.taskId === 'string' ? payload.taskId : ''
        const name = typeof payload.name === 'string' ? payload.name : '工具'
        const decision = typeof payload.decision === 'string' ? payload.decision : ''
        const approved = decision.startsWith('allow')
        if (!taskId) return

        setMessages(current =>
          updateAssistantEntry(current, runId, entry => ({
            ...entry,
            segments: updateSubagentInSegments(entry.segments, taskId, data.timestampMs, subagent => ({
              ...subagent,
              segments: updateApprovalSegment(subagent.segments, `task:${taskId}:${name}`, approved),
            })),
          })),
        )
        return
      }

      if (data.type === 'task.tool.call.completed') {
        const taskId = typeof payload.taskId === 'string' ? payload.taskId : ''
        const name = typeof payload.name === 'string' ? payload.name : '工具'
        if (!taskId) return

        setMessages(current =>
          updateAssistantEntry(current, runId, entry => ({
            ...entry,
            segments: updateSubagentInSegments(entry.segments, taskId, data.timestampMs, subagent => ({
              ...subagent,
              segments: updateToolInProcessSegment(subagent.segments, name, data.timestampMs, tool => ({
                id: tool?.id ?? newId('tool'),
                name,
                state: 'output-available',
                input: tool?.input,
                output: normalizeToolValue(payload.content),
                errorText: undefined,
                approval: undefined,
              })),
            })),
          })),
        )
        return
      }

      if (data.type === 'task.tool.call.rejected') {
        const taskId = typeof payload.taskId === 'string' ? payload.taskId : ''
        const name = typeof payload.name === 'string' ? payload.name : '工具'
        const reason = typeof payload.reason === 'string' ? payload.reason : '工具执行被拒绝'
        if (!taskId) return

        setMessages(current =>
          updateAssistantEntry(current, runId, entry => ({
            ...entry,
            segments: updateSubagentInSegments(entry.segments, taskId, data.timestampMs, subagent => ({
              ...subagent,
              segments: updateToolInProcessSegment(subagent.segments, name, data.timestampMs, tool => ({
                id: tool?.id ?? newId('tool'),
                name,
                state: 'output-denied',
                input: tool?.input,
                output: undefined,
                errorText: reason,
                approval: undefined,
              })),
            })),
          })),
        )
        return
      }

      if (data.type === 'task.completed' || data.type === 'task.failed' || data.type === 'task.killed') {
        const taskId = typeof payload.taskId === 'string' ? payload.taskId : ''
        if (!taskId) return

        const finalContent =
          typeof payload.content === 'string'
            ? payload.content
            : typeof payload.error === 'string'
              ? payload.error
              : typeof payload.reason === 'string'
                ? payload.reason
                : ''
        const status: SubagentStatus =
          data.type === 'task.completed'
            ? 'completed'
            : data.type === 'task.killed'
              ? 'killed'
              : 'failed'

        setMessages(current =>
          updateAssistantEntry(current, runId, entry => ({
            ...entry,
            segments: updateSubagentInSegments(entry.segments, taskId, data.timestampMs, subagent => ({
              ...subagent,
              status,
              endedAtMs: data.timestampMs,
              segments:
                finalContent && !subagent.segments.some(segment => segment.type === 'text' && segment.content === finalContent)
                  ? completeTextSegment(
                      status === 'failed'
                        ? failOpenProcessSegments(subagent.segments, data.timestampMs)
                        : subagent.segments,
                      finalContent,
                      data.timestampMs,
                    )
                  : closeRunningProcessSegments(subagent.segments, data.timestampMs),
              totalDurationMs:
                typeof payload.totalDurationMs === 'number'
                  ? payload.totalDurationMs
                  : subagent.totalDurationMs,
              totalToolUseCount:
                typeof payload.totalToolUseCount === 'number'
                  ? payload.totalToolUseCount
                  : subagent.totalToolUseCount,
            })),
          })),
        )
        return
      }

      if (data.type === 'run.completed') {
        const content = typeof payload.content === 'string' ? payload.content : ''
        flushTokenBuffer()
        setPanelState('ready')
        setError(null)
        setMessages(current =>
          updateAssistantEntry(current, runId, entry => ({
            ...entry,
            streaming: false,
            final: true,
            segments:
              content && !(entry.segments ?? []).some(segment => segment.type === 'text' && segment.content === content)
                ? completeTextSegment(entry.segments, content, data.timestampMs)
                : closeRunningProcessSegments(entry.segments, data.timestampMs),
          })),
        )
        return
      }

      if (data.type === 'run.failed') {
        const content = typeof payload.content === 'string' ? payload.content : '执行失败'
        flushTokenBuffer()
        setPanelState('error')
        setError(content)
        setMessages(current =>
          updateAssistantEntry(current, runId, entry => ({
            ...entry,
            streaming: false,
            final: true,
            segments: [
              ...failOpenProcessSegments(entry.segments, data.timestampMs),
              {
                id: newId('text'),
                type: 'text',
                content,
                streaming: false,
              },
            ],
          })),
        )
      }
    }

    void (async () => {
      try {
        setPanelState('connecting')
        await ensureAgentService()
        if (closed) return

        const data = await agentApi.createSession()
        if (closed) return

        setSessionId(data.sessionId)
        setWorkingDir(data.workingDir)
        setPermissionMode(normalizePermissionMode(data.permissionMode))
        source = new EventSource(agentEventUrl(data.sessionId))

        source.onopen = () => {
          if (closed) return

          if (reconnectTimer !== null) {
            window.clearTimeout(reconnectTimer)
            reconnectTimer = null
          }
          if (connectionErrorVisible) {
            connectionErrorVisible = false
            setPanelState(current => (current === 'running' ? current : 'ready'))
            setError(current => (current === AGENT_EVENT_CONNECTION_ERROR ? null : current))
          } else {
            setPanelState(current => (current === 'connecting' ? 'ready' : current))
          }
        }

        source.onerror = () => {
          if (closed || reconnectTimer !== null) return

          reconnectTimer = window.setTimeout(() => {
            reconnectTimer = null
            if (closed || source?.readyState === EventSource.OPEN) return

            connectionErrorVisible = true
            setPanelState(current => (current === 'running' ? current : 'error'))
            setError(AGENT_EVENT_CONNECTION_ERROR)
          }, AGENT_EVENT_RECONNECT_GRACE_MS)
        }

        eventTypes.forEach(type => source?.addEventListener(type, handleAgentEvent as EventListener))
      } catch (cause) {
        if (closed) return
        setPanelState('error')
        setError(cause instanceof Error ? cause.message : '无法创建 AI 会话。')
      }
    })()

    return () => {
      closed = true
      if (reconnectTimer !== null) {
        window.clearTimeout(reconnectTimer)
      }
      if (tokenFlushTimerRef.current !== null) {
        window.clearTimeout(tokenFlushTimerRef.current)
        tokenFlushTimerRef.current = null
      }
      if (copyResetTimerRef.current !== null) {
        window.clearTimeout(copyResetTimerRef.current)
        copyResetTimerRef.current = null
      }
      tokenBufferRef.current.clear()
      source?.close()
    }
  }, [])

  const copyMessage = useCallback(async (entry: ChatEntry) => {
    const text = buildChatEntryCopyText(entry)
    if (!text) return

    await copyTextToClipboard(text)
    setCopiedMessageId(entry.id)

    if (copyResetTimerRef.current !== null) {
      window.clearTimeout(copyResetTimerRef.current)
    }
    copyResetTimerRef.current = window.setTimeout(() => {
      setCopiedMessageId(current => current === entry.id ? null : current)
      copyResetTimerRef.current = null
    }, 1500)
  }, [])

  const submitPrompt = useCallback(async (text: string) => {
    if (!sessionId) {
      throw new Error('AI 会话尚未就绪。')
    }

    const nextText = text.trim()
    if (!nextText) return

    setPanelState('running')
    setError(null)
    setMessages(current => [
      ...current,
      {
        id: newId('user'),
        role: 'user',
        content: nextText,
      },
    ])

    await agentApi.createRun(sessionId, nextText, runMode)
  }, [runMode, sessionId])

  const handleSubmit = useCallback(async ({ text }: { text: string }) => {
    const nextText = text.trim()
    if (!nextText) return

    setDraft('')

    try {
      await submitPrompt(nextText)
    } catch (cause) {
      setDraft(nextText)
      setPanelState('error')
      setError(cause instanceof Error ? cause.message : '消息发送失败。')
      throw cause
    }
  }, [submitPrompt])

  const resolveApproval = useCallback(async (approvalId: string, decision: ApprovalDecision) => {
    if (!sessionId) return

    setResolvingApprovals(current => (current.includes(approvalId) ? current : [...current, approvalId]))

    try {
      await agentApi.decideApproval(sessionId, approvalId, decision)
    } catch (cause) {
      setResolvingApprovals(current => current.filter(item => item !== approvalId))
      setError(cause instanceof Error ? cause.message : '处理授权失败。')
    }
  }, [sessionId])

  const workspaceOptions = useMemo(
    () => Array.from(new Set([workspaceRoot, workingDir].filter((item): item is string => Boolean(item)))),
    [workspaceRoot, workingDir],
  )

  const updateSessionConfig = useCallback(async (next: {
    workingDir?: string
    permissionMode?: PermissionModeValue
  }) => {
    if (!sessionId) return

    const nextWorkingDir = next.workingDir ?? workingDir
    const nextPermissionMode = next.permissionMode ?? permissionMode

    setWorkingDir(nextWorkingDir)
    setPermissionMode(nextPermissionMode)

    try {
      const data = await agentApi.updateSessionSettings(
        sessionId,
        {
          workingDir: nextWorkingDir,
          permissionMode: nextPermissionMode,
        },
      )
      setWorkingDir(data.workingDir)
      setPermissionMode(normalizePermissionMode(data.permissionMode))
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '更新会话配置失败。')
    }
  }, [permissionMode, sessionId, workingDir])

  const canSubmit = Boolean(sessionId && draft.trim()) && panelState !== 'connecting'
  const isEmptyConversation = messages.length === 0
  const currentTodo = useMemo(() => latestTodoSegment(messages), [messages])

  const allSubagents = useMemo(() => {
    const result: SubagentEntry[] = []
    for (const msg of messages) {
      if (!msg.segments) continue
      for (const seg of msg.segments) {
        if (seg.type === 'subagent') {
          result.push(seg.subagent)
        }
      }
    }
    return result
  }, [messages])

  return (
    <div
      className="relative flex min-h-0 flex-col bg-white"
      style={{ width: width ?? '100%', minWidth: 0, flex: width ? '0 0 auto' : 1 }}
    >
      <AgentContextConsole
        contextTurns={llmContextTurns}
        onOpenChange={setContextOpen}
        open={contextOpen}
      />

      <Conversation className="min-h-0 flex-1 bg-white">
        <ConversationContent className="relative mx-auto flex w-full max-w-4xl flex-col gap-6 px-5 py-6">
          {isEmptyConversation && error ? (
            <Alert className="rounded-md" variant="destructive">
              <XCircle />
              <AlertTitle>无法连接本地智能体</AlertTitle>
              <AlertDescription>{error}</AlertDescription>
            </Alert>
          ) : null}

          {isEmptyConversation ? (
            <EmptyAssistantPrompt
              controls={(
                <>
                  <RunModeControl
                    disabled={!sessionId || panelState === 'connecting' || panelState === 'running'}
                    mode={runMode}
                    onModeChange={setRunMode}
                  />
                  <SessionControls
                    disabled={!sessionId || runMode === 'chat' || panelState === 'connecting' || panelState === 'running'}
                    onPermissionModeChange={mode => void updateSessionConfig({ permissionMode: mode })}
                    onWorkspaceChange={path => void updateSessionConfig({ workingDir: path })}
                    permissionMode={permissionMode}
                    workspaceOptions={workspaceOptions}
                    workingDir={workingDir}
                  />
                </>
              )}
              disabled={!sessionId || panelState === 'connecting'}
              onSubmit={submitPrompt}
            />
          ) : null}

          {messages.map(entry => (
            <Message className={entry.role === 'assistant' ? 'max-w-full' : undefined} from={entry.role} key={entry.id}>
              <MessageContent className={entry.role === 'assistant' ? 'w-full max-w-[880px]' : undefined}>
                {entry.role === 'assistant' ? (
                  <div className="space-y-4">
                    <AssistantSegmentsView
                      resolveApproval={resolveApproval}
                      resolvingApprovals={resolvingApprovals}
                      segments={entry.segments ?? []}
                    />

                    {entry.streaming && (entry.segments ?? []).length === 0 ? (
                      <TextShimmer as="div" className="text-sm">
                        思考中...
                      </TextShimmer>
                    ) : null}
                  </div>
                ) : (
                  <div className="whitespace-pre-wrap break-words">{entry.content}</div>
                )}
              </MessageContent>
              {shouldShowChatEntryCopyAction(entry) ? (
                <MessageActions
                  className={cn(
                    'opacity-70 transition-opacity group-hover:opacity-100 group-focus-within:opacity-100',
                    entry.role === 'user' ? 'ml-auto justify-end pr-1' : 'justify-start',
                  )}
                >
                  <ChatMessageCopyAction
                    copied={copiedMessageId === entry.id}
                    onCopy={() => void copyMessage(entry)}
                  />
                </MessageActions>
              ) : null}
            </Message>
          ))}
        </ConversationContent>
        <ConversationScrollButton />
        <SubagentStack
          onSelect={(agent) => setDetailSubagent(agent)}
          subagents={allSubagents}
        />
      </Conversation>

      <Dialog
        onOpenChange={(open) => { if (!open) setDetailSubagent(null) }}
        open={detailSubagent !== null}
      >
        <DialogContent className="max-h-[80vh] max-w-2xl overflow-y-auto">
          <DialogTitle>{detailSubagent?.name ?? '子 Agent'}</DialogTitle>
          {detailSubagent ? <SubagentExecutionFlow subagent={detailSubagent} /> : null}
        </DialogContent>
      </Dialog>

      <div className={cn('bg-white px-4 py-4', isEmptyConversation && 'hidden')}>
        <div className="mx-auto w-full max-w-4xl">
          {currentTodo ? (
            <div className="mb-3 flex justify-center">
              <TodoQueuePanel segment={currentTodo} />
            </div>
          ) : null}

          {error ? <div className="mb-3 text-xs text-destructive">{error}</div> : null}

          <PromptInput className="w-full" onSubmit={handleSubmit}>
            <PromptInputBody>
              <PromptInputTextarea
                disabled={!sessionId}
                onChange={event => setDraft(event.currentTarget.value)}
                placeholder={sessionId ? '输入消息并发送给助手' : '正在连接本地智能体...'}
                value={draft}
              />
            </PromptInputBody>
            <PromptInputFooter>
              <RunModeControl
                disabled={!sessionId || panelState === 'connecting' || panelState === 'running'}
                mode={runMode}
                onModeChange={setRunMode}
              />
              <SessionControls
                disabled={!sessionId || runMode === 'chat' || panelState === 'connecting' || panelState === 'running'}
                onPermissionModeChange={mode => void updateSessionConfig({ permissionMode: mode })}
                onWorkspaceChange={path => void updateSessionConfig({ workingDir: path })}
                permissionMode={permissionMode}
                workspaceOptions={workspaceOptions}
                workingDir={workingDir}
              />
              {contextWarning ? (
                <Context
                  maxTokens={contextWarning.maxContextTokens}
                  modelId="deepseek-v4-flash"
                  usedTokens={contextWarning.tokenCount}
                >
                  <ContextTrigger variant="ghost" />
                  <ContextContent>
                    <ContextContentHeader />
                  </ContextContent>
                </Context>
              ) : null}
              <PromptInputSubmit
                disabled={!canSubmit}
                status={panelState === 'running' ? 'submitted' : panelState === 'error' ? 'error' : undefined}
              />
            </PromptInputFooter>
          </PromptInput>
        </div>
      </div>
    </div>
  )
}

export default memo(ChatPanel)
