import { useMemo, useState } from "react"
import {
  ArrowLeft,
  BookOpenCheck,
  Check,
  ChevronRight,
  ClipboardList,
  FileText,
  GraduationCap,
  Library,
  LineChart,
  MessageSquareText,
  PanelRightClose,
  PanelRightOpen,
  Paperclip,
  Plus,
  Search,
  SendHorizontal,
  Sparkles,
  Target,
  type LucideIcon,
} from "lucide-react"

import {
  Conversation,
  ConversationContent,
  ConversationScrollButton,
} from "@/components/ai/conversation"
import { Message, MessageContent } from "@/components/ai/message"
import {
  PromptInput,
  PromptInputBody,
  PromptInputFooter,
  PromptInputSubmit,
  PromptInputTextarea,
} from "@/components/ai/prompt-input"
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Progress } from "@/components/ui/progress"
import { ScrollArea } from "@/components/ui/scroll-area"
import { Separator } from "@/components/ui/separator"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { Textarea } from "@/components/ui/textarea"
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip"
import { cn } from "@/lib/utils"

type CourseStage = "intake" | "outline" | "teaching" | "review"
type CourseFilter = "all" | CourseStage
type MessageRole = "assistant" | "user"
type ArtifactKind = "intake" | "outline" | "derivative" | "exercise" | "review"

type CourseMessage = {
  id: string
  role: MessageRole
  content: string
  label?: string
}

type CourseMaterial = {
  id: string
  name: string
  type: "PDF" | "讲义" | "题目" | "链接" | "备注"
  focus: string
}

type SyllabusNode = {
  id: string
  title: string
  purpose: string
  status: "done" | "current" | "next"
}

type TeachingArtifact = {
  kind: ArtifactKind
  title: string
  description: string
  prompts: string[]
}

type StudyCourse = {
  id: string
  title: string
  subject: string
  goal: string
  level: string
  stage: CourseStage
  progress: number
  currentNodeId: string
  updatedAt: string
  materials: CourseMaterial[]
  outline: SyllabusNode[]
  messages: CourseMessage[]
  artifact: TeachingArtifact
  teacherNote: string
}

type NewCourseDraft = {
  title: string
  goal: string
  level: string
  materialNote: string
}

const STAGE_ORDER: CourseStage[] = ["intake", "outline", "teaching", "review"]

const STAGE_META: Record<
  CourseStage,
  { label: string; title: string; icon: LucideIcon; description: string }
> = {
  intake: {
    label: "了解需求",
    title: "前置了解",
    icon: MessageSquareText,
    description: "AI 老师先确认目标、基础、资料和偏好的教学方式。",
  },
  outline: {
    label: "确认大纲",
    title: "教学大纲",
    icon: ClipboardList,
    description: "把目标拆成可教学的章节，和用户一起调整顺序与深度。",
  },
  teaching: {
    label: "开始教学",
    title: "沉浸教学",
    icon: GraduationCap,
    description: "AI 讲解、追问、出题、批改，并根据回答切换讲法。",
  },
  review: {
    label: "保存记录",
    title: "本节记录",
    icon: BookOpenCheck,
    description: "保存本节掌握情况，下一次从真实薄弱点继续。",
  },
}

const QUICK_ACTIONS = [
  "更细一点",
  "换个例子",
  "直接出题",
  "按资料讲",
  "确认大纲",
  "保存本节",
]

const DEFAULT_DRAFT: NewCourseDraft = {
  title: "高中导数专项",
  goal: "从 0 理解导数，最后能攻关综合难题",
  level: "从零开始，公式和图像都不稳",
  materialNote: "可以按教材导数章节和压轴题讲",
}

