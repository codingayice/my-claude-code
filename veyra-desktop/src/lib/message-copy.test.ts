import test from 'node:test'
import assert from 'node:assert/strict'

import { buildChatEntryCopyText, shouldShowChatEntryCopyAction, type CopyableChatEntry } from './message-copy.ts'

test('copies the user message content', () => {
  const entry: CopyableChatEntry = {
    role: 'user',
    content: '探索项目结构',
  }

  assert.equal(buildChatEntryCopyText(entry), '探索项目结构')
})

test('copies readable assistant segments', () => {
  const entry: CopyableChatEntry = {
    role: 'assistant',
    segments: [
      { type: 'reasoning', content: '先看目录' },
      {
        type: 'process',
        tools: [
          {
            name: 'Glob',
            input: { pattern: '**/*.java' },
            output: ['src/Main.java'],
          },
        ],
      },
      { type: 'text', content: '核心模块是 agent 和 context。' },
      {
        type: 'subagent',
        subagent: {
          name: 'reviewer',
          segments: [
            { type: 'text', content: '子任务完成。' },
          ],
        },
      },
      { type: 'todo', items: [{ content: 'ignored' }] },
    ],
  }

  assert.equal(
    buildChatEntryCopyText(entry),
    [
      '先看目录',
      'Glob',
      'input:\n{\n  "pattern": "**/*.java"\n}',
      'output:\n[\n  "src/Main.java"\n]',
      '核心模块是 agent 和 context。',
      'reviewer',
      '子任务完成。',
    ].join('\n\n'),
  )
})

test('shows assistant copy action only after the final run result', () => {
  const inProgressAssistant: CopyableChatEntry = {
    role: 'assistant',
    final: false,
    segments: [{ type: 'text', content: '中间回复' }],
  }
  const finalAssistant: CopyableChatEntry = {
    role: 'assistant',
    final: true,
    segments: [{ type: 'text', content: '最终回复' }],
  }
  const user: CopyableChatEntry = {
    role: 'user',
    content: '用户消息',
  }

  assert.equal(shouldShowChatEntryCopyAction(inProgressAssistant), false)
  assert.equal(shouldShowChatEntryCopyAction(finalAssistant), true)
  assert.equal(shouldShowChatEntryCopyAction(user), true)
})
