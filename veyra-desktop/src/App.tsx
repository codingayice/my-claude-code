import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react"
import { invoke } from "@tauri-apps/api/core"
import { basename, dirname } from "@tauri-apps/api/path"
import { open as openDialog, save as saveDialog } from "@tauri-apps/plugin-dialog"
import { readFile, stat, writeFile } from "@tauri-apps/plugin-fs"
import { open as openPath } from "@tauri-apps/plugin-shell"
import { word, type OoJsonWord } from "@/lib/oojson-word"
import {
  Blocks,
  FileCheck2,
  FilePlus2,
  GraduationCap,
  History,
  Loader2,
  ListTodo,
  Plus,
  PanelRight,
  Presentation,
  Save,
  Scale,
  Search,
  RefreshCw,
  Table2,
  MessageSquare,
  type LucideIcon,
} from "lucide-react"

import { AiStudyWorkspace } from "@/apps/ai-study/ai-study-workspace"
import {
  APP_CATEGORY_TABS,
  WORKSPACE_APPS,
  type WorkspaceApp,
  type WorkspaceAppId,
} from "@/apps/workspace-apps"
import { AppTitleBar } from "@/components/app-title-bar"
import {
  DocumentTree,
  type DocEntry,
  type DocFile,
} from "@/components/document-tree"
import { ModuleRail, type ModuleRailItem } from "@/components/module-rail"
import ChatPanel, { ensureAgentService } from "@/components/chat-panel"
import { WordPreviewPane, type WordPreviewPaneHandle } from "@/components/word-preview-pane"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import {
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from "@/components/ui/tabs"
import {
  Sheet,
  SheetContent,
} from "@/components/ui/sheet"
import { cn } from "@/lib/utils"
import { agentApi, type AgentSessionRecord } from "@/lib/agent-api"

type WorkspaceModule = "ai" | "apps" | "tasks" | "history"

type SessionState = {
  key: string
  document: OoJsonWord
  fileName: string
  filePath: string | null
}

type AssistantSessionTarget = {
  revision: number
  sessionId: string | null
}

function WorkspaceAction({
  children,
  onClick,
  disabled,
  variant = "ghost",
}: {
  children: ReactNode
  onClick: () => void | Promise<void>
  disabled?: boolean
  variant?: "ghost" | "default"
}) {
  return (
    <Button
      className={cn(
        "h-8 cursor-pointer gap-1.5 rounded-md px-2.5 text-xs transition-none disabled:cursor-not-allowed [&_svg]:size-4",
        "hover:bg-transparent focus-visible:bg-transparent",
        variant === "default"
          ? "bg-primary text-primary-foreground hover:bg-primary hover:text-primary-foreground"
          : "bg-transparent text-muted-foreground hover:text-muted-foreground"
      )}
      disabled={disabled}
      onClick={() => void onClick()}
      size="sm"
      type="button"
      variant="ghost"
    >
      {children}
    </Button>
  )
}

const MODULES: ModuleRailItem[] = [
  { id: "ai", label: "助手", icon: MessageSquare },
  { id: "apps", label: "应用", icon: Blocks },
  { id: "tasks", label: "任务", icon: ListTodo },
  { id: "history", label: "历史", icon: History },
]

const WORKSPACE_APP_ICONS: Record<WorkspaceApp["iconKey"], LucideIcon> = {
  contract: Scale,
  deck: Presentation,
  document: FileCheck2,
  sheet: Table2,
  study: GraduationCap,
}

function createBlankDocument(): OoJsonWord {
  return word.normalize({
    kind: "word",
    version: "1.0",
    body: [
      {
        type: "paragraph",
        children: [{ type: "text", text: "未命名" }],
      },
    ],
  })
}

function createBlankSession(): SessionState {
  return {
    key: crypto.randomUUID(),
    document: createBlankDocument(),
    fileName: "未命名.docx",
    filePath: null,
  }
}

async function loadDocxFromPath(filePath: string) {
  const info = await stat(filePath)
  if (info.size === 0) {
    return createBlankDocument()
  }

  const bytes = await readFile(filePath)

  try {
    return word.normalize(
      await word.fromBuffer(bytes, {
        parseHeadersFooters: true,
        parseNotes: true,
      })
    )
  } catch (error) {
    console.warn(`[veyra-desktop] Failed to parse DOCX: ${filePath}`, error)
    return createBlankDocument()
  }
}

function isUnderRoot(candidatePath: string, rootPath: string) {
  const candidate = candidatePath.toLowerCase().replaceAll("\\", "/")
  const root = rootPath.toLowerCase().replaceAll("\\", "/").replace(/\/+$/, "")
  return candidate === root || candidate.startsWith(`${root}/`)
}

function isWordDocument(path: string) {
  return path.toLowerCase().endsWith(".docx")
}

async function buildResourceTree(
  folderPath: string,
  expandedFolders: Set<string>
): Promise<DocEntry[]> {
  return invoke<DocEntry[]>("read_resource_tree", {
    rootPath: folderPath,
    expandedFolders: [...expandedFolders],
  })
}

async function collectAncestors(rootPath: string, filePath: string) {
  const expanded = new Set<string>()
  let current = await dirname(filePath)

  while (isUnderRoot(current, rootPath) && current !== rootPath) {
    expanded.add(current)
    const parent = await dirname(current)
    if (parent === current) {
      break
    }
    current = parent
  }

  return expanded
}

async function loadWorkspaceTree(
  rootPath: string,
  activeFilePath: string | null,
  expandedFolders: Set<string>
) {
  const entries = await buildResourceTree(rootPath, expandedFolders)
  const activeId =
    activeFilePath && isUnderRoot(activeFilePath, rootPath) ? activeFilePath : null
  return { entries, activeId }
}

function AppBreadcrumb({
  appTitle,
  onBackToApps,
}: {
  appTitle: string
  onBackToApps: () => void
}) {
  return (
    <div className="flex min-w-0 items-center gap-1 text-xs">
      <button
        className="cursor-pointer rounded-sm px-1 text-muted-foreground hover:bg-muted hover:text-foreground"
        onClick={onBackToApps}
        type="button"
      >
        应用
      </button>
      <span className="text-muted-foreground/60">/</span>
      <span className="truncate px-1 text-muted-foreground">{appTitle}</span>
    </div>
  )
}

function App() {
  const [activeModule, setActiveModule] = useState<WorkspaceModule>("apps")
  const [activeApp, setActiveApp] = useState<WorkspaceAppId | null>(null)
  const [assistantOpen, setAssistantOpen] = useState(false)
  const [assistantSessionTarget, setAssistantSessionTarget] = useState<AssistantSessionTarget>({
    revision: 0,
    sessionId: null,
  })
  const [activeAssistantSessionId, setActiveAssistantSessionId] = useState<string | null>(null)
  const [assistantSessionsRevision, setAssistantSessionsRevision] = useState(0)
  const [session, setSession] = useState<SessionState>(() => createBlankSession())
  const [workspaceRoot, setWorkspaceRoot] = useState<string | null>(null)
  const [treeEntries, setTreeEntries] = useState<DocEntry[]>([])
  const [treeError, setTreeError] = useState<string | null>(null)
  const [activeFileId, setActiveFileId] = useState<string | null>(null)
  const [, setExpandedFolders] = useState<Set<string>>(new Set())
  const loadSeqRef = useRef(0)

  const openAssistantSession = useCallback((sessionId: string) => {
    setAssistantSessionTarget(current => ({ revision: current.revision + 1, sessionId }))
    setActiveAssistantSessionId(sessionId)
    setActiveModule("ai")
  }, [])

  const createAssistantSession = useCallback(() => {
    setAssistantSessionTarget(current => ({ revision: current.revision + 1, sessionId: null }))
    setActiveAssistantSessionId(null)
    setActiveModule("ai")
  }, [])

  const handleAssistantSessionReady = useCallback((sessionId: string) => {
    setActiveAssistantSessionId(sessionId)
    setAssistantSessionsRevision(current => current + 1)
  }, [])

  const applyWorkspaceTree = useCallback(
    async (
      rootPath: string | null,
      activePath: string | null,
      nextExpandedFolders: Set<string> = new Set()
    ) => {
      const seq = ++loadSeqRef.current

      if (!rootPath) {
        if (seq !== loadSeqRef.current) {
          return
        }

        setWorkspaceRoot(null)
        setTreeEntries([])
        setTreeError(null)
        setActiveFileId(null)
        setExpandedFolders(new Set())
        return
      }

      let result: Awaited<ReturnType<typeof loadWorkspaceTree>>
      try {
        result = await loadWorkspaceTree(rootPath, activePath, nextExpandedFolders)
      } catch (error) {
        console.error(`[veyra-desktop] Failed to load document tree: ${rootPath}`, error)
        if (seq !== loadSeqRef.current) {
          return
        }

        setWorkspaceRoot(rootPath)
        setTreeEntries([])
        setTreeError(error instanceof Error ? error.message : String(error))
        setActiveFileId(null)
        setExpandedFolders(nextExpandedFolders)
        return
      }

      if (seq !== loadSeqRef.current) {
        return
      }

      setWorkspaceRoot(rootPath)
      setTreeEntries(result.entries)
      setTreeError(null)
      setActiveFileId(result.activeId)
      setExpandedFolders(nextExpandedFolders)
    },
    []
  )

  const openDocumentAtPath = useCallback(
    async (
      filePath: string,
      preferredRoot?: string | null,
      nextModule: WorkspaceModule = "apps"
    ) => {
      const document = await loadDocxFromPath(filePath)
      const fileName = await basename(filePath)
      const rootPath = preferredRoot ?? (await dirname(filePath))
      const nextExpandedFolders = await collectAncestors(rootPath, filePath)

      setSession({
        key: crypto.randomUUID(),
        document,
        fileName,
        filePath,
      })

      await applyWorkspaceTree(rootPath, filePath, nextExpandedFolders)
      setActiveModule(nextModule)
      if (nextModule === "apps") {
        setActiveApp("document")
      }
    },
    [applyWorkspaceTree]
  )

  const handleOpenAppTreeFile = useCallback(
    async (file: DocFile) => {
      if (isWordDocument(file.path)) {
        await openDocumentAtPath(file.path, workspaceRoot)
        return
      }

      await openPath(file.path)
    },
    [openDocumentAtPath, workspaceRoot]
  )

  const handleCreateAppDocument = useCallback(() => {
    setSession(createBlankSession())
    setActiveFileId(null)
    setActiveModule("apps")
    setActiveApp("document")
  }, [])

  const handleOpenFolder = useCallback(async () => {
    const selected = await openDialog({
      multiple: false,
      directory: true,
      title: "打开文件夹",
    })

    if (!selected || Array.isArray(selected)) {
      return
    }

    const activePath =
      session.filePath && isUnderRoot(session.filePath, selected)
        ? session.filePath
        : null

    const nextExpandedFolders = activePath
      ? await collectAncestors(selected, activePath)
      : new Set<string>()

    await applyWorkspaceTree(selected, activePath, nextExpandedFolders)
    setActiveModule("apps")
    setActiveApp("document")
  }, [applyWorkspaceTree, session.filePath])

  const handleOpenAppDocument = useCallback(async () => {
    const selected = await openDialog({
      multiple: false,
      title: "打开 DOCX",
      filters: [{ name: "Word 文档", extensions: ["docx"] }],
    })

    if (!selected || Array.isArray(selected)) {
      return
    }

    await openDocumentAtPath(selected, await dirname(selected))
  }, [openDocumentAtPath])

  const handleSaveDocument = useCallback(
    async (buffer: ArrayBuffer) => {
      let targetPath = session.filePath

      if (!targetPath) {
        targetPath = await saveDialog({
          title: "保存 DOCX",
          defaultPath: session.fileName,
          filters: [{ name: "Word 文档", extensions: ["docx"] }],
        })
      }

      if (!targetPath) {
        return
      }

      await writeFile(targetPath, new Uint8Array(buffer))
      const nextName = await basename(targetPath)
      const rootPath = workspaceRoot ?? (await dirname(targetPath))
      const nextExpandedFolders = await collectAncestors(rootPath, targetPath)

      setSession((prev) => ({
        ...prev,
        fileName: nextName,
        filePath: targetPath,
      }))

      await applyWorkspaceTree(rootPath, targetPath, nextExpandedFolders)
    },
    [applyWorkspaceTree, session.fileName, session.filePath, workspaceRoot]
  )

  const handleDocumentNameChange = useCallback((name: string) => {
    setSession((prev) => ({
      ...prev,
      fileName: name || "未命名.docx",
    }))
  }, [])

  const handleToggleFolder = useCallback(
    (folderId: string) => {
      setExpandedFolders((prev) => {
        const next = new Set(prev)
        if (next.has(folderId)) {
          next.delete(folderId)
        } else {
          next.add(folderId)
        }

        if (workspaceRoot) {
          void applyWorkspaceTree(workspaceRoot, session.filePath, next)
        }

        return next
      })
    },
    [applyWorkspaceTree, session.filePath, workspaceRoot]
  )

  const moduleLabel = MODULES.find((item) => item.id === activeModule)?.label
  const activeAppTitle = WORKSPACE_APPS.find((app) => app.id === activeApp)?.title
  const titleSubtitle =
    activeModule === "apps" && activeAppTitle ? (
      <AppBreadcrumb
        appTitle={activeAppTitle}
        onBackToApps={() => setActiveApp(null)}
      />
    ) : undefined

  return (
    <div className="flex h-full min-h-0 flex-col bg-background text-foreground">
      <AppTitleBar
        subtitle={titleSubtitle ? undefined : moduleLabel}
        subtitleNode={titleSubtitle}
        title="Veyra"
      />

      <div className="flex min-h-0 flex-1">
        <ModuleRail
          activeId={activeModule}
          items={MODULES}
          onChange={(id) => setActiveModule(id as WorkspaceModule)}
        />

        <div className="flex min-w-0 flex-1 flex-col">
          <main className="flex min-h-0 flex-1 flex-col">
            {activeModule === "ai" ? (
              <AiWorkspace
                activeSessionId={activeAssistantSessionId}
                key={`assistant-${assistantSessionTarget.revision}`}
                onNewSession={createAssistantSession}
                onOpenSession={openAssistantSession}
                onSessionReady={handleAssistantSessionReady}
                refreshKey={assistantSessionsRevision}
                sessionId={assistantSessionTarget.sessionId}
                workspaceRoot={workspaceRoot}
              />
            ) : null}
            {activeModule === "apps" ? (
              activeApp === "document" ? (
                <WordWorkspace
                  activeFileId={activeFileId}
                  document={session.document}
                  entries={treeEntries}
                  fileName={session.fileName}
                  filePath={session.filePath}
                  key={`document-app-${session.key}`}
                  onCreateDocument={handleCreateAppDocument}
                  onDocumentNameChange={handleDocumentNameChange}
                  onOpenAssistant={() => setAssistantOpen(true)}
                  onOpenDocument={handleOpenAppDocument}
                  onOpenDocumentAtPath={(file) => void handleOpenAppTreeFile(file)}
                  onOpenFolder={handleOpenFolder}
                  onSaveDocument={handleSaveDocument}
                  onToggleFolder={handleToggleFolder}
                  treeError={treeError}
                  workspaceRoot={workspaceRoot}
                />
              ) : activeApp === "study" ? (
                <AiStudyWorkspace />
              ) : (
                <AppsMarketplace onOpenApp={(appId) => setActiveApp(appId)} />
              )
            ) : null}
            {activeModule === "tasks" ? <TaskWorkspace /> : null}
            {activeModule === "history" ? (
              <HistoryWorkspace
                activeSessionId={activeAssistantSessionId}
                onNewSession={createAssistantSession}
                onOpenSession={openAssistantSession}
              />
            ) : null}
          </main>
        </div>
      </div>

      <Sheet open={assistantOpen} onOpenChange={setAssistantOpen}>
        <SheetContent className="w-[min(420px,100vw)] gap-0 p-0 sm:max-w-[420px]">
          <div className="min-h-0 flex-1">
            {assistantOpen ? (
              <ChatPanel
                initialInput="Polish the current paragraph and make the structure clearer."
                workspaceRoot={workspaceRoot}
              />
            ) : null}
          </div>
        </SheetContent>
      </Sheet>
    </div>
  )
}

type WordWorkspaceProps = {
  activeFileId: string | null
  document: OoJsonWord
  entries: DocEntry[]
  fileName: string
  filePath: string | null
  onCreateDocument: () => void
  onDocumentNameChange: (name: string) => void
  onOpenAssistant: () => void
  onOpenDocument: () => Promise<void>
  onOpenDocumentAtPath: (file: DocFile) => void
  onOpenFolder: () => Promise<void>
  onSaveDocument: (buffer: ArrayBuffer) => void | Promise<void>
  onToggleFolder: (folderId: string) => void
  treeError: string | null
  workspaceRoot: string | null
}

function WordWorkspace({
  activeFileId,
  document,
  entries,
  fileName,
  filePath,
  onCreateDocument,
  onDocumentNameChange,
  onOpenAssistant,
  onOpenDocument,
  onOpenDocumentAtPath,
  onOpenFolder,
  onSaveDocument,
  onToggleFolder,
  treeError,
  workspaceRoot,
}: WordWorkspaceProps) {
  const previewRef = useRef<WordPreviewPaneHandle>(null)
  const [saving, setSaving] = useState(false)

  const handleSave = useCallback(async () => {
    if (saving) {
      return
    }

    setSaving(true)
    try {
      const buffer = await previewRef.current?.save()
      if (buffer) {
        await onSaveDocument(buffer)
      }
    } finally {
      setSaving(false)
    }
  }, [onSaveDocument, saving])

  return (
    <div className="flex min-h-0 flex-1">
      <aside className="flex min-h-0 w-80 shrink-0 flex-col border-r border-border bg-muted/20">
        <DocumentTree
          activeFileId={activeFileId}
          entries={entries}
          onOpenEntry={onOpenDocumentAtPath}
          onOpenFile={onOpenDocument}
          onOpenFolder={onOpenFolder}
          onToggleFolder={onToggleFolder}
          treeError={treeError}
          workspaceRoot={workspaceRoot}
        />
      </aside>

      <section className="flex min-w-0 flex-1 flex-col bg-muted/30">
        <div className="flex min-h-12 shrink-0 flex-wrap items-center justify-between gap-2 border-b border-border bg-white px-4 py-2">
          <div className="flex min-w-0 items-center gap-2 text-sm">
            <Badge className="shrink-0 rounded-md">{filePath ? "本地文件" : "未保存草稿"}</Badge>
            <Input
              aria-label="文档名称"
              className="h-8 min-w-0 max-w-[360px] border-transparent bg-transparent px-2 text-sm font-semibold shadow-none hover:bg-muted/60 focus-visible:border-border focus-visible:bg-background focus-visible:ring-2 focus-visible:ring-ring/20"
              onChange={(event) => onDocumentNameChange(event.currentTarget.value)}
              spellCheck={false}
              value={fileName}
            />
          </div>
          <div className="flex flex-wrap items-center justify-end gap-2">
            <WorkspaceAction onClick={onCreateDocument} variant="default">
              <FilePlus2 />
              <span>新建</span>
            </WorkspaceAction>
            <WorkspaceAction onClick={onOpenAssistant} variant="default">
              <PanelRight />
              <span>助手</span>
            </WorkspaceAction>
            <WorkspaceAction disabled={saving} onClick={handleSave} variant="default">
              {saving ? <Loader2 className="spin size-4" /> : <Save />}
              <span>保存</span>
            </WorkspaceAction>
          </div>
        </div>

        <div className="min-h-0 flex-1">
          <WordPreviewPane
            ref={previewRef}
            document={document}
            documentName={fileName}
          />
        </div>
      </section>
    </div>
  )
}

function AppsMarketplace({
  onOpenApp,
}: {
  onOpenApp: (appId: WorkspaceAppId) => void
}) {
  const [query, setQuery] = useState("")
  const normalizedQuery = query.trim().toLowerCase()

  const visibleApps = WORKSPACE_APPS.filter((app) => {
    if (!normalizedQuery) {
      return true
    }

    return [
      app.title,
      app.description,
      app.categoryLabel,
      app.outputLabel,
    ].some((value) => value.toLowerCase().includes(normalizedQuery))
  })

  return (
    <div className="flex min-h-0 flex-1 flex-col bg-muted/20">
      <header className="flex shrink-0 flex-wrap items-center justify-between gap-3 border-b border-border bg-background px-6 py-4">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <Badge className="rounded-md" variant="secondary">Workbench</Badge>
            <span className="text-sm text-muted-foreground">AI 应用市场</span>
          </div>
          <h1 className="mt-2 text-xl font-semibold">选择一个工作台应用</h1>
        </div>
        <div className="relative w-full max-w-xs">
          <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            aria-label="搜索应用"
            className="h-9 pl-9"
            onChange={(event) => setQuery(event.currentTarget.value)}
            placeholder="搜索应用、类型或产物"
            value={query}
          />
        </div>
      </header>

      <Tabs className="flex min-h-0 flex-1 flex-col px-6 py-5" defaultValue="all">
        <TabsList className="w-fit">
          {APP_CATEGORY_TABS.map((tab) => (
            <TabsTrigger key={tab.id} value={tab.id}>
              {tab.label}
            </TabsTrigger>
          ))}
        </TabsList>

        {APP_CATEGORY_TABS.map((tab) => {
          const appsForTab = visibleApps.filter((app) => {
            return tab.id === "all" || app.category === tab.id
          })

          return (
            <TabsContent
              className="mt-5 min-h-0 flex-1 data-[state=inactive]:hidden"
              key={tab.id}
              value={tab.id}
            >
              {appsForTab.length > 0 ? (
                <div className="grid auto-rows-fr grid-cols-1 gap-4 lg:grid-cols-2 xl:grid-cols-3">
                  {appsForTab.map((app) => (
                    <WorkspaceAppCard
                      app={app}
                      key={app.id}
                      onOpenApp={onOpenApp}
                    />
                  ))}
                </div>
              ) : (
                <EmptyWorkspace
                  title="没有匹配的应用"
                  description="换一个关键词，或切回全部应用查看当前可用的工作台。"
                />
              )}
            </TabsContent>
          )
        })}
      </Tabs>
    </div>
  )
}

function WorkspaceAppCard({
  app,
  onOpenApp,
}: {
  app: WorkspaceApp
  onOpenApp: (appId: WorkspaceAppId) => void
}) {
  const Icon = WORKSPACE_APP_ICONS[app.iconKey]
  const ready = app.status === "ready"

  return (
    <article className="flex min-h-52 flex-col rounded-lg border border-border bg-background p-4 shadow-xs">
      <div className="flex items-start justify-between gap-3">
        <div className="flex min-w-0 items-center gap-3">
          <div className="flex size-10 shrink-0 items-center justify-center rounded-md border border-border bg-muted/50">
            <Icon className="size-5 text-foreground" />
          </div>
          <div className="min-w-0">
            <h2 className="truncate text-base font-semibold">{app.title}</h2>
            <p className="mt-1 text-xs text-muted-foreground">{app.categoryLabel}</p>
          </div>
        </div>
        <Badge className="rounded-md" variant={ready ? "default" : "secondary"}>
          {ready ? "可用" : "规划中"}
        </Badge>
      </div>

      <p className="mt-4 flex-1 text-sm leading-6 text-muted-foreground">
        {app.description}
      </p>

      <div className="mt-4 flex items-center justify-between gap-3 border-t border-border pt-4">
        <span className="min-w-0 truncate text-xs text-muted-foreground">
          {app.outputLabel}
        </span>
        <Button
          className="h-8 rounded-md"
          data-testid={
            app.id === "study"
              ? "open-ai-study"
              : undefined
          }
          disabled={!ready || !isReadyWorkspaceApp(app.id)}
          onClick={() => {
            if (isReadyWorkspaceApp(app.id)) {
              onOpenApp(app.id)
            }
          }}
          size="sm"
          type="button"
        >
          打开
        </Button>
      </div>
    </article>
  )
}

function isReadyWorkspaceApp(appId: WorkspaceApp["id"]): appId is WorkspaceAppId {
  return appId === "document" || appId === "study"
}

function AiWorkspace({
  activeSessionId,
  onNewSession,
  onOpenSession,
  onSessionReady,
  refreshKey,
  sessionId,
  workspaceRoot,
}: {
  activeSessionId: string | null
  onNewSession: () => void
  onOpenSession: (sessionId: string) => void
  onSessionReady: (sessionId: string) => void
  refreshKey: number
  sessionId: string | null
  workspaceRoot: string | null
}) {
  return (
    <div className="flex min-h-0 flex-1 bg-background">
      <AssistantSessionSidebar
        activeSessionId={activeSessionId}
        onNewSession={onNewSession}
        onOpenSession={onOpenSession}
        refreshKey={refreshKey}
      />
      <div className="flex min-w-0 flex-1 flex-col">
        <ChatPanel
          initialSessionId={sessionId}
          onSessionReady={onSessionReady}
          workspaceRoot={workspaceRoot}
        />
      </div>
    </div>
  )
}

function AssistantSessionSidebar({
  activeSessionId,
  onNewSession,
  onOpenSession,
  refreshKey,
}: {
  activeSessionId: string | null
  onNewSession: () => void
  onOpenSession: (sessionId: string) => void
  refreshKey: number
}) {
  const [sessions, setSessions] = useState<AgentSessionRecord[]>([])
  const [query, setQuery] = useState("")
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const loadSessions = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      await ensureAgentService()
      const response = await agentApi.listSessions()
      setSessions(response.items)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "会话列表加载失败。")
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void loadSessions()
  }, [loadSessions, refreshKey])

  const visibleSessions = useMemo(() => {
    const normalized = query.trim().toLowerCase()
    if (!normalized) return sessions
    return sessions.filter(session =>
      session.title.toLowerCase().includes(normalized) ||
      session.sessionId.toLowerCase().includes(normalized)
    )
  }, [query, sessions])

  return (
    <aside className="flex w-72 shrink-0 flex-col border-r border-border bg-muted/20">
      <div className="flex h-14 items-center gap-2 border-b border-border bg-background px-3">
        <div className="min-w-0 flex-1 text-sm font-semibold">会话</div>
        <Button
          aria-label="刷新会话"
          disabled={loading}
          onClick={() => void loadSessions()}
          size="icon-sm"
          type="button"
          variant="ghost"
        >
          <RefreshCw className={cn("size-4", loading && "animate-spin")} />
        </Button>
        <Button aria-label="新建会话" onClick={onNewSession} size="icon-sm" type="button">
          <Plus className="size-4" />
        </Button>
      </div>

      <div className="border-b border-border/70 p-3">
        <div className="relative">
          <Search className="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground" />
          <Input
            className="h-8 pl-8 text-xs"
            onChange={event => setQuery(event.target.value)}
            placeholder="搜索会话"
            value={query}
          />
        </div>
      </div>

      <div className="min-h-0 flex-1 overflow-auto p-2">
        {error ? (
          <button
            className="w-full rounded-lg border border-destructive/30 p-3 text-left text-xs text-destructive"
            onClick={() => void loadSessions()}
            type="button"
          >
            {error} 点击重试
          </button>
        ) : loading && sessions.length === 0 ? (
          <div className="flex items-center justify-center gap-2 py-8 text-xs text-muted-foreground">
            <Loader2 className="size-3.5 animate-spin" />
            加载中…
          </div>
        ) : visibleSessions.length === 0 ? (
          <div className="py-8 text-center text-xs text-muted-foreground">
            {query ? "没有匹配的会话" : "暂无历史会话"}
          </div>
        ) : (
          <div className="space-y-1">
            {visibleSessions.map(session => (
              <button
                className={cn(
                  "w-full rounded-lg px-3 py-2.5 text-left transition-colors hover:bg-accent",
                  activeSessionId === session.sessionId && "bg-accent text-accent-foreground"
                )}
                key={session.sessionId}
                onClick={() => onOpenSession(session.sessionId)}
                type="button"
              >
                <div className="truncate text-xs font-medium">{session.title || session.sessionId}</div>
                <div className="mt-1 flex items-center justify-between gap-2 text-[10px] text-muted-foreground">
                  <span className="truncate font-mono">{session.sessionId.slice(0, 8)}</span>
                  <span className="shrink-0">{formatSessionTime(session.updatedAt)}</span>
                </div>
              </button>
            ))}
          </div>
        )}
      </div>
    </aside>
  )
}