const MOCK_COURSES: StudyCourse[] = [
  {
    id: "derivative",
    title: "高中导数专项",
    subject: "数学",
    goal: "从 0 理解导数，最后能攻关综合难题",
    level: "从零开始，能看懂函数图像，但不会把图像和公式联系起来",
    stage: "teaching",
    progress: 32,
    currentNodeId: "instant-rate",
    updatedAt: "刚刚",
    materials: [
      {
        id: "m-derivative-book",
        name: "教材：导数及其应用",
        type: "讲义",
        focus: "定义、几何意义、单调性",
      },
      {
        id: "m-derivative-hard",
        name: "近三年压轴题摘录",
        type: "题目",
        focus: "恒成立、参数范围、极值",
      },
    ],
    outline: [
      {
        id: "rate",
        title: "变化率直觉",
        purpose: "先理解函数在一段区间内变化快慢。",
        status: "done",
      },
      {
        id: "instant-rate",
        title: "瞬时变化率与切线斜率",
        purpose: "把极限、切线和导数值连成一个画面。",
        status: "current",
      },
      {
        id: "monotonicity",
        title: "单调性、极值与最值",
        purpose: "用导数符号判断函数走势。",
        status: "next",
      },
      {
        id: "hard-problems",
        title: "综合难题攻关",
        purpose: "处理恒成立、存在性和参数范围。",
        status: "next",
      },
    ],
    messages: [
      {
        id: "d-1",
        role: "assistant",
        label: "AI 老师",
        content:
          "我们现在只处理一个核心画面：导数不是先背公式，而是描述某一点附近的变化速度。先把它看成“把很短一段区间继续缩短，最后盯住一个点”。",
      },
      {
        id: "d-2",
        role: "assistant",
        label: "追问",
        content:
          "如果 f'(1)=2，你先不要写定义。用一句自然语言解释这个 2 代表什么？",
      },
    ],
    artifact: {
      kind: "derivative",
      title: "瞬时变化率板书",
      description: "把平均变化率逐步压缩到一个点，连接到切线斜率。",
      prompts: ["先看割线", "缩小区间", "得到切线", "用导数值描述变化"],
    },
    teacherNote:
      "你已经能说出“变化快慢”，下一步要把“某一点附近”和“切线斜率”说准确。",
  },
  {
    id: "python-api",
    title: "Python API 开发",
    subject: "编程",
    goal: "做出一个能运行、能调试、能扩展的接口项目",
    level: "会写 Python 函数，没系统做过 Web API",
    stage: "outline",
    progress: 18,
    currentNodeId: "http-model",
    updatedAt: "今天",
    materials: [
      {
        id: "m-api-note",
        name: "项目需求：用户管理接口",
        type: "备注",
        focus: "登录、查询、错误处理",
      },
    ],
    outline: [
      {
        id: "http-model",
        title: "HTTP 请求模型",
        purpose: "分清 URL、方法、参数和响应。",
        status: "current",
      },
      {
        id: "fastapi-route",
        title: "路由与参数校验",
        purpose: "写出可运行的接口，并给出清楚错误。",
        status: "next",
      },
      {
        id: "debugging",
        title: "调试与接口测试",
        purpose: "看懂请求日志和失败响应。",
        status: "next",
      },
    ],
    messages: [
      {
        id: "p-1",
        role: "assistant",
        label: "AI 老师",
        content:
          "我先根据你的目标拟一个三段式大纲：请求模型、最小接口、调试与扩展。你可以让我按项目资料讲，或者先从概念补齐。",
      },
    ],
    artifact: {
      kind: "outline",
      title: "API 课程大纲",
      description: "从能跑起来的最小接口开始，再补参数校验和错误处理。",
      prompts: ["先做一个 GET 接口", "再加请求体", "最后处理错误"],
    },
    teacherNote: "适合边做边讲，先避免框架概念堆叠。",
  },
  {
    id: "english-report",
    title: "英语工作汇报",
    subject: "英语",
    goal: "能用 5 分钟讲清进度、风险和下一步",
    level: "能读英文材料，但开口时句子组织不稳定",
    stage: "intake",
    progress: 6,
    currentNodeId: "scenario",
    updatedAt: "昨天",
    materials: [],
    outline: [
      {
        id: "scenario",
        title: "场景确认",
        purpose: "确认汇报对象、时间长度和常见问题。",
        status: "current",
      },
      {
        id: "structure",
        title: "汇报结构",
        purpose: "固定 progress / risk / next step 的表达顺序。",
        status: "next",
      },
      {
        id: "practice",
        title: "口头演练",
        purpose: "AI 扮演听众追问并纠正表达。",
        status: "next",
      },
    ],
    messages: [
      {
        id: "e-1",
        role: "assistant",
        label: "AI 老师",
        content:
          "开始前我需要先了解你的真实场景：你通常向谁汇报？是周会、客户同步，还是 1:1？",
      },
    ],
    artifact: {
      kind: "intake",
      title: "教学前了解",
      description: "先确认汇报对象、压力点和可练习材料。",
      prompts: ["汇报对象", "常见卡点", "可用材料"],
    },
    teacherNote: "还需要补充真实汇报材料，才能让 AI 以老师身份纠正表达。",
  },
]

export function AiStudyWorkspace() {
  const [courses, setCourses] = useState<StudyCourse[]>(() =>
    structuredClone(MOCK_COURSES)
  )
  const [activeCourseId, setActiveCourseId] = useState<string | null>(null)
  const [canvasOpen, setCanvasOpen] = useState(true)

  const activeCourse = courses.find((course) => course.id === activeCourseId)

  const updateCourse = (courseId: string, updater: (course: StudyCourse) => StudyCourse) => {
    setCourses((current) =>
      current.map((course) => (course.id === courseId ? updater(course) : course))
    )
  }

  const createAndOpenCourse = (draft: NewCourseDraft) => {
    const course = createCourse(draft)
    setCourses((current) => [course, ...current])
    setActiveCourseId(course.id)
    setCanvasOpen(true)
  }

  if (activeCourse) {
    return (
      <CourseWorkspaceView
        canvasOpen={canvasOpen}
        course={activeCourse}
        onBack={() => setActiveCourseId(null)}
        onToggleCanvas={() => setCanvasOpen((open) => !open)}
        onUpdateCourse={(updater) => updateCourse(activeCourse.id, updater)}
      />
    )
  }

  return (
    <CourseLibraryView
      courses={courses}
      onCreateCourse={createAndOpenCourse}
      onOpenCourse={(courseId) => {
        setActiveCourseId(courseId)
        setCanvasOpen(true)
      }}
    />
  )
}

