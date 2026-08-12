import test from "node:test"
import assert from "node:assert/strict"

import {
  AGENT_API_BASE,
  agentEventUrl,
  createAgentApiClient,
  type AgentFetch,
} from "./agent-api.ts"

type RecordedRequest = {
  url: string
  init?: RequestInit
}

function apiResponse<T>(data: T, status = 200) {
  return new Response(
    JSON.stringify({
      success: true,
      code: "00000",
      message: "success",
      data,
    }),
    {
      status,
      headers: { "Content-Type": "application/json" },
    }
  )
}

test("uses the Veyra API contract and unwraps unified responses", async () => {
  const requests: RecordedRequest[] = []
  const responses = [
    apiResponse({ ok: true }),
    apiResponse({
      sessionId: "session/1",
      workingDir: "D:\\workspace",
      permissionMode: "ask_every_time",
      runMode: "chat",
    }),
    apiResponse({ items: [{
      sessionId: "session/1",
      title: "First session",
      createdAt: "2026-08-08T00:00:00Z",
      updatedAt: "2026-08-08T01:00:00Z",
      journalPath: "D:\\sessions\\session-1.journal.jsonl",
    }] }),
    apiResponse({
      sessionId: "session/1",
      workingDir: "D:\\workspace",
      permissionMode: "ask_every_time",
      runMode: "agent",
      lastRunStatus: "completed",
    }),
    apiResponse({ items: [{
      id: "1",
      sessionId: "session/1",
      role: "user",
      content: "hello",
      timestamp: "2026-08-08T00:00:00Z",
    }] }),
    apiResponse({ runId: "run-1", accepted: true }, 202),
    apiResponse({ ok: true }),
    apiResponse({
      sessionId: "session/1",
      workingDir: "D:\\next",
      permissionMode: "project_auto",
      runMode: "agent",
    }),
  ]
  const fetcher: AgentFetch = async (input, init) => {
    requests.push({ url: String(input), init })
    const response = responses.shift()
    assert.ok(response)
    return response
  }
  const client = createAgentApiClient(fetcher)

  assert.deepEqual(await client.health(), { ok: true })
  assert.equal((await client.createSession()).sessionId, "session/1")
  assert.equal((await client.listSessions()).items[0].title, "First session")
  assert.equal((await client.session("session/1")).lastRunStatus, "completed")
  assert.equal((await client.transcript("session/1")).items[0].content, "hello")
  assert.deepEqual(await client.createRun("session/1", "hello", "agent"), {
    runId: "run-1",
    accepted: true,
  })
  await client.controlRun("session/1", "run/1", "approval/1", "allow_once", 42, "cmd-1")
  assert.equal(
    (
      await client.updateSessionSettings("session/1", {
        workingDir: "D:\\next",
        permissionMode: "project_auto",
        runMode: "agent",
      })
    ).workingDir,
    "D:\\next"
  )

  assert.equal(requests[0].url, `${AGENT_API_BASE}/health`)
  assert.equal(requests[1].url, `${AGENT_API_BASE}/sessions`)
  assert.equal(requests[1].init?.method, "POST")
  assert.equal(requests[2].url, `${AGENT_API_BASE}/sessions`)
  assert.equal(requests[3].url, `${AGENT_API_BASE}/sessions/session%2F1`)
  assert.equal(requests[4].url, `${AGENT_API_BASE}/sessions/session%2F1/transcript`)
  assert.equal(requests[5].url, `${AGENT_API_BASE}/sessions/session%2F1/runs`)
  assert.equal(
    requests[5].init?.body,
    JSON.stringify({ input: "hello", mode: "agent" })
  )
  assert.equal(
    requests[6].url,
    `${AGENT_API_BASE}/sessions/session%2F1/runs/run%2F1/control`
  )
  assert.equal(
    requests[6].init?.body,
    JSON.stringify({
      action: "resume", cause: "approval", input: { approvalId: "approval/1", decision: "allow_once" },
      expectedRevision: 42, commandId: "cmd-1",
    })
  )
  assert.equal(
    requests[7].url,
    `${AGENT_API_BASE}/sessions/session%2F1/settings`
  )
  assert.equal(
    requests[7].init?.body,
    JSON.stringify({
      workingDir: "D:\\next",
      permissionMode: "project_auto",
      runMode: "agent",
    })
  )
  assert.equal(
    agentEventUrl("session/1"),
    `${AGENT_API_BASE}/sessions/session%2F1/events`
  )
})

test("surfaces unified API failures", async () => {
  const client = createAgentApiClient(
    async () =>
      new Response(
        JSON.stringify({
          success: false,
          code: "B0001",
          message: "系统执行失败",
          data: null,
        }),
        {
          status: 500,
          headers: { "Content-Type": "application/json" },
        }
      )
  )

  await assert.rejects(client.health(), /系统执行失败/)
})

test("queues, steers, and cancels followup inputs", async () => {
  const requests: RecordedRequest[] = []
  const responses = [
    apiResponse({ messageId: "message/1", accepted: true, steerable: true }, 202),
    apiResponse({ ok: true }),
    apiResponse({ ok: true }),
  ]
  const client = createAgentApiClient(async (input, init) => {
    requests.push({ url: String(input), init })
    const response = responses.shift()
    assert.ok(response)
    return response
  })

  assert.deepEqual(await client.createFollowup("session/1", "调整方向", "agent"), {
    messageId: "message/1",
    accepted: true,
    steerable: true,
  })
  await client.steerFollowup("session/1", "message/1")
  await client.cancelFollowup("session/1", "message/1")

  assert.equal(requests[0].url, `${AGENT_API_BASE}/sessions/session%2F1/followups`)
  assert.equal(requests[0].init?.body, JSON.stringify({ input: "调整方向", mode: "agent" }))
  assert.equal(
    requests[1].url,
    `${AGENT_API_BASE}/sessions/session%2F1/followups/message%2F1/steer`
  )
  assert.equal(
    requests[2].url,
    `${AGENT_API_BASE}/sessions/session%2F1/followups/message%2F1`
  )
  assert.equal(requests[2].init?.method, "DELETE")
})

test("deletes a persisted session", async () => {
  const requests: RecordedRequest[] = []
  const client = createAgentApiClient(async (input, init) => {
    requests.push({ url: String(input), init })
    return apiResponse({ ok: true })
  })

  await client.deleteSession("session/1")

  assert.equal(requests[0].url, `${AGENT_API_BASE}/sessions/session%2F1`)
  assert.equal(requests[0].init?.method, "DELETE")
})

test("lists and restores run checkpoints with optimistic revision", async () => {
  const requests: RecordedRequest[] = []
  const responses = [
    apiResponse({ items: [{ runId: "run/1", terminalRevision: 7, status: "completed", current: true, restorable: true }] }),
    apiResponse({
      sessionId: "session/1",
      workingDir: "D:\\workspace",
      permissionMode: "ask_every_time",
      runMode: "chat",
      revision: 8,
      currentRunId: "run/1",
      activeRunId: null,
    }),
  ]
  const client = createAgentApiClient(async (input, init) => {
    requests.push({ url: String(input), init })
    return responses.shift()!
  })

  assert.equal((await client.checkpoints("session/1")).items[0].runId, "run/1")
  await client.restoreCheckpoint("session/1", "run/1", 7)

  assert.equal(requests[0].url, `${AGENT_API_BASE}/sessions/session%2F1/checkpoints`)
  assert.equal(requests[1].url, `${AGENT_API_BASE}/sessions/session%2F1/checkpoint-restorations`)
  assert.equal(requests[1].init?.body, JSON.stringify({ runId: "run/1", expectedRevision: 7 }))
})
