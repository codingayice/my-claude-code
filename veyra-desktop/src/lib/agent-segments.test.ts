import test from 'node:test'
import assert from 'node:assert/strict'

import {
  isActiveStreamingSegment,
  shouldCloseProcessOnAssistantMessage,
} from './agent-segments.ts'

test('closes the preceding process block when the next assistant message contains tool requests', () => {
  assert.equal(shouldCloseProcessOnAssistantMessage(true), true)
})

test('closes the process block when the assistant message has no tool requests', () => {
  assert.equal(shouldCloseProcessOnAssistantMessage(false), true)
})

test('continues only the active model response segment', () => {
  assert.equal(isActiveStreamingSegment({ streaming: true }), true)
  assert.equal(isActiveStreamingSegment({ streaming: false }), false)
  assert.equal(isActiveStreamingSegment({}), false)
})