function CourseLibraryView({
  courses,
  onCreateCourse,
  onOpenCourse,
}: {
  courses: StudyCourse[]
  onCreateCourse: (draft: NewCourseDraft) => void
  onOpenCourse: (courseId: string) => void
}) {
  const [query, setQuery] = useState("")
  const [filter, setFilter] = useState<CourseFilter>("all")
  const [dialogOpen, setDialogOpen] = useState(false)
  const [draft, setDraft] = useState<NewCourseDraft>(DEFAULT_DRAFT)

  const normalizedQuery = query.trim().toLowerCase()
  const visibleCourses = courses.filter((course) => {
    const matchesFilter = filter === "all" || course.stage === filter
    const matchesQuery =
      !normalizedQuery ||
      [course.title, course.subject, course.goal, course.level, course.teacherNote]
        .join(" ")
        .toLowerCase()
        .includes(normalizedQuery)

    return matchesFilter && matchesQuery
  })

  const submitCourse = () => {
    onCreateCourse(draft)
    setDraft(DEFAULT_DRAFT)
    setDialogOpen(false)
  }

  return (
    <TooltipProvider>
      <div className="flex min-h-0 flex-1 flex-col bg-background text-foreground">
        <header className="flex shrink-0 flex-wrap items-center justify-between gap-3 border-b border-border px-6 py-4">
          <div className="min-w-0">
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <GraduationCap className="size-4" />
              <span>AI伴学</span>
            </div>
            <h1 className="mt-1 text-xl font-semibold tracking-tight">
              选择一门课程，让 AI 老师继续教
            </h1>
          </div>
          <div className="flex w-full flex-wrap items-center gap-2 sm:w-auto">
            <div className="relative min-w-56 flex-1 sm:w-72 sm:flex-none">
              <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                aria-label="搜索课程"
                className="h-9 rounded-md pl-9"
                onChange={(event) => setQuery(event.currentTarget.value)}
                placeholder="搜索课程、目标或资料"
                value={query}
              />
            </div>
            <Dialog onOpenChange={setDialogOpen} open={dialogOpen}>
              <DialogTrigger asChild>
                <Button className="h-9 rounded-md" type="button">
                  <Plus />
                  新建课程
                </Button>
              </DialogTrigger>
              <DialogContent className="sm:max-w-[560px]">
                <DialogHeader>
                  <DialogTitle>新建 AI伴学课程</DialogTitle>
                  <DialogDescription>
                    先告诉 AI 老师你想系统学习什么，进入课程后它会继续追问、看资料并拟定大纲。
                  </DialogDescription>
                </DialogHeader>
                <div className="grid gap-3">
                  <label className="grid gap-1.5">
                    <span className="text-xs font-medium text-muted-foreground">
                      课程名称
                    </span>
                    <Input
                      onChange={(event) =>
                        setDraft({ ...draft, title: event.currentTarget.value })
                      }
                      value={draft.title}
                    />
                  </label>
                  <label className="grid gap-1.5">
                    <span className="text-xs font-medium text-muted-foreground">
                      学习目标
                    </span>
                    <Textarea
                      className="min-h-20 resize-none"
                      onChange={(event) =>
                        setDraft({ ...draft, goal: event.currentTarget.value })
                      }
                      value={draft.goal}
                    />
                  </label>
                  <label className="grid gap-1.5">
                    <span className="text-xs font-medium text-muted-foreground">
                      当前基础
                    </span>
                    <Input
                      onChange={(event) =>
                        setDraft({ ...draft, level: event.currentTarget.value })
                      }
                      value={draft.level}
                    />
                  </label>
                  <label className="grid gap-1.5">
                    <span className="text-xs font-medium text-muted-foreground">
                      给老师的资料说明
                    </span>
                    <Textarea
                      className="min-h-20 resize-none"
                      onChange={(event) =>
                        setDraft({
                          ...draft,
                          materialNote: event.currentTarget.value,
                        })
                      }
                      value={draft.materialNote}
                    />
                  </label>
                </div>
                <DialogFooter>
                  <DialogClose asChild>
                    <Button type="button" variant="outline">
                      取消
                    </Button>
                  </DialogClose>
                  <Button
                    data-testid="ai-study-create-course"
                    disabled={!draft.title.trim() && !draft.goal.trim()}
                    onClick={submitCourse}
                    type="button"
                  >
                    进入课程
                  </Button>
                </DialogFooter>
              </DialogContent>
            </Dialog>
          </div>
        </header>

        <div className="flex min-h-0 flex-1 flex-col">
          <div className="shrink-0 border-b border-border px-6 py-3">
            <Tabs
              onValueChange={(value) => setFilter(value as CourseFilter)}
              value={filter}
            >
              <TabsList className="rounded-md">
                <TabsTrigger value="all">全部</TabsTrigger>
                {STAGE_ORDER.map((stage) => (
                  <TabsTrigger key={stage} value={stage}>
                    {STAGE_META[stage].label}
                  </TabsTrigger>
                ))}
              </TabsList>
            </Tabs>
          </div>

          <ScrollArea className="min-h-0 flex-1">
            <main className="mx-auto w-full max-w-6xl px-6 py-5">
              <div className="overflow-hidden rounded-md border border-border">
                <div className="grid grid-cols-[minmax(0,1fr)_140px_140px_120px] gap-4 border-b border-border bg-muted/30 px-4 py-2 text-xs font-medium text-muted-foreground max-lg:hidden">
                  <span>课程</span>
                  <span>阶段</span>
                  <span>资料</span>
                  <span>继续</span>
                </div>
                {visibleCourses.length > 0 ? (
                  visibleCourses.map((course) => (
                    <CourseRow
                      course={course}
                      key={course.id}
                      onOpenCourse={onOpenCourse}
                    />
                  ))
                ) : (
                  <div className="px-6 py-14 text-center">
                    <Library className="mx-auto size-7 text-muted-foreground" />
                    <div className="mt-3 text-sm font-medium">没有匹配课程</div>
                    <p className="mt-1 text-sm text-muted-foreground">
                      换一个关键词，或新建一门课程让 AI 老师先了解你的目标。
                    </p>
                  </div>
                )}
              </div>
            </main>
          </ScrollArea>
        </div>
      </div>
    </TooltipProvider>
  )
}

