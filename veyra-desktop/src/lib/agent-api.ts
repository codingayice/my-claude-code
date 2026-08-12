export const AGENT_API_BASE = "http://127.0.0.1:17361/v1"

export type AgentRunMode = "chat" | "agent"
export type AgentPermissionMode =
  "ask_every_time" | "project_auto" | "auto_approve"
export type AgentApprovalDecision = "allow_once" | "allow_for_session" | "deny"

export type AgentSessionResponse = {
  sessionId: string
  workingDir: string
  permissionMode: AgentPermissionMode
  runMode: AgentRunMode
  lastRunStatus?: string | null
  revision: number
  currentRunId?: string | null
  activeRunId?: string | null
  runs: Record<string, AgentRunNode>
  agent: AgentStateView
}

export type AgentRunNode = {
  runId: string
  parentRunId?: string | null
  startedRevision: number
  terminalRevision?: number | null
  status: string
  snapshotAvailable: boolean
}

export type AgentToolCallState = {
  toolUseId: string
  name: string
  arguments: string
  phase: string
  approvalId?: string | null
  outcome?: string | null
  resultContent: string
  recoveryPolicy: string
}

export type AgentMessageState = {
  messageId: string
  sourceRevision: number
  runId?: string | null
  role: "USER" | "ASSISTANT" | "TOOL"
  text: string
  thinking: string
  visible: boolean
  toolCalls: AgentToolCallState[]
}

export type AgentStateView = {
  run?: { runId: string; phase: string; finalResponse: string } | null
  messages: AgentMessageState[]
  toolCalls: Record<string, AgentToolCallState>
  approvals: Record<string, Record<string, unknown>>
  pendingInputs: Array<Record<string, unknown>>
  todos: Array<Record<string, unknown>>
  tasks: Record<string, Record<string, unknown>>
  contextSummary: Record<string, unknown>
}

export type AgentSessionRecord = {
  sessionId: string
  title: string
  createdAt: string
  updatedAt: string
  journalPath: string
}

export type AgentTranscriptEntry = {
  id: string
  sessionId: string
  role: string
  content: string
  toolUseId?: string | null
  toolName?: string | null
  timestamp: string
}

export type AgentStableEvent = {
  seq: number
  sessionId: string
  runId?: string | null
  type: string
  timestampMs: number
  payload: Record<string, unknown>
}

export type AgentRunResponse = {
  runId: string
  accepted: boolean
}

export type AgentCheckpoint = {
  runId: string
  parentRunId?: string | null
  terminalRevision: number
  status: string
  current: boolean
  restorable: boolean
}

export type AgentFollowupResponse = {
  messageId: string
  accepted: boolean
  steerable: boolean
}

type ApiResponse<T> = {
  success: boolean
  code: string
  message: string
  data: T
}

export type AgentFetch = (
  input: RequestInfo | URL,
  init?: RequestInit
) => Promise<Response>

function isApiResponse(value: unknown): value is ApiResponse<unknown> {
  if (!value || typeof value !== "object") return false

  const candidate = value as Record<string, unknown>
  return (
    typeof candidate.success === "boolean" &&
    typeof candidate.code === "string" &&
    typeof candidate.message === "string" &&
    "data" in candidate
  )
}