function TaskWorkspace() {
  return (
    <EmptyWorkspace
      title="任务区即将接入"
      description="这里会承载 AI 执行队列、文件处理过程和人工复核步骤。"
    />
  )
}

function formatSessionTime(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date)
}

function HistoryWorkspace({
  activeSessionId,
  onNewSession,
  onOpenSession,
}: {
  activeSessionId: string | null
  onNewSession: () => void
  onOpenSession: (sessionId: string) => void
}) {
  const [sessions, setSessions] = useState<AgentSessionRecord[]>([])
  const [query, setQuery] = useState("")
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const loadSessions = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      await ensureAgentService()
      const response = await agentApi.listSessions()
      setSessions(response.items)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "历史会话加载失败。")
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void loadSessions()
  }, [loadSessions])

  const visibleSessions = useMemo(() => {
    const normalized = query.trim().toLowerCase()
    if (!normalized) return sessions
    return sessions.filter(session =>
      session.title.toLowerCase().includes(normalized) ||
      session.sessionId.toLowerCase().includes(normalized)
    )
  }, [query, sessions])

  return (
    <div className="flex min-h-0 flex-1 flex-col bg-muted/20">
      <div className="flex items-center gap-3 border-b border-border bg-background px-5 py-4">
        <div className="min-w-0 flex-1">
          <h2 className="text-base font-semibold">历史会话</h2>
          <p className="mt-1 text-xs text-muted-foreground">继续之前的对话和任务上下文</p>
        </div>
        <Button className="gap-1.5" onClick={onNewSession} size="sm" type="button">
          <Plus className="size-4" />
          新建会话
        </Button>
      </div>

      <div className="flex items-center gap-2 border-b border-border/70 bg-background px-5 py-3">
        <div className="relative max-w-md flex-1">
          <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            className="pl-9"
            onChange={event => setQuery(event.target.value)}
            placeholder="搜索标题或会话 ID"
            value={query}
          />
        </div>
        <Button
          aria-label="刷新历史会话"
          disabled={loading}
          onClick={() => void loadSessions()}
          size="icon"
          type="button"
          variant="outline"
        >
          <RefreshCw className={cn("size-4", loading && "animate-spin")} />
        </Button>
      </div>

      <div className="min-h-0 flex-1 overflow-auto p-5">
        {error ? (
          <div className="rounded-xl border border-destructive/30 bg-destructive/5 p-4 text-sm text-destructive">
            <p>{error}</p>
            <Button className="mt-3" onClick={() => void loadSessions()} size="sm" type="button" variant="outline">
              重试
            </Button>
          </div>
        ) : loading && sessions.length === 0 ? (
          <div className="flex items-center justify-center gap-2 py-20 text-sm text-muted-foreground">
            <Loader2 className="size-4 animate-spin" />
            正在加载历史会话…
          </div>
        ) : visibleSessions.length === 0 ? (
          <div className="py-20 text-center text-sm text-muted-foreground">
            {query ? "没有匹配的历史会话" : "还没有历史会话"}
          </div>
        ) : (
          <div className="mx-auto grid max-w-5xl gap-3">
            {visibleSessions.map(session => (
              <button
                className={cn(
                  "group rounded-xl border bg-background p-4 text-left shadow-xs transition-colors hover:border-primary/40 hover:bg-accent/30",
                  activeSessionId === session.sessionId && "border-primary/50 bg-accent/40"
                )}
                key={session.sessionId}
                onClick={() => onOpenSession(session.sessionId)}
                type="button"
              >
                <div className="flex items-start justify-between gap-4">
                  <div className="min-w-0 flex-1">
                    <div className="truncate text-sm font-medium">{session.title || session.sessionId}</div>
                    <div className="mt-2 truncate font-mono text-[11px] text-muted-foreground">
                      {session.sessionId}
                    </div>
                  </div>
                  <div className="shrink-0 text-xs text-muted-foreground">
                    {formatSessionTime(session.updatedAt)}
                  </div>
                </div>
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}

function EmptyWorkspace({
  title,
  description,
}: {
  title: string
  description: string
}) {
  return (
    <div className="flex min-h-0 flex-1 items-center justify-center bg-muted/20 p-6">
      <div className="max-w-lg rounded-2xl border border-border bg-background p-8 text-center shadow-xs">
        <div className="text-lg font-semibold">{title}</div>
        <p className="mt-3 text-sm leading-7 text-muted-foreground">
          {description}
        </p>
      </div>
    </div>
  )
}

export default App