function CourseRow({
  course,
  onOpenCourse,
}: {
  course: StudyCourse
  onOpenCourse: (courseId: string) => void
}) {
  const stage = STAGE_META[course.stage]
  const currentNode = getCurrentNode(course)

  return (
    <button
      className="grid w-full cursor-pointer grid-cols-1 gap-3 border-b border-border px-4 py-4 text-left last:border-b-0 hover:bg-muted/35 lg:grid-cols-[minmax(0,1fr)_140px_140px_120px] lg:items-center"
      onClick={() => onOpenCourse(course.id)}
      type="button"
    >
      <div className="min-w-0">
        <div className="flex flex-wrap items-center gap-2">
          <h2 className="truncate text-sm font-semibold">{course.title}</h2>
          <Badge className="rounded-md" variant="secondary">
            {course.subject}
          </Badge>
          <span className="text-xs text-muted-foreground">{course.updatedAt}</span>
        </div>
        <p className="mt-2 line-clamp-1 text-sm text-muted-foreground">
          {course.goal}
        </p>
        <div className="mt-3 grid gap-2 sm:grid-cols-[minmax(0,1fr)_72px] sm:items-center">
          <Progress className="h-1.5" value={course.progress} />
          <span className="text-xs text-muted-foreground">{course.progress}%</span>
        </div>
      </div>
      <div className="flex items-center gap-2 text-sm lg:block">
        <Badge className="rounded-md">{stage.label}</Badge>
        <div className="mt-0 truncate text-xs text-muted-foreground lg:mt-2">
          {currentNode.title}
        </div>
      </div>
      <div className="flex items-center gap-2 text-sm text-muted-foreground">
        <Paperclip className="size-4" />
        {course.materials.length} 份资料
      </div>
      <div className="flex items-center justify-between gap-2 text-sm font-medium lg:justify-end">
        <span className="lg:hidden">进入课程</span>
        <ChevronRight className="size-4" />
      </div>
    </button>
  )
}

function CourseWorkspaceView({
  course,
  canvasOpen,
  onBack,
  onToggleCanvas,
  onUpdateCourse,
}: {
  course: StudyCourse
  canvasOpen: boolean
  onBack: () => void
  onToggleCanvas: () => void
  onUpdateCourse: (updater: (course: StudyCourse) => StudyCourse) => void
}) {
  const [input, setInput] = useState("")

  const sendMessage = (text: string) => {
    const trimmed = text.trim()
    if (!trimmed) {
      return
    }

    onUpdateCourse((current) => recordCourseMessage(current, trimmed))
    setInput("")
  }

  const applyAction = (action: string) => {
    onUpdateCourse((current) => applyTeacherAction(current, action))
  }

  return (
    <TooltipProvider>
      <div
        className={cn(
          "grid min-h-0 flex-1 bg-background text-foreground",
          canvasOpen
            ? "grid-cols-[280px_minmax(0,1fr)_360px]"
            : "grid-cols-[280px_minmax(0,1fr)]"
        )}
      >
        <CourseRail course={course} onAction={applyAction} />

        <main className="flex min-w-0 flex-col border-r border-border">
          <header className="flex shrink-0 items-center justify-between gap-3 border-b border-border px-4 py-3">
            <div className="flex min-w-0 items-center gap-3">
              <Button
                aria-label="返回课程列表"
                className="rounded-md"
                onClick={onBack}
                size="icon-sm"
                type="button"
                variant="ghost"
              >
                <ArrowLeft />
              </Button>
              <div className="min-w-0">
                <div className="flex items-center gap-2 text-xs text-muted-foreground">
                  <span>AI伴学</span>
                  <span>/</span>
                  <span>{STAGE_META[course.stage].title}</span>
                </div>
                <h1 className="truncate text-base font-semibold">{course.title}</h1>
              </div>
            </div>
            <Tooltip>
              <TooltipTrigger asChild>
                <Button
                  aria-label={canvasOpen ? "收起教学画布" : "展开教学画布"}
                  className="rounded-md"
                  onClick={onToggleCanvas}
                  size="icon-sm"
                  type="button"
                  variant="outline"
                >
                  {canvasOpen ? <PanelRightClose /> : <PanelRightOpen />}
                </Button>
              </TooltipTrigger>
              <TooltipContent>
                {canvasOpen ? "收起教学画布" : "展开教学画布"}
              </TooltipContent>
            </Tooltip>
          </header>

          <TeacherConversation
            course={course}
            input={input}
            onAction={applyAction}
            onInputChange={setInput}
            onSend={sendMessage}
          />
        </main>

        {canvasOpen ? (
          <TeachingCanvas course={course} onAction={applyAction} />
        ) : null}
      </div>
    </TooltipProvider>
  )
}