async function requestJson<T>(
  fetcher: AgentFetch,
  path: string,
  init?: RequestInit
): Promise<T> {
  const response = await fetcher(`${AGENT_API_BASE}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(init?.headers ?? {}),
    },
  })

  let payload: unknown
  try {
    payload = await response.json()
  } catch (cause) {
    throw new Error(`智能体服务返回了无效响应（HTTP ${response.status}）。`, {
      cause,
    })
  }

  if (!isApiResponse(payload)) {
    throw new Error(`智能体服务响应格式无效（HTTP ${response.status}）。`)
  }

  if (!response.ok || !payload.success) {
    throw new Error(payload.message || `请求失败：${response.status}`)
  }

  return payload.data as T
}

function encodePathSegment(value: string) {
  return encodeURIComponent(value)
}

export function agentEventUrl(sessionId: string) {
  return `${AGENT_API_BASE}/sessions/${encodePathSegment(sessionId)}/events`
}

export function createAgentApiClient(fetcher: AgentFetch = globalThis.fetch) {
  return {
    health: () => requestJson<{ ok: boolean }>(fetcher, "/health"),

    createSession: () =>
      requestJson<AgentSessionResponse>(fetcher, "/sessions", {
        method: "POST",
      }),

    listSessions: () =>
      requestJson<{ items: AgentSessionRecord[] }>(fetcher, "/sessions"),

    deleteSession: (sessionId: string) =>
      requestJson<{ ok: boolean }>(
        fetcher,
        `/sessions/${encodePathSegment(sessionId)}`,
        { method: "DELETE" }
      ),

    session: (sessionId: string) =>
      requestJson<AgentSessionResponse>(
        fetcher,
        `/sessions/${encodePathSegment(sessionId)}`
      ),

    transcript: (sessionId: string) =>
      requestJson<{ items: AgentTranscriptEntry[] }>(
        fetcher,
        `/sessions/${encodePathSegment(sessionId)}/transcript`
        ),

    createRun: (
      sessionId: string,
      input: string,
      mode: AgentRunMode,
      parentRunId?: string
    ) =>
      requestJson<AgentRunResponse>(
        fetcher,
        `/sessions/${encodePathSegment(sessionId)}/runs`,
        {
          method: "POST",
          body: JSON.stringify({ input, mode, parentRunId }),
        }
      ),

    checkpoints: (sessionId: string) =>
      requestJson<{ items: AgentCheckpoint[] }>(
        fetcher,
        `/sessions/${encodePathSegment(sessionId)}/checkpoints`
      ),

    restoreCheckpoint: (
      sessionId: string,
      runId: string,
      expectedRevision: number
    ) =>
      requestJson<AgentSessionResponse>(
        fetcher,
        `/sessions/${encodePathSegment(sessionId)}/checkpoint-restorations`,
        {
          method: "POST",
          body: JSON.stringify({ runId, expectedRevision }),
        }
      ),

    createFollowup: (sessionId: string, input: string, mode: AgentRunMode) =>
      requestJson<AgentFollowupResponse>(
        fetcher,
        `/sessions/${encodePathSegment(sessionId)}/followups`,
        {
          method: "POST",
          body: JSON.stringify({ input, mode }),
        }
      ),

    steerFollowup: (sessionId: string, messageId: string) =>
      requestJson<{ ok: boolean }>(
        fetcher,
        `/sessions/${encodePathSegment(sessionId)}/followups/${encodePathSegment(messageId)}/steer`,
        { method: "POST" }
      ),

    cancelFollowup: (sessionId: string, messageId: string) =>
      requestJson<{ ok: boolean }>(
        fetcher,
        `/sessions/${encodePathSegment(sessionId)}/followups/${encodePathSegment(messageId)}`,
        { method: "DELETE" }
      ),

    decideApproval: (
      sessionId: string,
      approvalId: string,
      decision: AgentApprovalDecision
    ) =>
      requestJson<{ ok: boolean }>(
        fetcher,
        `/sessions/${encodePathSegment(sessionId)}/approvals/${encodePathSegment(approvalId)}/decision`,
        {
          method: "POST",
          body: JSON.stringify({ decision }),
        }
      ),

    updateSessionSettings: (
      sessionId: string,
      settings: {
        workingDir: string
        permissionMode: AgentPermissionMode
        runMode: AgentRunMode
        expectedRevision?: number
      }
    ) =>
      requestJson<AgentSessionResponse>(
        fetcher,
        `/sessions/${encodePathSegment(sessionId)}/settings`,
        {
          method: "PATCH",
          body: JSON.stringify(settings),
        }
      ),
  }
}

export const agentApi = createAgentApiClient()
