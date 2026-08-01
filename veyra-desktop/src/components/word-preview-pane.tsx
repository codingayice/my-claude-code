import {
  forwardRef,
  memo,
  useCallback,
  useEffect,
  useImperativeHandle,
  useMemo,
  useRef,
  useState,
  type ForwardedRef,
} from "react"
import { renderAsync } from "docx-preview"
import { FileWarning, Loader2 } from "lucide-react"
import { word, type OoJsonWord } from "@/lib/oojson-word"

type WordPreviewPaneProps = {
  document: OoJsonWord
  documentName: string
}

export type WordPreviewPaneHandle = {
  save: () => Promise<ArrayBuffer | null>
}

type PreviewState =
  | { status: "idle" | "rendering"; error: null }
  | { status: "error"; error: string }

function WordPreviewPaneInner(
  { document, documentName }: WordPreviewPaneProps,
  ref: ForwardedRef<WordPreviewPaneHandle>
) {
  const containerRef = useRef<HTMLDivElement>(null)
  const saveInProgressRef = useRef(false)
  const renderSeqRef = useRef(0)
  const [previewState, setPreviewState] = useState<PreviewState>({
    status: "idle",
    error: null,
  })

  const title = useMemo(() => documentName || "未命名.docx", [documentName])

  const buildBuffer = useCallback(async () => {
    const normalized = word.normalize(document)
    const validation = word.validate(normalized)

    if (!validation.valid) {
      throw new Error(
        validation.errors
          .map((issue) => `${issue.path}: ${issue.message}`)
          .join("\n")
      )
    }

    return word.toBuffer(normalized)
  }, [document])

  const handleSave = useCallback(async () => {
    if (saveInProgressRef.current) {
      return null
    }

    saveInProgressRef.current = true

    try {
      return await buildBuffer()
    } finally {
      saveInProgressRef.current = false
    }
  }, [buildBuffer])

  useImperativeHandle(ref, () => ({
    save: handleSave,
  }), [handleSave])

  useEffect(() => {
    const seq = ++renderSeqRef.current
    const container = containerRef.current

    if (!container) {
      return
    }

    async function renderPreview() {
      setPreviewState({ status: "rendering", error: null })

      try {
        const buffer = await buildBuffer()
        const currentContainer = containerRef.current
        if (seq !== renderSeqRef.current || !currentContainer) {
          return
        }

        currentContainer.innerHTML = ""
        await renderAsync(
          new Blob([buffer], {
            type: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
          }),
          currentContainer,
          undefined,
          {
            ignoreFonts: false,
            inWrapper: true,
            renderFooters: true,
            renderHeaders: true,
          }
        )

        if (seq === renderSeqRef.current) {
          setPreviewState({ status: "idle", error: null })
        }
      } catch (error) {
        if (seq !== renderSeqRef.current) {
          return
        }

        const currentContainer = containerRef.current
        if (currentContainer) {
          currentContainer.innerHTML = ""
        }
        setPreviewState({
          status: "error",
          error: error instanceof Error ? error.message : String(error),
        })
      }
    }

    void renderPreview()

    return () => {
      if (seq === renderSeqRef.current) {
        renderSeqRef.current += 1
      }
    }
  }, [buildBuffer])

  return (
    <div className="word-preview-pane" aria-label={`${title} 预览`}>
      {previewState.status === "rendering" ? (
        <div className="word-preview-status" role="status">
          <Loader2 className="spin size-4" />
          <span>正在生成预览</span>
        </div>
      ) : null}

      {previewState.status === "error" ? (
        <div className="word-preview-error" role="alert">
          <FileWarning className="size-5" />
          <div>
            <div className="font-medium">无法预览当前文档</div>
            <pre>{previewState.error}</pre>
          </div>
        </div>
      ) : null}

      <div className="word-preview-surface">
        <div ref={containerRef} className="word-preview-container" />
      </div>
    </div>
  )
}

export const WordPreviewPane = memo(forwardRef(WordPreviewPaneInner))