function CourseRail({
  course,
  onAction,
}: {
  course: StudyCourse
  onAction: (action: string) => void
}) {
  return (
    <aside className="flex min-h-0 flex-col border-r border-border bg-muted/15">
      <div className="shrink-0 border-b border-border px-4 py-4">
        <div className="flex items-center gap-2 text-sm font-semibold">
          <Target className="size-4" />
          课程目标
        </div>
        <p className="mt-2 text-sm leading-6 text-muted-foreground">{course.goal}</p>
      </div>

      <ScrollArea className="min-h-0 flex-1">
        <div className="space-y-5 p-4">
          <section>
            <div className="mb-3 text-xs font-medium text-muted-foreground">
              教学阶段
            </div>
            <div className="space-y-2">
              {STAGE_ORDER.map((stage, index) => {
                const meta = STAGE_META[stage]
                const Icon = meta.icon
                const active = course.stage === stage
                const done =
                  STAGE_ORDER.indexOf(course.stage) > index || course.stage === "review"

                return (
                  <div
                    className={cn(
                      "flex items-start gap-3 rounded-md px-2 py-2",
                      active && "bg-background shadow-xs"
                    )}
                    key={stage}
                  >
                    <div
                      className={cn(
                        "mt-0.5 flex size-6 shrink-0 items-center justify-center rounded-md border border-border bg-background",
                        active && "border-primary bg-primary text-primary-foreground"
                      )}
                    >
                      {done && !active ? (
                        <Check className="size-3.5" />
                      ) : (
                        <Icon className="size-3.5" />
                      )}
                    </div>
                    <div className="min-w-0">
                      <div className="text-sm font-medium">{meta.label}</div>
                      <p className="mt-1 line-clamp-2 text-xs leading-5 text-muted-foreground">
                        {meta.description}
                      </p>
                    </div>
                  </div>
                )
              })}
            </div>
          </section>

          <Accordion defaultValue="outline" type="single">
            <AccordionItem value="outline">
              <AccordionTrigger>课程大纲</AccordionTrigger>
              <AccordionContent>
                <div className="space-y-2">
                  {course.outline.map((node) => (
                    <div
                      className={cn(
                        "rounded-md border border-transparent px-3 py-2",
                        node.status === "current" && "border-border bg-background"
                      )}
                      key={node.id}
                    >
                      <div className="flex items-center justify-between gap-2">
                        <span className="text-sm font-medium">{node.title}</span>
                        <NodeStatusBadge status={node.status} />
                      </div>
                      <p className="mt-1 text-xs leading-5 text-muted-foreground">
                        {node.purpose}
                      </p>
                    </div>
                  ))}
                </div>
              </AccordionContent>
            </AccordionItem>
            <AccordionItem value="materials">
              <AccordionTrigger>给老师的资料</AccordionTrigger>
              <AccordionContent>
                <div className="space-y-2">
                  {course.materials.length > 0 ? (
                    course.materials.map((material) => (
                      <div
                        className="rounded-md border border-border bg-background px-3 py-2"
                        key={material.id}
                      >
                        <div className="flex items-center gap-2">
                          <FileText className="size-4 text-muted-foreground" />
                          <span className="truncate text-sm font-medium">
                            {material.name}
                          </span>
                        </div>
                        <div className="mt-2 flex items-center gap-2">
                          <Badge className="rounded-md" variant="secondary">
                            {material.type}
                          </Badge>
                          <span className="truncate text-xs text-muted-foreground">
                            {material.focus}
                          </span>
                        </div>
                      </div>
                    ))
                  ) : (
                    <p className="text-sm leading-6 text-muted-foreground">
                      暂无资料。可以先告诉 AI 老师你手上有什么教材、题目或项目文档。
                    </p>
                  )}
                  <Button
                    className="mt-2 w-full rounded-md"
                    onClick={() => onAction("按资料讲")}
                    size="sm"
                    type="button"
                    variant="outline"
                  >
                    <Paperclip />
                    给老师资料
                  </Button>
                </div>
              </AccordionContent>
            </AccordionItem>
          </Accordion>
        </div>
      </ScrollArea>
    </aside>
  )
}

function TeacherConversation({
  course,
  input,
  onAction,
  onInputChange,
  onSend,
}: {
  course: StudyCourse
  input: string
  onAction: (action: string) => void
  onInputChange: (value: string) => void
  onSend: (text: string) => void
}) {
  const quickReplies = useMemo(() => getQuickReplies(course.stage), [course.stage])

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <Conversation className="min-h-0">
        <ConversationContent className="mx-auto w-full max-w-3xl gap-5 px-5 py-6">
          {course.messages.map((message) => (
            <Message from={message.role} key={message.id}>
              <MessageContent
                className={cn(
                  "leading-7",
                  message.role === "assistant" && "max-w-[720px]"
                )}
              >
                {message.label ? (
                  <div className="mb-1 text-xs font-medium text-muted-foreground">
                    {message.label}
                  </div>
                ) : null}
                <p className="whitespace-pre-wrap">{message.content}</p>
              </MessageContent>
            </Message>
          ))}
        </ConversationContent>
        <ConversationScrollButton />
      </Conversation>

      <footer className="shrink-0 border-t border-border bg-background px-5 py-4">
        <div className="mx-auto max-w-3xl space-y-3">
          <div className="flex flex-wrap gap-2">
            {quickReplies.map((reply) => (
              <Button
                className="h-8 rounded-md"
                key={reply}
                onClick={() => onSend(reply)}
                size="sm"
                type="button"
                variant="outline"
              >
                {reply}
              </Button>
            ))}
          </div>
          <div className="flex flex-wrap gap-2">
            {QUICK_ACTIONS.map((action) => (
              <Button
                className="h-8 rounded-md"
                key={action}
                onClick={() => onAction(action)}
                size="sm"
                type="button"
                variant={action === "确认大纲" ? "default" : "ghost"}
              >
                {action}
              </Button>
            ))}
          </div>
          <PromptInput
            onSubmit={(message) => {
              onSend(message.text)
            }}
          >
            <PromptInputBody>
              <PromptInputTextarea
                className="min-h-20"
                data-testid="ai-study-teacher-input"
                onChange={(event) => onInputChange(event.currentTarget.value)}
                placeholder="把你的回答、疑问、资料重点或想调整的大纲发给 AI 老师"
                value={input}
              />
            </PromptInputBody>
            <PromptInputFooter>
              <div className="text-xs text-muted-foreground">
                AI 老师会根据你的回答调整讲法和下一步内容
              </div>
              <PromptInputSubmit
                className="rounded-md"
                data-testid="ai-study-teacher-send"
                disabled={!input.trim()}
                size="sm"
              >
                <SendHorizontal />
                发给老师
              </PromptInputSubmit>
            </PromptInputFooter>
          </PromptInput>
        </div>
      </footer>
    </div>
  )
}

