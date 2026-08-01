import JSZip from "jszip"

type WordText = {
  type: "text"
  text: string
}

type WordParagraph = {
  type: "paragraph"
  children: WordText[]
}

export type OoJsonWord = {
  kind: "word"
  version: "1.0"
  body: WordParagraph[]
}

type ValidationIssue = {
  path: string
  message: string
}

type WordInput = Partial<OoJsonWord> & {
  body?: Array<Partial<WordParagraph> & {
    children?: Array<Partial<WordText>>
  }>
}

type FromBufferOptions = {
  parseHeadersFooters?: boolean
  parseNotes?: boolean
}

function normalizeText(value: unknown): string {
  return typeof value === "string" ? value : ""
}

function normalizeParagraph(block: Partial<WordParagraph> | undefined): WordParagraph {
  const children = Array.isArray(block?.children) ? block.children : []
  const normalizedChildren = children.flatMap((child) => child?.type === "text" ? [{
      type: "text" as const,
      text: normalizeText(child.text),
    }] : [])

  return {
    type: "paragraph",
    children: normalizedChildren.length > 0
      ? normalizedChildren
      : [{ type: "text", text: "" }],
  }
}

function normalize(input: WordInput): OoJsonWord {
  const body = Array.isArray(input.body) ? input.body : []

  return {
    kind: "word",
    version: "1.0",
    body: body.length > 0
      ? body.map((block) => normalizeParagraph(block))
      : [
          {
            type: "paragraph",
            children: [{ type: "text", text: "" }],
          },
        ],
  }
}

function validate(document: unknown): { valid: boolean; errors: ValidationIssue[] } {
  const errors: ValidationIssue[] = []
  const candidate = document as Partial<OoJsonWord>

  if (!candidate || typeof candidate !== "object") {
    errors.push({ path: "document", message: "Document must be an object" })
  }

  if (candidate.kind !== "word") {
    errors.push({ path: "kind", message: "Document kind must be word" })
  }

  if (!Array.isArray(candidate.body)) {
    errors.push({ path: "body", message: "Document body must be an array" })
  } else {
    candidate.body.forEach((block, blockIndex) => {
      if (block.type !== "paragraph") {
        errors.push({
          path: `body.${blockIndex}.type`,
          message: "Only paragraph blocks are supported",
        })
      }

      if (!Array.isArray(block.children)) {
        errors.push({
          path: `body.${blockIndex}.children`,
          message: "Paragraph children must be an array",
        })
        return
      }

      block.children.forEach((child, childIndex) => {
        if (child.type !== "text") {
          errors.push({
            path: `body.${blockIndex}.children.${childIndex}.type`,
            message: "Only text children are supported",
          })
        }

        if (typeof child.text !== "string") {
          errors.push({
            path: `body.${blockIndex}.children.${childIndex}.text`,
            message: "Text content must be a string",
          })
        }
      })
    })
  }

  return { valid: errors.length === 0, errors }
}

function escapeXml(value: string): string {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&apos;")
}

function unescapeXml(value: string): string {
  return value
    .replaceAll("&apos;", "'")
    .replaceAll("&quot;", '"')
    .replaceAll("&gt;", ">")
    .replaceAll("&lt;", "<")
    .replaceAll("&amp;", "&")
}

function documentXml(document: OoJsonWord): string {
  const paragraphs = document.body
    .map((paragraph) => {
      const runs = paragraph.children
        .map((child) => `<w:r><w:t xml:space="preserve">${escapeXml(child.text)}</w:t></w:r>`)
        .join("")
      return `<w:p>${runs}</w:p>`
    })
    .join("")

  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:body>
    ${paragraphs}
    <w:sectPr>
      <w:pgSz w:w="12240" w:h="15840"/>
      <w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="720" w:footer="720" w:gutter="0"/>
    </w:sectPr>
  </w:body>
</w:document>`
}

async function toBuffer(input: OoJsonWord): Promise<ArrayBuffer> {
  const document = normalize(input)
  const validation = validate(document)

  if (!validation.valid) {
    throw new Error(
      validation.errors.map((issue) => `${issue.path}: ${issue.message}`).join("\n")
    )
  }

  const zip = new JSZip()
  zip.file(
    "[Content_Types].xml",
    `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>`
  )
  zip.folder("_rels")?.file(
    ".rels",
    `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>`
  )
  zip.folder("word")?.file("document.xml", documentXml(document))

  return zip.generateAsync({ type: "arraybuffer" })
}

async function fromBuffer(
  buffer: ArrayBuffer | Uint8Array,
  options: FromBufferOptions = {}
): Promise<OoJsonWord> {
  void options

  const zip = await JSZip.loadAsync(buffer)
  const xml = await zip.file("word/document.xml")?.async("string")

  if (!xml) {
    return normalize({})
  }

  const paragraphs = [...xml.matchAll(/<w:p\b[^>]*>([\s\S]*?)<\/w:p>/g)]
    .map((paragraphMatch) => {
      const text = [...paragraphMatch[1].matchAll(/<w:t\b[^>]*>([\s\S]*?)<\/w:t>/g)]
        .map((textMatch) => unescapeXml(textMatch[1]))
        .join("")

      return {
        type: "paragraph" as const,
        children: [{ type: "text" as const, text }],
      }
    })
    .filter((paragraph) => paragraph.children[0].text.length > 0)

  return normalize({ body: paragraphs })
}

export const word = {
  fromBuffer,
  normalize,
  toBuffer,
  validate,
}
