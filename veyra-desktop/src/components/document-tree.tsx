import { memo, useCallback, useEffect, useMemo, useState, type ReactNode } from "react"
import {
  ChevronRight,
  FileText,
  FileUp,
  Folder,
  FolderOpen,
  Search,
} from "lucide-react"

import { Button } from "@/components/ui/button"
import {
  Empty,
  EmptyContent,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from "@/components/ui/empty"
import { Input } from "@/components/ui/input"
import { cn } from "@/lib/utils"

export type DocFile = {
  kind: "file"
  id: string
  path: string
  name: string
  type: string
  createdAt: number
}

export type DocFolder = {
  kind: "folder"
  id: string
  path: string
  name: string
  children: DocEntry[]
  expanded: boolean
}

export type DocEntry = DocFile | DocFolder

type DocumentTreeProps = {
  activeFileId: string | null
  entries: DocEntry[]
  onOpenEntry: (file: DocFile) => void
  onOpenFile: () => void | Promise<void>
  onOpenFolder: () => void | Promise<void>
  onToggleFolder: (folderId: string) => void
  treeError: string | null
  workspaceRoot: string | null
}

function entryMatches(entry: DocEntry, query: string) {
  const text = `${entry.name} ${entry.path}`.toLowerCase()
  return text.includes(query)
}

function normalizeQuery(query: string) {
  return query.trim().toLowerCase()
}

function fileLabel(file: DocFile) {
  return file.type === "word" ? "DOCX" : file.type.toUpperCase()
}

function DocumentTreeEmptyState({ hasQuery }: { hasQuery: boolean }) {
  return (
    <Empty className="min-h-40 border border-border/70 px-4 py-6 md:p-6">
      <EmptyHeader>
        <EmptyMedia variant="icon">
          <FileText className="size-5" />
        </EmptyMedia>
        <EmptyTitle className="text-sm">
          {hasQuery ? "没有匹配的文档" : "文件树为空"}
        </EmptyTitle>
        <EmptyDescription className="text-xs">
          {hasQuery ? "换个关键词再试试。" : "当前文件夹里还没有可打开的 DOCX 文档。"}
        </EmptyDescription>
      </EmptyHeader>
    </Empty>
  )
}

function DocumentTreeNoWorkspaceState({
  onOpenFile,
  onOpenFolder,
}: {
  onOpenFile: () => void | Promise<void>
  onOpenFolder: () => void | Promise<void>
}) {
  return (
    <Empty className="min-h-[260px] border border-border/70 px-4 py-6 md:p-6">
      <EmptyHeader>
        <EmptyMedia variant="icon">
          <FolderOpen className="size-5" />
        </EmptyMedia>
        <EmptyTitle className="text-sm">还没打开目录</EmptyTitle>
        <EmptyDescription className="text-xs">
          选择一个本地文件夹，或直接打开 DOCX 文件开始编辑。
        </EmptyDescription>
      </EmptyHeader>
      <EmptyContent>
        <div className="flex items-center gap-2">
          <Button onClick={() => void onOpenFolder()} size="sm" type="button">
            <FolderOpen className="size-4" />
            打开文件夹
          </Button>
          <Button onClick={() => void onOpenFile()} size="sm" type="button" variant="outline">
            <FileUp className="size-4" />
            打开文件
          </Button>
        </div>
      </EmptyContent>
    </Empty>
  )
}

function filterEntries(entries: DocEntry[], query: string): DocEntry[] {
  const needle = normalizeQuery(query)
  if (!needle) {
    return entries
  }

  const next: DocEntry[] = []

  for (const entry of entries) {
    if (entry.kind === "file") {
      if (entryMatches(entry, needle)) {
        next.push({ ...entry })
      }
      continue
    }

    const children = filterEntries(entry.children, needle)
    if (entryMatches(entry, needle) || children.length > 0) {
      next.push({ ...entry, expanded: true, children })
    }
  }

  return next
}

function DocumentTreeInner({
  activeFileId,
  entries,
  onOpenEntry,
  onOpenFile,
  onOpenFolder,
  onToggleFolder,
  treeError,
  workspaceRoot,
}: DocumentTreeProps) {
  const [query, setQuery] = useState("")
  const filteredEntries = useMemo(() => filterEntries(entries, query), [entries, query])

  useEffect(() => {
    setQuery("")
  }, [workspaceRoot])

  const renderEntries = useCallback(
    (nodes: DocEntry[], depth = 0): ReactNode[] =>
      nodes.flatMap((entry) => {
        const selected = entry.kind === "file" && entry.id === activeFileId
        const isFolder = entry.kind === "folder"

        return [
          <button
            className={cn(
              "group flex min-h-9 w-full items-center gap-2 rounded-lg border border-transparent pr-2 text-left text-sm transition-colors",
              selected && "border-primary/15 bg-primary/8 text-foreground",
              !selected && "hover:bg-background"
            )}
            key={entry.id}
            onClick={() => {
              if (isFolder) {
                onToggleFolder(entry.id)
              } else {
                onOpenEntry(entry)
              }
            }}
            style={{ paddingLeft: 10 + depth * 16 }}
            type="button"
          >
            <div className="flex w-5 shrink-0 items-center gap-0.5 text-muted-foreground">
              {isFolder ? (
                <ChevronRight
                  className="size-3 transition-transform"
                  style={{ transform: `rotate(${entry.expanded ? 90 : 0}deg)` }}
                />
              ) : (
                <span className="inline-flex w-3" />
              )}
              <span
                className={cn(
                  "inline-flex h-4 w-4 items-center justify-center",
                  selected && "text-primary"
                )}
              >
                {isFolder ? (
                  entry.expanded ? (
                    <FolderOpen className="size-4" />
                  ) : (
                    <Folder className="size-4" />
                  )
                ) : (
                  <FileText className="size-4" />
                )}
              </span>
            </div>

            <div className="min-w-0 flex-1 truncate text-[12px]">
              {entry.name || entry.path}
            </div>

            {isFolder ? (
              <span className="shrink-0 text-[10px] text-muted-foreground">
                {entry.children.length}
              </span>
            ) : (
              <span className="shrink-0 rounded-sm bg-muted px-1.5 py-0.5 text-[9px] font-medium text-muted-foreground">
                {fileLabel(entry)}
              </span>
            )}
          </button>,
          ...(isFolder && entry.expanded ? renderEntries(entry.children, depth + 1) : []),
        ]
      }),
    [activeFileId, onOpenEntry, onToggleFolder]
  )

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-2 p-3 text-foreground">
      <div className="relative">
        <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground" />
        <Input
          className="h-9 pr-10 pl-9 text-[12px]"
          onChange={(event) => setQuery(event.currentTarget.value)}
          placeholder="搜索资源"
          value={query}
        />
        {query.trim() ? (
          <Button
            className="absolute top-1/2 right-1 -translate-y-1/2"
            onClick={() => setQuery("")}
            size="icon-xs"
            type="button"
            variant="ghost"
          >
            <ChevronRight className="size-3 rotate-180" />
            <span className="sr-only">清空搜索</span>
          </Button>
        ) : null}
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto">
        <div className="space-y-1 pr-2">
          {treeError ? (
            <div className="flex min-h-32 flex-col justify-center rounded-lg border border-destructive/30 bg-destructive/5 px-3 text-[12px] leading-6 text-destructive">
              <div className="font-medium">文件树加载失败</div>
              <div className="break-all text-destructive/80">{treeError}</div>
            </div>
          ) : workspaceRoot ? (
            filteredEntries.length > 0 ? (
              renderEntries(filteredEntries)
            ) : (
              <DocumentTreeEmptyState hasQuery={Boolean(query.trim())} />
            )
          ) : (
            <DocumentTreeNoWorkspaceState
              onOpenFile={onOpenFile}
              onOpenFolder={onOpenFolder}
            />
          )}
        </div>
      </div>
    </div>
  )
}

export const DocumentTree = memo(DocumentTreeInner)