function TeachingCanvas({
  course,
  onAction,
}: {
  course: StudyCourse
  onAction: (action: string) => void
}) {
  const stage = STAGE_META[course.stage]

  return (
    <aside className="flex min-h-0 flex-col bg-background">
      <header className="shrink-0 border-b border-border px-4 py-3">
        <div className="flex items-center gap-2 text-xs text-muted-foreground">
          <LineChart className="size-4" />
          教学画布
        </div>
        <h2 className="mt-1 truncate text-sm font-semibold">
          {course.artifact.title}
        </h2>
      </header>
      <ScrollArea className="min-h-0 flex-1">
        <div className="space-y-5 p-4">
          <section className="rounded-md border border-border p-4">
            <Badge className="rounded-md">{stage.label}</Badge>
            <p className="mt-3 text-sm leading-6 text-muted-foreground">
              {course.artifact.description}
            </p>
          </section>

          {course.artifact.kind === "derivative" ? (
            <DerivativeBoard />
          ) : course.stage === "outline" ? (
            <OutlineBoard course={course} onAction={onAction} />
          ) : course.stage === "review" ? (
            <ReviewBoard course={course} />
          ) : (
            <IntakeBoard course={course} />
          )}

          <section className="rounded-md border border-border p-4">
            <div className="text-sm font-semibold">老师当前判断</div>
            <p className="mt-2 text-sm leading-6 text-muted-foreground">
              {course.teacherNote}
            </p>
          </section>
        </div>
      </ScrollArea>
    </aside>
  )
}

function IntakeBoard({ course }: { course: StudyCourse }) {
  return (
    <section className="rounded-md border border-border p-4">
      <div className="flex items-center gap-2 text-sm font-semibold">
        <Sparkles className="size-4" />
        教学前需要确认
      </div>
      <div className="mt-4 space-y-3">
        {course.artifact.prompts.map((prompt) => (
          <div className="flex items-start gap-3 text-sm" key={prompt}>
            <span className="mt-1.5 size-1.5 rounded-full bg-foreground" />
            <span className="leading-6 text-muted-foreground">{prompt}</span>
          </div>
        ))}
      </div>
    </section>
  )
}

function OutlineBoard({
  course,
  onAction,
}: {
  course: StudyCourse
  onAction: (action: string) => void
}) {
  return (
    <section className="rounded-md border border-border p-4">
      <div className="text-sm font-semibold">大纲预览</div>
      <div className="mt-4 space-y-3">
        {course.outline.map((node, index) => (
          <div className="grid grid-cols-[28px_minmax(0,1fr)] gap-3" key={node.id}>
            <div className="flex size-7 items-center justify-center rounded-md bg-muted text-xs font-medium">
              {index + 1}
            </div>
            <div>
              <div className="text-sm font-medium">{node.title}</div>
              <p className="mt-1 text-xs leading-5 text-muted-foreground">
                {node.purpose}
              </p>
            </div>
          </div>
        ))}
      </div>
      <Button
        className="mt-4 w-full rounded-md"
        onClick={() => onAction("确认大纲")}
        type="button"
      >
        确认并开始教学
      </Button>
    </section>
  )
}

function DerivativeBoard() {
  return (
    <section className="rounded-md border border-border p-4">
      <div className="flex items-center justify-between gap-3">
        <div className="text-sm font-semibold">变化率可视化</div>
        <Badge className="rounded-md" variant="secondary">
          f'(1)=2
        </Badge>
      </div>
      <div className="mt-4 rounded-md border border-border bg-muted/20 p-3">
        <svg
          aria-label="导数变化率示意图"
          className="h-36 w-full"
          role="img"
          viewBox="0 0 320 140"
        >
          <path d="M20 118 L300 118" stroke="currentColor" strokeOpacity=".22" />
          <path d="M34 126 L34 16" stroke="currentColor" strokeOpacity=".22" />
          <path
            d="M42 104 C88 88 110 32 154 48 C196 62 214 94 286 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="3"
          />
          <path
            d="M92 82 L218 42"
            stroke="currentColor"
            strokeDasharray="5 5"
            strokeOpacity=".55"
            strokeWidth="2"
          />
          <circle cx="154" cy="48" fill="currentColor" r="4" />
          <text fill="currentColor" fontSize="11" x="164" y="42">
            x=1
          </text>
          <text fill="currentColor" fontSize="11" x="84" y="102">
            割线靠近切线
          </text>
        </svg>
      </div>
      <div className="mt-4 space-y-2 text-sm leading-6 text-muted-foreground">
        <p>1. 先看一段区间的平均变化率。</p>
        <p>2. 让区间越来越短，盯住 x=1 附近。</p>
        <p>3. 最后得到切线斜率，也就是这个点的导数值。</p>
      </div>
    </section>
  )
}

