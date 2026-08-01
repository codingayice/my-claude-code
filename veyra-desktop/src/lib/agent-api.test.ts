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
    }),
    apiResponse({ runId: "run-1", accepted: true }, 202),
    apiResponse({ ok: true }),
    apiResponse({
      sessionId: "session/1",
      workingDir: "D:\\next",
      permissionMode: "project_auto",
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
  assert.deepEqual(await client.createRun("session/1", "hello", "agent"), {
    runId: "run-1",
    accepted: true,
  })
  await client.decideApproval("session/1", "approval/1", "allow_once")
  assert.equal(
    (
      await client.updateSessionSettings("session/1", {
        workingDir: "D:\\next",
        permissionMode: "project_auto",
      })
    ).workingDir,
    "D:\\next"
  )

  assert.equal(requests[0].url, `${AGENT_API_BASE}/health`)
  assert.equal(requests[1].url, `${AGENT_API_BASE}/sessions`)
  assert.equal(requests[1].init?.method, "POST")
  assert.equal(requests[2].url, `${AGENT_API_BASE}/sessions/session%2F1/runs`)
  assert.equal(
    requests[2].init?.body,
    JSON.stringify({ input: "hello", mode: "agent" })
  )
  assert.equal(
    requests[3].url,
    `${AGENT_API_BASE}/sessions/session%2F1/approvals/approval%2F1/decision`
  )
  assert.equal(
    requests[3].init?.body,
    JSON.stringify({ decision: "allow_once" })
  )
  assert.equal(
    requests[4].url,
    `${AGENT_API_BASE}/sessions/session%2F1/settings`
  )
  assert.equal(
    requests[4].init?.body,
    JSON.stringify({
      workingDir: "D:\\next",
      permissionMode: "project_auto",
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
