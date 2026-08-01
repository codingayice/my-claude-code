import test from 'node:test'
import assert from 'node:assert/strict'

import { applyLlmContextEvent } from './llm-context-view.ts'

test('stores readable messages and attaches llm output by turn index', () => {
  const afterSnapshot = applyLlmContextEvent([], {
    type: 'context.snapshot',
    payload: {
      turnIndex: 1,
      messageCount: 2,
      estimatedTokens: 120,
      messages: [
        { index: 0, role: 'SYSTEM', title: 'SYSTEM', content: 'system prompt' },
        { index: 1, role: 'USER', title: 'USER', content: 'hello' },
      ],
    },
  })

  const afterOutput = applyLlmContextEvent(afterSnapshot, {
    type: 'llm.output',
    payload: {
      turnIndex: 1,
      text: 'I will read the file.',
      hasToolRequests: true,
      toolCalls: [
        { name: 'Read', arguments: '{"file_path":"README.md"}' },
      ],
    },
  })

  assert.equal(afterOutput.length, 1)
  assert.equal(afterOutput[0].turnIndex, 1)
  assert.equal(afterOutput[0].messageCount, 2)
  assert.equal(afterOutput[0].estimatedTokens, 120)
  assert.equal(afterOutput[0].messages[1].content, 'hello')
  assert.equal(afterOutput[0].output?.text, 'I will read the file.')
  assert.equal(afterOutput[0].output?.toolCalls[0].name, 'Read')
})