function ReviewBoard({ course }: { course: StudyCourse }) {
  return (
    <section className="rounded-md border border-border p-4">
      <div className="text-sm font-semibold">本节掌握记录</div>
      <Separator className="my-3" />
      <div className="space-y-3 text-sm">
        <ReviewLine label="课程" value={course.title} />
        <ReviewLine label="当前节点" value={getCurrentNode(course).title} />
        <ReviewLine label="下一步" value="继续追问薄弱点，再进入下一类题型" />
      </div>
    </section>
  )
}

function ReviewLine({ label, value }: { label: string; value: string }) {
  return (
    <div className="grid grid-cols-[72px_minmax(0,1fr)] gap-3">
      <span className="text-muted-foreground">{label}</span>
      <span className="leading-6">{value}</span>
    </div>
  )
}

function NodeStatusBadge({ status }: { status: SyllabusNode["status"] }) {
  const label =
    status === "done" ? "已讲" : status === "current" ? "正在讲" : "稍后"

  return (
    <Badge
      className="rounded-md"
      variant={status === "current" ? "default" : "secondary"}
    >
      {label}
    </Badge>
  )
}

function createCourse(draft: NewCourseDraft): StudyCourse {
  const title = draft.title.trim() || inferTitle(draft.goal)
  const goal = draft.goal.trim() || "系统学会这个主题"
  const materialNote = draft.materialNote.trim()

  return {
    id: `course-${Date.now()}`,
    title,
    subject: inferSubject(`${title} ${goal}`),
    goal,
    level: draft.level.trim() || "还不确定，需要 AI 老师先诊断",
    stage: "intake",
    progress: 0,
    currentNodeId: "intake",
    updatedAt: "刚刚",
    materials: materialNote
      ? [
          {
            id: "initial-material-note",
            name: "给老师的资料说明",
            type: "备注",
            focus: materialNote,
          },
        ]
      : [],
    outline: [
      {
        id: "intake",
        title: "需求与水平诊断",
        purpose: "确认目标、基础、资料和偏好的教学方式。",
        status: "current",
      },
      {
        id: "core",
        title: "核心概念讲解",
        purpose: "用最小必要概念建立理解框架。",
        status: "next",
      },
      {
        id: "practice",
        title: "练习与批改",
        purpose: "用回答和练习暴露薄弱点。",
        status: "next",
      },
      {
        id: "transfer",
        title: "迁移攻关",
        purpose: "换题、换场景，确认真正掌握。",
        status: "next",
      },
    ],
    messages: [
      {
        id: "new-course-open",
        role: "assistant",
        label: "AI 老师",
        content: `我先不急着上课。你想学「${title}」，目标是：${goal}。\n\n为了像真人老师一样备课，我需要先了解三件事：你现在会到哪里、最终要能完成什么、有没有教材/题目/项目资料。`,
      },
      {
        id: "new-course-question",
        role: "assistant",
        label: "第一个问题",
        content: "你希望学完后能独立完成什么？可以用考试题型、项目成果或真实场景来描述。",
      },
    ],
    artifact: {
      kind: "intake",
      title: "教学前了解",
      description: "AI 老师正在收集备课信息，还不会把大纲一次性定死。",
      prompts: ["目标结果", "当前基础", "已有资料", "偏好讲法"],
    },
    teacherNote: "需要先完成需求和水平诊断，再生成第一版教学大纲。",
  }
}

function recordCourseMessage(course: StudyCourse, text: string): StudyCourse {
  const userMessage: CourseMessage = {
    id: `user-${Date.now()}`,
    role: "user",
    content: text,
  }

  return {
    ...course,
    stage: nextStageAfterReply(course.stage),
    progress: nextProgress(course),
    messages: [
      ...course.messages,
      userMessage,
      {
        id: `assistant-${Date.now()}`,
        role: "assistant",
        label: "AI 老师",
        content: buildTeacherReply(course, text),
      },
    ],
    artifact: nextArtifact(course, text),
    teacherNote: nextTeacherNote(course.stage, text),
    updatedAt: "刚刚",
  }
}

