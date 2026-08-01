import test from "node:test"
import assert from "node:assert/strict"

import { word } from "./oojson-word.ts"

test("generates a docx buffer for a normalized word document", async () => {
  const document = word.normalize({
    kind: "word",
    version: "1.0",
    body: [
      {
        type: "paragraph",
        children: [{ type: "text", text: "Hello Veyra" }],
      },
    ],
  })

  const validation = word.validate(document)
  const buffer = await word.toBuffer(document)
  const bytes = new Uint8Array(buffer)

  assert.equal(validation.valid, true)
  assert.equal(bytes[0], 0x50)
  assert.equal(bytes[1], 0x4b)
  assert.ok(buffer.byteLength > 500)
})
