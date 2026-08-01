export type WorkspaceAppId = "document" | "study"
export type WorkspacePlannedAppId = "sheet" | "deck" | "contract"
export type WorkspaceAppStatus = "ready" | "soon"
export type WorkspaceAppCategory =
  | "document"
  | "study"
  | "data"
  | "deck"
  | "legal"
export type WorkspaceAppIconKey =
  | "document"
  | "study"
  | "sheet"
  | "deck"
  | "contract"

export type WorkspaceApp = {
  id: WorkspaceAppId | WorkspacePlannedAppId
  title: string
  description: string
  category: WorkspaceAppCategory
  categoryLabel: string
  outputLabel: string
  status: WorkspaceAppStatus
  iconKey: WorkspaceAppIconKey
}

export const WORKSPACE_APPS: WorkspaceApp[] = [
  {
    id: "study",
    title: "AI伴学",
    description: "输入想学的主题，让 AI 现场讲解、追问、出题和批改。",
    category: "study",
    categoryLabel: "学习",
    outputLabel: "AI 私教 / 掌握记录 / 知识结构",
    status: "ready",
    iconKey: "study",
  },
  {
    id: "document",
    title: "AI 文档",
    description: "围绕资料收集、草稿生成、润色检查和 Word 预览导出构建的文档工作台。",
    category: "document",
    categoryLabel: "文档",
    outputLabel: "DOCX / oojson",
    status: "ready",
    iconKey: "document",
  },
  {
    id: "sheet",
    title: "AI 表格",
    description: "面向数据整理、公式生成、批量校验和结构化表格产出的工作区。",
    category: "data",
    categoryLabel: "数据",
    outputLabel: "XLSX / CSV",
    status: "soon",
    iconKey: "sheet",
  },
  {
    id: "deck",
    title: "AI 演示",
    description: "把主题、资料和讲述节奏编排为可审阅的 PPT 生成流程。",
    category: "deck",
    categoryLabel: "演示",
    outputLabel: "PPTX",
    status: "soon",
    iconKey: "deck",
  },
  {
    id: "contract",
    title: "合同审查",
    description: "针对合同条款抽取、风险标注、修订建议和复核记录的专用应用。",
    category: "legal",
    categoryLabel: "法务",
    outputLabel: "DOCX / 批注",
    status: "soon",
    iconKey: "contract",
  },
]

export const APP_CATEGORY_TABS: Array<{
  id: "all" | WorkspaceAppCategory
  label: string
}> = [
  { id: "all", label: "全部" },
  { id: "study", label: "学习" },
  { id: "document", label: "文档" },
  { id: "data", label: "数据" },
  { id: "deck", label: "演示" },
  { id: "legal", label: "法务" },
]