function applyTeacherAction(course: StudyCourse, action: string): StudyCourse {
  if (action === "确认大纲") {
    return {
      ...course,
      stage: "teaching",
      progress: Math.max(course.progress, 24),
      messages: [
        ...course.messages,
        {
          id: `action-${Date.now()}`,
          role: "assistant",
          label: "AI 老师",
          content:
            "好，大纲先按这版执行。现在进入第一节：我会先讲核心概念，再用一个小问题确认你是不是真的理解。",
        },
      ],
      artifact:
        course.subject === "数学"
          ? {
              kind: "derivative",
              title: "瞬时变化率板书",
              description: "把平均变化率逐步压缩到一个点，连接到切线斜率。",
              prompts: ["先看割线", "缩小区间", "得到切线", "解释导数值"],
            }
          : {
              kind: "exercise",
              title: "本节小练习",
              description: "先做一个最小练习，AI 老师根据答案继续讲。",
              prompts: ["写出第一反应", "说出判断依据", "等待 AI 批改"],
            },
      teacherNote: "已进入教学。AI 会根据回答实时调整讲法。",
      updatedAt: "刚刚",
    }
  }

  if (action === "保存本节") {
    return {
      ...course,
      stage: "review",
      progress: Math.min(100, Math.max(course.progress + 12, 36)),
      messages: [
        ...course.messages,
        {
          id: `review-${Date.now()}`,
          role: "assistant",
          label: "本节记录",
          content:
            "我已经记录本节内容：你能跟上主线，但还需要用自己的话解释关键概念。下一次我会先用一个小题检查，再进入下一节点。",
        },
      ],
      artifact: {
        kind: "review",
        title: "本节掌握记录",
        description: "记录已保存，下一次会从真实薄弱点继续。",
        prompts: ["已讲内容", "暴露薄弱点", "下一次入口"],
      },
      teacherNote: "本节记录已生成，下一次从表达不够精确的地方继续。",
      updatedAt: "刚刚",
    }
  }

  const actionReply: Record<string, string> = {
    "更细一点": "我会把步骤拆小。先只做一件事：说清楚这个概念在判断什么，然后再看公式或例题。",
    "换个例子": "换一个例子讲。先用一个更具体、更小的场景，把抽象概念落到你能观察到的变化上。",
    "直接出题": "我们来一道小题。你先写第一反应，不用完整，我会按概念是否准确、步骤是否跳跃来批改。",
    "按资料讲": "可以。请把教材、讲义、题目或项目资料的重点发给我。我会优先按资料顺序讲，但会补上你缺的前置概念。",
  }

  return {
    ...course,
    messages: [
      ...course.messages,
      {
        id: `action-${Date.now()}`,
        role: "assistant",
        label: action,
        content: actionReply[action] ?? "收到，我会调整接下来的讲法。",
      },
    ],
    teacherNote:
      action === "按资料讲"
        ? "等待用户补充资料重点，之后可按资料生成更贴合的大纲。"
        : "AI 已按用户操作调整讲解粒度。",
    updatedAt: "刚刚",
  }
}

function buildTeacherReply(course: StudyCourse, text: string) {
  if (course.stage === "intake") {
    return `我收到你的补充：「${text}」。\n\n基于这个目标，我会先拟一个教学大纲，但还会保留调整空间。下一步请看右侧大纲预览：如果你觉得太快、太浅，或者需要严格按资料讲，可以直接告诉我。`
  }

  if (course.stage === "outline") {
    return `这个调整有价值。我会把「${text}」纳入大纲：先补必要前置，再进入核心讲解，最后用练习确认迁移能力。\n\n如果这版可以，就点“确认大纲”，我们开始第一节。`
  }

  if (course.stage === "teaching") {
    return `你的回答里已经有关键方向。现在我按老师批改的方式指出一点：你需要把“现象”和“判断依据”连起来。\n\n请再补一句：为什么这个判断在当前题目或场景里成立？`
  }

  return `我把这条补充写入记录：「${text}」。下一次继续时，会先从这里检查。`
}

function nextStageAfterReply(stage: CourseStage): CourseStage {
  if (stage === "intake") {
    return "outline"
  }

  return stage
}

function nextProgress(course: StudyCourse) {
  if (course.stage === "intake") {
    return Math.max(course.progress, 12)
  }

  if (course.stage === "outline") {
    return Math.max(course.progress, 20)
  }

  if (course.stage === "teaching") {
    return Math.min(92, course.progress + 6)
  }

  return course.progress
}

function nextArtifact(course: StudyCourse, text: string): TeachingArtifact {
  if (course.stage === "intake") {
    return {
      kind: "outline",
      title: "第一版教学大纲",
      description: `已根据「${text}」生成可调整大纲。`,
      prompts: ["检查目标是否准确", "调整章节深度", "确认开始教学"],
    }
  }

  return course.artifact
}

function nextTeacherNote(stage: CourseStage, text: string) {
  if (stage === "intake") {
    return `已获得关键需求：“${text}”。下一步是确认大纲。`
  }

  if (stage === "outline") {
    return "用户正在调整大纲，AI 需要保持可讨论，而不是直接开讲。"
  }

  if (stage === "teaching") {
    return "已根据用户回答发现表达和判断依据之间还需要连接。"
  }

  return "记录已更新。"
}

function getQuickReplies(stage: CourseStage) {
  if (stage === "intake") {
    return ["目标是考试压轴题", "我几乎从零开始", "我有讲义和错题", "先诊断薄弱点"]
  }

  if (stage === "outline") {
    return ["大纲再细一点", "多安排例题", "按资料顺序讲", "可以开始教学"]
  }

  if (stage === "teaching") {
    return ["我的理解是...", "这里为什么成立？", "给我一道类似题", "我想看图像解释"]
  }

  return ["继续下一节", "再检查一次", "生成复习题", "回到当前概念"]
}

function getCurrentNode(course: StudyCourse) {
  return (
    course.outline.find((node) => node.id === course.currentNodeId) ??
    course.outline.find((node) => node.status === "current") ??
    course.outline[0]
  )
}

function inferTitle(goal: string) {
  const trimmed = goal
    .replace(/^我想/, "")
    .replace(/^想/, "")
    .replace(/^系统学习/, "")
    .replace(/^系统学/, "")
    .replace(/[，。,.].*$/, "")
    .trim()

  if (!trimmed) {
    return "新的 AI伴学课程"
  }

  return trimmed.length > 14 ? `${trimmed.slice(0, 14)}...` : trimmed
}

function inferSubject(text: string) {
  if (/导数|函数|数学|几何|物理|化学/.test(text)) {
    return "数学"
  }

  if (/Python|React|API|代码|编程|开发/i.test(text)) {
    return "编程"
  }

  if (/英语|日语|口语|写作|汇报|语言/.test(text)) {
    return "语言"
  }

  return "自定义"
}
