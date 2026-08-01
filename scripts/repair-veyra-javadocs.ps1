$ErrorActionPreference = "Stop"
$workspace = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$sourceRoot = Join-Path $workspace "src/main/java/cn/ayice/veyra"
$utf8Strict = [System.Text.UTF8Encoding]::new($false, $true)
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)

$subjects = @{
    "agent"="智能体"; "ai"="模型消息"; "all"="全部内容"; "allowed"="允许项"; "approval"="审批";
    "approved"="已批准请求"; "assistant"="助手消息"; "auto"="自动"; "base"="基础"; "boundary"="压缩边界";
    "calls"="调用"; "chat"="聊天"; "code"="错误码"; "command"="命令"; "compact"="压缩"; "config"="配置";
    "content"="内容"; "context"="上下文"; "current"="当前值"; "description"="描述"; "dir"="目录";
    "directories"="目录集合"; "directory"="目录"; "edit"="编辑"; "enabled"="启用状态"; "encoding"="字符编码";
    "entries"="条目"; "error"="错误"; "event"="事件"; "executor"="执行器"; "failure"="失败状态";
    "file"="文件"; "files"="文件集合"; "format"="格式"; "frontmatter"="Frontmatter"; "full"="完整";
    "glob"="Glob 模式"; "history"="对话历史"; "include"="包含指令"; "index"="索引"; "input"="输入";
    "items"="条目"; "last"="最近一个"; "limit"="限制"; "line"="行"; "lines"="文本行"; "marker"="提取标记";
    "matches"="匹配结果"; "max"="最大"; "memory"="记忆"; "message"="消息"; "messages"="消息列表";
    "micro"="微压缩"; "mode"="权限模式"; "model"="模型"; "name"="名称"; "notification"="任务通知";
    "options"="选项"; "output"="输出"; "path"="路径"; "paths"="路径集合"; "pattern"="匹配模式";
    "permission"="权限"; "project"="项目"; "prompt"="提示词"; "quotes"="引号"; "reason"="原因";
    "request"="请求"; "requests"="请求列表"; "response"="响应"; "result"="结果"; "ripgrep"="ripgrep";
    "root"="根目录"; "rule"="权限规则"; "rules"="权限规则集合"; "run"="运行"; "runtime"="运行时";
    "session"="会话"; "sessions"="会话列表"; "slash"="斜杠命令"; "state"="状态"; "status"="状态";
    "string"="字符串"; "summary"="摘要"; "task"="任务"; "text"="文本"; "token"="token"; "tokens"="token 数";
    "tool"="工具"; "transcript"="会话转录"; "type"="类型"; "user"="用户消息"; "value"="值";
    "window"="窗口"; "workspace"="工作区"; "write"="写入"; "read"="读取"; "only"="专用";
    "profile"="工具配置集"; "spec"="工具规范"; "specs"="工具规范列表"; "temperature"="模型温度";
    "timeout"="超时时间"; "rounds"="最大轮数"; "keys"="键集合"; "hash"="哈希"; "fingerprint"="指纹";
    "separator"="分隔符"; "relative"="相对"; "absolute"="绝对"; "normalized"="规范化"; "actual"="实际";
    "existing"="已有"; "readable"="可读"; "danger"="危险阈值"; "ratio"="比例"; "buffer"="缓冲区";
    "source"="来源"; "behavior"="行为"; "working"="工作"; "total"="总"; "minimum"="最小";
    "between"="间隔"; "update"="更新"; "updates"="更新项"; "record"="记录"; "records"="记录集合";
    "subscriber"="订阅者"; "subscribers"="订阅者集合"; "topic"="主题记忆"; "snapshot"="快照";
    "restored"="恢复内容"; "localized"="本地化"; "payload"="事件载荷"; "stream"="数据流";
    "lineendings"="换行风格"; "lineending"="换行风格"; "size"="大小"; "count"="数量";
    "prefix"="前缀"; "suffix"="后缀"; "target"="目标"; "fields"="字段"; "field"="字段"
}

$exactMethods = @{
    "listSessions"="返回持久化会话摘要列表，并保持最近更新的会话优先。"
    "getHistory"="返回当前对话历史的防御性副本，调用方修改不会影响循环内部状态。"
    "getApiKey"="返回模型服务 API Key；调用方不得将其写入日志或响应。"
    "getAllSpecs"="返回按注册顺序排列的全部模型工具规范。"
    "getDescriptions"="返回工具名到模型可见描述的只读映射。"
    "pendingApprovals"="返回当前会话尚未处理的工具审批快照。"
    "expandHome"="将路径开头的波浪号展开为当前用户主目录。"
    "detectEncoding"="根据文件字节标记和内容特征识别字符编码。"
    "detectLineEndings"="识别文本使用的主换行风格，供写回时保持原格式。"
    "detectOutputFormat"="根据命令参数判断调用方请求的结构化输出格式。"
    "globToRegex"="将权限规则中的 Glob 表达式转换为完整匹配的正则表达式。"
    "adjustKeepIndexForToolPairs"="向前调整保留起点，避免截断 tool-use 与 tool-result 配对。"
    "ensureToolResultPairing"="修复发送给模型的工具调用与工具结果配对关系。"
    "afterLastBoundary"="返回最近压缩边界之后仍应发送给模型的消息。"
    "autocompactBuffer"="计算触发自动压缩前必须预留的 token 缓冲量。"
    "safeErrorMessage"="提取根异常的可展示消息，空消息退回异常类型名称。"
    "safeFileName"="移除文件名中的非法字符并生成可下载的安全名称。"
    "safeResolve"="在指定根目录内解析相对路径，并拒绝越界结果。"
    "canonicalWorkspace"="返回工作区真实路径；无法解析时退回规范化绝对路径。"
    "success"="创建表示成功且携带结果数据的返回对象。"
    "failure"="创建表示失败且携带稳定错误信息的返回对象。"
    "error"="创建工具执行失败结果。"
    "ok"="创建输入校验通过结果。"
    "invalid"="创建携带失败原因的输入校验结果。"
    "allow"="创建允许执行的权限决定。"
    "ask"="创建需要用户确认的权限决定。"
    "deny"="创建拒绝执行的权限决定。"
    "accepted"="创建包含 runId 的已受理 Run 结果。"
    "rejected"="创建未受理 Run 或工具授权结果。"
    "current"="返回当前不可变权限上下文快照。"
    "messages"="返回状态中消息列表的防御性副本。"
    "approvedRequests"="返回已批准工具请求列表的防御性副本。"
    "readOnly"="返回只允许读取和检索工具的配置集。"
    "general"="返回子 Agent 通用工具与权限策略。"
    "memory"="返回长期记忆任务专用的工具或权限策略。"
    "plan"="返回计划类子 Agent 可使用的只读工具配置集。"
    "explore"="返回代码探索子 Agent 可使用的工具配置集。"
    "verify"="返回验证类子 Agent 可使用的工具配置集。"
    "all"="返回包含全部已注册工具的配置集。"
    "mainAgent"="返回主 Agent 使用的工具执行策略。"
    "runCompleted"="发布 Run 正常完成事件及最终文本。"
    "runFailed"="发布 Run 失败事件及安全错误摘要。"
    "assistantToken"="发布助手正文增量 token 事件。"
    "assistantCompleted"="发布一轮完整助手消息事件。"
    "toolStarted"="发布工具已进入授权阶段的事件。"
    "toolCompleted"="发布工具成功返回结果的事件。"
    "toolFailed"="发布工具执行异常事件。"
    "toolRejected"="发布工具调用被权限系统拒绝的事件。"
    "userMessage"="发布用户消息进入 Agent 循环的事件。"
    "contextWarning"="在 token 接近限制时发布上下文容量告警。"
    "executeAgent"="把输入交给当前会话的主 Agent 循环执行。"
    "executeChat"="把输入交给当前会话的无工具 Chat 循环执行。"
    "appConfig"="读取指定配置文件并创建全局 Veyra 配置 Bean。"
    "agentRunExecutor"="创建处理会话 Run 的有界共享线程池。"
    "agentTaskExecutor"="创建处理子 Agent 任务的有界共享线程池。"
    "agentIoExecutor"="创建处理工具 I/O 和生命周期任务的有界共享线程池。"
    "transcriptStore"="创建基于项目隔离路径的 JSONL 转录存储。"
    "transcriptRestorer"="创建将持久化条目恢复为模型消息的恢复器。"
    "sessionRuntimeFactory"="创建共享模型、记忆和受管线程池的会话运行时工厂。"
    "sessionRegistry"="创建负责活动会话恢复和关闭的注册表。"
    "runCoordinator"="创建 Agent 与 Chat 共用的 Run 生命周期协调器。"
    "runtimeHost"="创建控制面访问活动运行时的唯一入口。"
    "documentExportService"="创建 Word 文档导出服务。"
    "corsConfigurer"="创建仅允许本地 Tauri 客户端访问的 CORS 配置。"
    "agentRequestLoggingFilter"="创建注入 requestId 并记录耗时的 HTTP 日志过滤器。"
    "health"="返回后端进程可接收请求的健康状态。"
    "streamingChatOnly"="发起不携带工具规范的流式模型调用。"
    "managedExecutor"="创建具有固定线程数、有界队列和统一命名规则的受管线程池。"
    "get"="按配置键读取原始值；配置文件未提供时回退到环境变量或默认值。"
    "str"="读取字符串配置，并在缺失时使用默认值。"
    "num"="读取必需整数配置，并校验其取值范围。"
    "optionalNum"="读取可选整数配置；未配置时返回空值。"
    "bool"="读取布尔配置，并兼容字符串和布尔字面量。"
    "dbl"="读取小数配置，并校验其取值范围。"
    "envOr"="优先读取环境变量，缺失时返回给定默认值。"
    "session"="返回指定会话的当前状态或 API 表示。"
    "transcript"="返回指定会话按写入顺序排列的转录记录。"
    "slashCommandOptions"="返回与查询文本匹配的斜杠命令补全选项。"
    "logs"="建立后端日志 SSE 流并持续写出日志事件。"
    "exportWord"="将标题和 Markdown 文本导出为可下载的 Word 文档。"
    "text"="将可空文本转换为非空字符串。"
    "status"="返回当前异常或任务记录的状态。"
    "code"="返回当前异常携带的稳定错误码。"
    "defaultCode"="根据 HTTP 状态选择默认业务错误码。"
    "options"="返回与查询条件匹配的命令选项。"
    "joinText"="合并消息中的文本内容，忽略非文本片段。"
    "estimate"="估算消息或消息集合占用的 token 数。"
    "fitSystemMessage"="将恢复内容裁剪到预算内并封装为系统消息。"
    "register"="注册组件并保持后续构建顺序稳定。"
    "cacheBreak"="指示当前提示词片段后是否建立模型缓存边界。"
    "defaults"="使用默认用户目录和给定工作区创建加载器。"
    "load"="按优先级加载当前工作区可用的指令或记忆内容。"
    "dispatch"="查找并执行匹配的命令；无法识别时返回空结果。"
    "subscribe"="注册订阅者并返回用于解除订阅的关闭句柄。"
    "publish"="将事件发布给当前全部有效订阅者。"
    "record"="将消息转换为 transcript 条目并追加到持久化存储。"
    "restore"="将持久化 transcript 条目恢复为合法模型消息序列。"
    "run"="执行当前运行策略并返回最终结果。"
    "process"="处理一条用户输入并返回本次循环最终文本。"
    "reset"="清空当前组件维护的临时状态。"
    "suggest"="按查询文本返回排序后的命令建议。"
    "supports"="当当前命令实现支持给定输入时返回 true。"
    "requiredString"="读取必需字符串字段，缺失或空白时返回校验失败。"
    "optionalString"="读取可选字符串字段，缺失时返回空值。"
    "optionalBoolean"="读取可选布尔字段，并拒绝非布尔值。"
    "optionalInteger"="读取可选整数字段，并拒绝非整数值。"
    "optionalNonNegativeInteger"="读取可选非负整数字段，并校验下界。"
    "parseInput"="解析工具 JSON 参数并返回经过校验的输入对象。"
    "parseAndValidateInput"="解析工具 JSON 参数，同时完成必填字段和路径校验。"
    "normalize"="将输入转换为模块内部使用的统一形式。"
    "truncate"="按最大长度截断文本，并保留明确的截断标记。"
    "escapeXml"="转义任务通知中的 XML 特殊字符。"
    "preview"="生成长度受限、适合事件展示的输出预览。"
    "notification"="根据任务终态创建一次性主 Agent 通知。"
    "eventPayload"="构建任务事件使用的稳定字段集合。"
    "eventTypeFor"="将任务终态映射为对应的事件类型。"
    "nextSubagentName"="按循环序列分配下一个可读子 Agent 名称。"
    "runningCount"="返回当前仍处于 RUNNING 状态的任务数量。"
}

# 这些方法名本身不足以表达契约，必须给出调用方真正需要知道的语义。
$semanticMethods = @{
    "decideApproval"="校验审批选项并完成指定待审批工具调用，返回最新审批状态。"
    "decide"="根据工具、参数和当前权限上下文计算允许、询问或拒绝决定。"
    "isAutoCompactEnabled"="返回是否启用达到阈值后的自动上下文压缩。"
    "isMicroCompactEnabled"="返回是否启用仅裁剪旧工具结果的微压缩。"
    "isPostCompactRestoreEnabled"="返回完整压缩后是否恢复必要的系统提示词片段。"
    "isCJK"="判断字符是否属于按单字符估算 token 的中日韩文字区段。"
    "isBoundary"="判断消息是否为任意一种上下文压缩边界。"
    "isFullBoundary"="判断消息是否为替换早期历史的完整压缩边界。"
    "isMicroBoundary"="判断消息是否为仅压缩工具结果的微压缩边界。"
    "clearSystemPromptSections"="清空已注册的系统提示词片段，供运行时重新装配。"
    "isCompactableTool"="判断工具结果是否允许在微压缩时替换为占位摘要。"
    "evaluate"="估算当前历史的 token 使用量并返回压缩阈值状态。"
    "clearCache"="清除已构建的系统提示词缓存，使后续请求重新生成。"
    "instructionFiles"="按用户级到项目级的覆盖顺序返回候选指令文件。"
    "ruleFiles"="返回当前工作区可参与权限判断的规则文件集合。"
    "matchesPaths"="判断当前工作区相对路径是否命中任一 Frontmatter 路径约束。"
    "matchesGlob"="使用项目路径语义判断相对路径是否匹配给定 Glob。"
    "isAllowedInclude"="判断 include 目标是否位于允许读取的用户目录或工作区内。"
    "includeTarget"="从 include 指令中提取并去除引号后的目标路径。"
    "unquote"="去除一对匹配的单引号或双引号，其他文本保持不变。"
    "isEnabled"="返回当前项目的长期记忆功能是否启用。"
    "list"="读取记忆索引并返回全部可用记忆条目。"
    "delete"="删除名称或文件名匹配的记忆文件，并同步重建索引。"
    "rebuildIndexes"="重新扫描记忆文件并原子更新索引内容。"
    "indexPath"="返回当前项目记忆目录中的索引文件路径。"
    "disabledMarker"="返回用于标记当前项目禁用记忆功能的文件路径。"
    "sanitizeProjectRoot"="将项目根目录转换为稳定的目录名，并附加哈希避免冲突。"
    "slug"="将记忆名称转换为可安全用于文件名的短标识。"
    "shortHash"="返回输入文本 SHA-256 摘要的短十六进制前缀。"
    "frontmatterValue"="读取 Frontmatter 中指定键的值；键不存在时返回 null。"
    "recall"="按关键词相关度检索记忆，并按得分从高到低返回。"
    "score"="计算查询词在记忆标题、描述和正文中的加权匹配分数。"
    "minimumTokensToInit"="返回首次生成会话记忆前要求的最小上下文 token 数。"
    "minimumTokensBetweenUpdate"="返回两次会话记忆更新之间要求的最小 token 增量。"
    "maxTotalTokens"="返回会话记忆提示词允许使用的最大 token 总量。"
    "lastExtractedIndex"="返回最近一次写入会话记忆所覆盖的消息索引；尚未提取时为 -1。"
    "shouldExtract"="根据初始化阈值、token 增量和工具调用数判断是否应更新会话记忆。"
    "extract"="调用模型更新会话记忆并持久化覆盖位置；失败只记录日志，不中断主循环。"
    "hasContent"="判断当前会话是否已有可恢复的记忆内容。"
    "markerPath"="返回记录最近一次会话记忆覆盖位置的标记文件路径。"
    "fingerprint"="为消息生成稳定指纹，用于恢复后重新定位已摘要边界。"
    "projectDir"="返回当前工作区隔离后的 transcript 存储目录。"
    "transcriptPath"="校验会话标识并返回其 JSONL transcript 文件路径。"
    "sanitizeWorkspace"="将工作区路径转换为稳定目录名，并附加哈希避免同名冲突。"
    "user"="创建带当前时间戳的用户 transcript 条目。"
    "assistant"="创建带当前时间戳的助手 transcript 条目。"
    "timestampInstant"="将持久化时间戳解析为 Instant；格式非法时返回最早时间。"
    "userText"="提取用户消息的纯文本内容，忽略非文本内容块。"
    "bindRun"="将后续会话事件关联到当前 runId。"
    "removeSubscriber"="注销事件订阅者，后续发布不再向其发送事件。"
    "nextSeq"="原子递增并返回当前会话的下一事件序号。"
    "global"="返回进程级日志总线单例。"
    "recentSnapshot"="返回最近日志缓冲区的线程安全快照。"
    "matches"="判断斜杠命令的名称或描述是否匹配规范化查询文本。"
    "contains"="以忽略大小写的方式判断候选文本是否包含查询文本。"
    "completed"="创建已在控制面完成、无需进入 Agent 循环的命令结果。"
    "builtIns"="创建包含内置记忆命令的斜杠命令分发器。"
    "drainTaskNotifications"="等待当前子任务到达同步点并一次性取走全部完成通知。"
    "initial"="用恢复后的消息创建尚未开始模型调用的初始循环状态。"
    "state"="返回循环状态的稳定字符串表示，供事件和日志使用。"
    "isTerminal"="判断循环是否已完成、失败或取消，不能再进入下一轮。"
    "isActive"="判断循环是否仍允许准备或执行下一轮模型调用。"
    "transitionReason"="返回最近一次循环状态迁移的原因说明。"
    "turnCount"="返回当前运行已完成的模型轮次数量。"
    "failureCount"="返回当前运行连续模型调用失败的次数。"
    "request"="返回当前轮已准备好的不可变模型请求。"
    "aiMessage"="返回当前轮模型响应中的助手消息。"
    "append"="返回追加给定消息后的新状态或新列表，不修改原消息集合。"
    "isMemoryWriteRequest"="判断工具调用是否会写入长期记忆并需要生命周期跟踪。"
    "limit"="将文本限制在最大字符数内，并在截断时添加省略标记。"
    "await"="在超时范围内等待模型结果，并保留取消和根异常语义。"
    "rootCause"="沿 cause 链返回最内层异常，处理循环引用以避免死循环。"
    "isUserAllowedType"="判断用户传入的子 Agent 类型是否在公开白名单中。"
    "memoryExtraction"="创建仅允许执行记忆提取所需工具的子 Agent 配置。"
    "result"="将子 Agent 终态、输出和错误封装为统一运行结果。"
    "partialOrError"="优先返回已生成的部分输出；没有输出时返回安全错误摘要。"
    "localizedPrompt"="为子 Agent 提示词补充与主会话一致的语言要求。"
    "allowed"="返回本次工具授权是否允许继续执行。"
    "authorizationDecided"="在权限策略完成初步授权判断后接收回调。"
    "permissionRequested"="在工具调用需要用户审批时接收回调。"
    "permissionResolved"="在用户提交审批选择后接收回调。"
    "canAskPermission"="返回当前执行表面是否支持暂停并请求用户审批。"
    "includeDecisionReasonInApproval"="返回审批事件是否应包含权限策略给出的原因。"
    "deniedApprovalReason"="将拒绝决定转换为可写入审批结果的稳定原因。"
    "emptySuccessContent"="返回工具成功但无输出时写入模型上下文的占位内容。"
    "treatBlankContentAsEmpty"="返回空白工具输出是否按无输出结果处理。"
    "named"="按给定名称和工具名集合创建不可变工具配置集。"
    "isReadOnly"="返回该工具是否只执行不会修改外部状态的操作。"
    "checkCommandRules"="按命令前缀权限规则计算 Bash 调用的执行决定。"
    "isReadOnlyCommand"="判断命令是否属于允许直接执行的只读命令集合。"
    "isReadOnlyGitCommand"="判断 Git 子命令是否只读取仓库状态。"
    "containsOutputOption"="判断命令参数是否已包含调用方指定的输出格式选项。"
    "firstToken"="解析命令的首个可执行 token，供只读规则匹配。"
    "containsAnyToken"="判断命令文本是否包含任一危险控制 token。"
    "validateEditPreconditions"="校验文件存在、已读取且内容未在读取后变化，否则拒绝编辑。"
    "mapNormalizedMatchBackToFile"="把规范化文本中的匹配区间映射回原文件的精确文本。"
    "preserveQuoteStyle"="将替换文本中的直引号调整为原匹配文本使用的弯引号风格。"
    "applyCurlyDoubleQuotes"="按上下文把直双引号转换为对应的左、右弯引号。"
    "applyCurlySingleQuotes"="按上下文把直单引号转换为对应的左、右弯引号。"
    "isOpeningContext"="根据前置字符判断当前位置的引号是否为起始引号。"
    "applyEdit"="按单次或全部替换策略修改文本，并拒绝找不到或不唯一的匹配。"
    "firstChangedLine"="计算首次匹配文本所在的 1 基行号，供编辑结果展示。"
    "optionalInt"="读取可选整数字段；缺失时返回默认值，类型错误时拒绝输入。"
    "validateWritePreconditions"="校验覆盖目标已读取且未发生并发修改，否则拒绝写入。"
    "matchesPattern"="判断候选文件的相对路径或文件名是否匹配编译后的 Glob。"
    "compileGlob"="将 Glob 语法编译为兼容跨平台路径分隔符的正则表达式。"
    "isAbsolutePattern"="判断 Glob 是否以 Unix 根目录、UNC 或 Windows 盘符开头。"
    "lastSeparatorIndex"="返回路径中最后一个正斜杠或反斜杠的位置。"
    "displayPath"="根据搜索根和用户输入选择稳定、可读的结果展示路径。"
    "applyHeadLimit"="按偏移量和上限截取结果，并标记是否仍有未返回项。"
    "relativizeContentLine"="把 ripgrep 内容模式输出中的绝对路径转换为工作区相对路径。"
    "relativizeCountLine"="把 ripgrep 计数模式输出中的绝对路径转换为工作区相对路径。"
    "firstOutputSeparator"="定位 ripgrep 输出中路径字段与后续字段之间的分隔符。"
    "looksLikeWindowsDrivePath"="判断输出行是否以 Windows 盘符绝对路径开头。"
    "isDigits"="判断非空字符串是否完全由十进制数字组成。"
    "ripgrepErrorMessage"="从进程输出中提取适合返回给调用方的 ripgrep 错误摘要。"
    "ripgrepStartError"="将 ripgrep 启动异常转换为包含安装提示的稳定错误消息。"
    "plural"="根据数量选择英文单数或复数形式。"
    "allowsTool"="判断工具名是否位于该 Agent 的允许集合中。"
    "mode"="设置或返回当前权限模式。"
    "allowedDirectories"="设置或返回允许工具访问的规范化目录集合。"
    "workingDir"="设置或返回权限规则解析使用的工作目录。"
    "rules"="返回按声明顺序保存的不可变权限规则集合。"
    "ruleContentMatches"="判断规则内容是否精确匹配或覆盖给定工具参数。"
    "isWithinRuleDirectory"="判断路径参数是否位于目录型权限规则覆盖范围内。"
    "isWithinDirectory"="通过规范化绝对路径判断候选路径是否位于目录内。"
    "containsPath"="判断目录集合是否已包含规范化后的候选路径。"
    "update"="原子替换权限上下文并返回更新后的不可变快照。"
    "configValue"="返回写入配置文件时使用的权限模式值。"
    "allowsToolExecutionByDefault"="返回该模式是否默认允许工具执行。"
    "allowsReadOnlyByDefault"="返回该模式是否默认允许只读工具执行。"
    "shouldAskByDefault"="返回该模式是否应对未命中规则的工具调用请求审批。"
    "source"="设置权限规则来源并返回当前构建器。"
    "behavior"="设置权限规则行为并返回当前构建器。"
    "content"="设置权限规则匹配内容并返回当前构建器。"
    "ruleContent"="返回去除工具名前缀后的规则匹配内容。"
    "isToolWideRule"="判断规则是否覆盖某工具的全部调用而非特定参数。"
    "checkReadPathPermission"="校验读取路径边界，并结合规则和权限模式返回执行决定。"
    "checkWritePathPermission"="校验写入路径边界，并结合规则和权限模式返回执行决定。"
    "containsPathTraversal"="判断路径是否包含可逃逸当前目录的父级遍历片段。"
    "isUncPath"="判断路径是否为 UNC 网络共享路径。"
    "hasSuspiciousWindowsPathPattern"="判断路径是否包含设备路径、数据流或保留设备名。"
    "hasAlternateDataStream"="判断 Windows 路径是否使用 NTFS 备用数据流语法。"
    "isDosDeviceName"="判断路径段是否为 Windows 保留的 DOS 设备名。"
    "generateForSessionAllow"="根据已批准调用生成仅在当前会话生效的最小权限更新。"
    "generateWriteSessionUpdates"="为写工具生成目标目录和必要读取权限的会话更新。"
    "isEditTool"="判断工具名是否属于文件编辑工具。"
    "isWriteTool"="判断工具名是否属于创建或覆盖文件的工具。"
    "isReadTool"="判断工具名是否属于文件读取或搜索工具。"
    "textField"="读取 JSON 文本字段；缺失、null 或非文本时返回 null。"
    "directoryRule"="把规范化目录转换为权限系统使用的目录规则。"
    "commandPrefixRule"="从命令中提取稳定前缀并生成 Bash 权限规则。"
    "staticBaseFromGlob"="提取 Glob 首个通配符之前的静态目录并基于工作区解析。"
    "firstWildcard"="返回 Glob 中首个通配符的位置；不存在时返回 -1。"
    "fullReadPaths"="返回已完整读取文件路径的线程安全快照。"
    "statusLabel"="返回 Todo 状态对应的稳定文本标签。"
    "statusIcon"="返回 Todo 状态对应的终端显示符号。"
    "hasOpenItems"="判断 Todo 列表中是否仍有待处理或进行中的条目。"
    "awaitRunningAndDrain"="等待本轮已启动的全部子 Agent 结束，再一次性返回完成通知。"
    "hasTask"="判断给定标识对应的任务是否存在。"
    "hasRunning"="判断是否仍有尚未结束的子 Agent 任务。"
    "summaryVerb"="把任务终态映射为通知摘要中使用的动词。"
    "check"="返回后台任务当前状态和增量输出，但不移除任务记录。"
    "drain"="原子取走所有待发送的后台任务完成通知。"
    "wireValue"="返回任务状态在事件和接口中的稳定字符串值。"
    "loadFromFile"="读取 YAML 配置文件并构建配置对象；文件缺失或字段非法时使用受控默认值。"
    "getAppName"="返回模型请求中用于标识客户端的应用名称。"
    "getAppDescription"="返回系统提示词中使用的应用描述。"
    "getBaseUrl"="返回兼容 OpenAI 协议的模型服务基础地址。"
    "getMaxTokens"="返回单次模型响应允许生成的最大 token 数。"
    "getModelTimeoutSeconds"="返回等待单次模型调用完成的超时秒数。"
    "getMaxContextTokens"="返回模型上下文窗口允许使用的最大 token 数。"
    "getAutoCompactWindowOverride"="返回自动压缩窗口覆盖值；未配置时由模型上下文大小推导。"
    "updateSettings"="校验并更新会话权限设置，返回更新后的会话状态。"
    "normalizeForAPI"="合并连续同角色消息并修复工具调用配对，生成模型 API 可接受的历史。"
    "withoutFullBoundaries"="返回移除完整压缩边界后的消息副本，保留普通消息和微压缩边界。"
    "estimateTokens"="估算当前消息集合占用的 token 数。"
    "formatForSummary"="把待压缩消息转换为带角色标记、适合摘要模型读取的文本。"
    "truncateToTokenBudget"="按 token 预算裁剪恢复文本，优先保留靠后的最新内容。"
    "readFromDisk"="读取可选恢复文件；文件不存在或读取失败时返回空内容。"
    "formatSection"="仅在正文非空时添加 Markdown 标题并生成系统提示词片段。"
    "buildMemoryInstructions"="根据当前记忆配置生成模型可执行的记忆读写规则。"
    "stripHtmlComments"="移除指令文件中的 HTML 注释，避免隐藏内容进入系统提示词。"
    "ensureDirs"="创建项目记忆目录及其 topic 子目录，已存在时保持不变。"
    "readItemFromTopic"="解析 topic 文件的 Frontmatter 并构建记忆索引条目。"
    "findByName"="按显示名或文件名查找记忆条目；未命中时返回 null。"
    "topicContent"="把记忆元数据和正文序列化为带 Frontmatter 的 topic 文件内容。"
    "formatForPrompt"="将召回结果格式化为带标题和来源文件名的提示词片段。"
    "toRecall"="加载记忆正文、计算相关度并创建召回结果。"
    "findLastSummarizedIndex"="通过持久化消息指纹定位最近一次会话记忆覆盖的历史索引。"
    "countToolCallsSince"="统计指定消息索引之后出现的工具调用数量。"
    "toolCallsBetweenUpdates"="返回两次会话记忆更新之间要求的最少工具调用数。"
    "toolResult"="创建与指定工具调用关联的持久化结果条目。"
    "fromChatMessage"="把受支持的模型消息转换为最小 transcript 条目。"
    "builder"="创建用于逐步填写字段的空构建器。"
    "normalizeQuery"="去除查询首尾空白并转为小写，供命令匹配使用。"
    "formatNotificationBlock"="把一批子任务完成通知格式化为注入下一轮模型上下文的块。"
    "tokenState"="返回最近一次上下文容量评估结果。"
    "withTokenState"="返回替换 token 评估结果后的新循环状态。"
    "withTerminalState"="返回进入指定终态并记录迁移原因的新循环状态。"
    "withTurnCount"="返回更新已完成轮次数量后的新循环状态。"
    "without"="复制工具注册表并移除指定名称的工具及其模型规范。"
    "parseTaskId"="读取并校验必填任务标识；缺失或空白时拒绝调用。"
    "parseAndValidateInput"="解析工具 JSON 参数并完成字段、类型和路径边界校验。"
    "readFileForEdit"="按检测到的字符编码读取文件，并保留换行风格用于无损写回。"
    "normalizeWhitespace"="统一换行符和特殊空白字符，供宽松文本匹配使用。"
    "countOccurrences"="统计目标文本在文件内容中的非重叠出现次数。"
    "buildFocusedDiff"="围绕首次变更行生成长度受限的局部差异预览。"
    "appendDiffLines"="为差异文本行添加旧、新行号和变更标记。"
    "resolveSearchSpec"="从 Glob 输入中解析静态搜索根和剩余匹配模式，并校验路径边界。"
    "normalizePathForGlob"="将平台路径分隔符统一为正斜杠，供 Glob 正则匹配。"
    "runRipgrep"="启动 ripgrep、收集标准输出和错误输出，并返回退出码。"
    "splitGlobPatterns"="按逗号拆分 Glob 过滤条件并丢弃空模式。"
    "formatLimitInfo"="生成结果截断和偏移量信息；未限制时返回空字符串。"
    "splitContentLineWithLineNumber"="解析 ripgrep 内容行中的路径、行号和正文三部分。"
    "resolveRipgrepPath"="优先使用配置路径，否则在 PATH 中定位 ripgrep 可执行文件。"
    "findToolWideRule"="按工具名查找不限制具体参数的权限规则；未命中时返回 null。"
    "buildReadAllowRule"="根据读取工具参数生成最小范围的会话允许规则。"
    "buildAllowRule"="根据工具名和参数生成可复用的会话允许规则。"
    "setEventSink"="替换 Todo 状态变化使用的事件出口；null 会退回空实现。"
    "runningCount"="返回当前仍处于运行状态的子 Agent 数量。"
    "tokens"="按连续空白拆分命令，返回用于权限判断的参数 token 列表。"
    "tool"="设置权限规则目标工具名并返回当前构建器。"
    "toolName"="返回权限规则限定的工具名；规则值缺失时返回 null。"
    "toToolCallCountBetweenUpdates"="返回两次会话记忆更新之间要求的最少工具调用数。"
    "toMessage"="按 transcript 角色恢复 LangChain4j 消息；无法安全恢复的条目返回空结果。"
    "effectiveWindow"="根据显式覆盖值和模型上下文上限计算实际自动压缩窗口。"
    "toSlashCommandOptionResponse"="把内部命令选项映射为控制面补全响应。"
    "toSessionResponse"="把运行时会话状态映射为不泄露内部对象的接口响应。"
    "toSessionRecordResponse"="把持久化会话摘要映射为列表接口条目。"
    "toTranscriptEntryResponse"="把 transcript 存储模型映射为接口传输对象。"
    "toRelativePath"="将绝对路径转换为相对工作目录、使用正斜杠的展示路径。"
    "supports"="判断输入是否应由当前斜杠命令处理。"
    "profile"="复制注册表并只保留配置集允许的工具及其模型规范。"
    "parseFrontmatter"="解析指令文件的 Frontmatter 路径约束和正文；没有头部时保留完整正文。"
    "stripFrontmatter"="移除记忆文件的 Frontmatter，仅返回可展示正文。"
    "appendHeading"="把 Markdown 标题转换为对应级别的 Word 段落。"
    "appendParagraph"="把普通 Markdown 文本追加为 Word 正文段落。"
    "appendPageBreak"="向 Word 文档追加分页符。"
    "appendRuns"="解析行内 Markdown 样式并追加对应的 Word 文本片段。"
}

foreach ($entry in $semanticMethods.GetEnumerator()) {
    $exactMethods[$entry.Key] = $entry.Value
}

$scopedMethods = @{
    "SlashCommandApplicationService#execute"="解析并执行斜杠命令，将内部结果映射为控制面响应。"
    "SlashCommand#execute"="执行已匹配的斜杠命令并返回结束或继续进入 Agent 循环的决定。"
    "BackgroundManager#execute"="等待后台进程结束或超时，并原子更新任务终态与通知。"
    "ToolDispatcher#execute"="按请求中的稳定名称执行工具，并把未知工具和实现异常归一化为失败结果。"
    "ToolRegistry#matches"="判断工具是否满足配置集的显式名单、类别、可见性和风险等级约束。"
}

$exactTypes = @{
    "FrontmatterDocument"="解析后的 Frontmatter 路径约束和正文内容。"
    "RecalledMemory"="一次记忆召回结果，包含显示名称、文件名、正文和相关度分数。"
    "PendingApproval"="等待用户决定的工具调用及其完成信号。"
    "ModelCallExecutor"="统一等待模型 Future，并处理超时、取消和根异常提取。"
    "Toolset"="子 Agent profile 对应的工具注册表和执行分发器。"
    "AgentInput"="Agent 工具校验后的任务描述、提示词和子 Agent 类型。"
    "BashInput"="Bash 工具校验后的命令、超时和输出限制。"
    "EditInput"="文件编辑工具校验后的路径、原文本、新文本和替换模式。"
    "EditSnapshot"="文件编辑前的文本、编码和换行风格快照。"
    "LineEndings"="检测到的换行文本及其主换行符。"
    "ReadInput"="文件读取工具校验后的路径、偏移量和行数限制。"
    "WriteInput"="文件写入工具校验后的目标路径和内容。"
    "WriteSnapshot"="覆盖写入前的文件内容和字符编码快照。"
    "GlobInput"="Glob 工具校验后的模式、根路径和结果限制。"
    "SearchSpec"="一次文件搜索使用的根目录和相对 Glob 模式。"
    "GlobBase"="从 Glob 模式提取出的静态搜索根和剩余模式。"
    "GlobMatch"="文件搜索结果及其最后修改时间。"
    "GrepInput"="Grep 工具校验后的模式、路径、输出模式和限制。"
    "LimitedItems"="截断后的结果集合及是否发生截断。"
    "FileMatch"="匹配文件路径及其命中的文本行。"
    "LineParts"="解析后的文件路径、行号和文本内容。"
    "PermissionBehavior"="权限规则可以产生的允许、询问或拒绝行为。"
    "AddRules"="一次向权限上下文追加多条规则的更新。"
    "AddDirectories"="一次向权限上下文追加多个允许目录的更新。"
    "TodoItem"="Todo 列表中的内容、状态和进行时描述。"
    "AgentTask"="子 Agent 任务的标识、输入、状态、Future 和结果。"
    "Task"="后台命令的进程、状态、输出和完成通知。"
    "SubagentExecution"="子 Agent 执行回调，隔离任务管理与具体运行时实现。"
    "ToolDispatcher"="工具执行分发边界。它按稳定名称定位工具，并把实现异常归一化为可返回的失败结果。"
    "ToolRegistry"="模型可见工具注册表。它维护可执行工具与模型规范的一致顺序，并支持按配置集过滤。"
    "ToolProfile"="工具配置集，按类别、可见性、风险等级及显式名单共同约束可用工具。"
    "BackgroundManager"="后台命令生命周期管理器。它并发跟踪进程、终态和一次性完成通知。"
}

function Convert-Subject([string]$name, [string[]]$removePrefixes) {
    $remaining = $name
    foreach ($prefix in $removePrefixes) {
        if ($remaining.StartsWith($prefix) -and $remaining.Length -gt $prefix.Length) {
            $remaining = $remaining.Substring($prefix.Length)
            break
        }
    }
    $tokens = [regex]::Matches($remaining, "[A-Z]?[a-z]+|[A-Z]+(?![a-z])|\d+") |
        ForEach-Object { $_.Value.ToLowerInvariant() }
    if ($tokens.Count -eq 0) {
        return $name
    }
    $translated = foreach ($token in $tokens) {
        if ($subjects.ContainsKey($token)) { $subjects[$token] } else { $token }
    }
    return ($translated -join "")
}

function Get-MethodDescription([string]$name, [string]$fileBase = "") {
    $scopedKey = "${fileBase}#${name}"
    if ($scopedMethods.ContainsKey($scopedKey)) {
        return $scopedMethods[$scopedKey]
    }
    if ($exactMethods.ContainsKey($name)) {
        return $exactMethods[$name]
    }
    switch -Regex ($name) {
        "^get[A-Z]" { return "返回$(Convert-Subject $name @('get'))。" }
        "^list[A-Z]" { return "列出$(Convert-Subject $name @('list'))。" }
        "^load[A-Z]" { return "从持久化介质加载$(Convert-Subject $name @('load'))。" }
        "^read[A-Z]" { return "读取$(Convert-Subject $name @('read'))。" }
        "^find[A-Z]" { return "查找$(Convert-Subject $name @('find'))；不存在时返回空结果。" }
        "^(is|has|can|allows|should)[A-Z]" { return "当$(Convert-Subject $name @('is','has','can','allows','should'))时返回 true。" }
        "^with[A-Z]" { return "复制当前对象，并将$(Convert-Subject $name @('with'))替换为给定值。" }
        "^add[A-Z]" { return "将给定项加入$(Convert-Subject $name @('add'))。" }
        "^set[A-Z]" { return "将$(Convert-Subject $name @('set'))更新为给定值。" }
        "^update[A-Z]" { return "更新$(Convert-Subject $name @('update'))并返回最新状态。" }
        "^build[A-Z]" { return "根据当前输入构建$(Convert-Subject $name @('build'))。" }
        "^format[A-Z]" { return "将输入格式化为$(Convert-Subject $name @('format'))。" }
        "^normalize[A-Z]" { return "将$(Convert-Subject $name @('normalize'))规范化为内部统一形式。" }
        "^parse[A-Z]" { return "解析输入并返回$(Convert-Subject $name @('parse'))。" }
        "^extract[A-Z]" { return "从输入中提取$(Convert-Subject $name @('extract'))。" }
        "^resolve[A-Z]" { return "解析并校验$(Convert-Subject $name @('resolve'))。" }
        "^append[A-Z]" { return "将$(Convert-Subject $name @('append'))追加到目标内容。" }
        "^ensure[A-Z]" { return "确保$(Convert-Subject $name @('ensure'))已满足运行前置条件。" }
        "^detect[A-Z]" { return "检测并返回$(Convert-Subject $name @('detect'))。" }
        "^estimate[A-Z]" { return "估算$(Convert-Subject $name @('estimate'))。" }
        "^strip[A-Z]" { return "移除$(Convert-Subject $name @('strip'))并返回剩余内容。" }
        "^truncate[A-Z]" { return "按限制截断$(Convert-Subject $name @('truncate'))。" }
        "^to[A-Z]" { return "将当前数据转换为$(Convert-Subject $name @('to'))。" }
        "^run[A-Z]" { return "执行$(Convert-Subject $name @('run'))并返回运行结果。" }
        "^execute[A-Z]" { return "执行$(Convert-Subject $name @('execute'))用例。" }
        "^save[A-Z]" { return "将$(Convert-Subject $name @('save'))持久化。" }
        "^write[A-Z]" { return "将$(Convert-Subject $name @('write'))写入目标。" }
        "^count[A-Z]" { return "统计$(Convert-Subject $name @('count'))数量。" }
        "^split[A-Z]" { return "拆分并返回$(Convert-Subject $name @('split'))。" }
        "^matches[A-Z]" { return "当输入匹配$(Convert-Subject $name @('matches'))时返回 true。" }
        default { return "返回当前对象记录的$(Convert-Subject $name @())。" }
    }
}

function Get-TypeDescription([string]$name) {
    if ($exactTypes.ContainsKey($name)) {
        return $exactTypes[$name]
    }
    switch -Regex ($name) {
        "Request$" { return "${name} 保存接口请求字段及其传输语义。" }
        "Response$" { return "${name} 保存接口响应字段及其传输语义。" }
        "Result$" { return "${name} 保存一次操作的结果和状态。" }
        "State$" { return "${name} 保存对应流程的不可变状态快照。" }
        "Event$" { return "${name} 保存运行时事件的序号、关联标识和载荷。" }
        "Policy$" { return "${name} 定义对应场景的权限或执行决策。" }
        "Profile$" { return "${name} 定义一组可见工具和执行约束。" }
        "Builder$" { return "${name} 收集字段并创建不可变目标对象。" }
        "Kind$|Mode$|Status$|Type$|Category$|Visibility$|RiskLevel$|Choice$" { return "${name} 枚举对应流程允许的离散状态。" }
        default { return "${name} 保存该模块内部传递的结构化数据。" }
    }
}

function Get-DeclarationDescription(
    [System.Collections.Generic.List[string]]$lines,
    [int]$start,
    [string]$fileBase
) {
    $name = Find-NextDeclarationName $lines $start
    if ($exactTypes.ContainsKey($name)) {
        return Get-TypeDescription $name
    }
    return Get-MethodDescription $name $fileBase
}

function Find-NextDeclarationName([System.Collections.Generic.List[string]]$lines, [int]$start) {
    $limit = [Math]::Min($lines.Count - 1, $start + 25)
    for ($index = $start; $index -le $limit; $index++) {
        $trimmed = $lines[$index].Trim()
        if ($trimmed.StartsWith("@")) { continue }
        if ($trimmed -match "\b(class|interface|enum|record)\s+([A-Za-z0-9_]+)") { return $matches[2] }
        if ($trimmed -match "([A-Za-z0-9_]+)\s*\(") { return $matches[1] }
    }
    return "当前对象"
}

function Get-ConstructorDescription([string]$typeName) {
    switch -Regex ($typeName) {
        "Controller$" { return "注入应用服务并创建 ${typeName}。" }
        "Service$" { return "注入该服务运行所需依赖并创建 ${typeName}。" }
        "Tool$" { return "注入工具执行所需状态组件并创建 ${typeName}。" }
        "Factory$" { return "注入对象装配所需的配置、存储和执行器。" }
        "Manager$" { return "注入状态管理所需的执行器和事件出口。" }
        "Config$" { return "读取配置源并初始化 ${typeName}。" }
        default { return "使用给定字段创建 ${typeName}。" }
    }
}

$changedComments = 0
foreach ($file in (Get-ChildItem $sourceRoot -Recurse -Filter "*.java")) {
    $content = [System.IO.File]::ReadAllText($file.FullName, $utf8Strict)
    $lineEnding = if ($content.Contains("`r`n")) { "`r`n" } else { "`n" }
    $withoutConstructorTemplates = [regex]::Replace(
        $content,
        "(?m)^[ \t]*/\*\*\r?\n[ \t]*\* 使用给定字段创建 .+。\r?\n[ \t]*\*/\r?\n",
        ""
    )
    $constructorName = [regex]::Escape($file.BaseName)
    $withoutConstructorTemplates = [regex]::Replace(
        $withoutConstructorTemplates,
        "(?m)^[ \t]*/\*\*\r?\n[ \t]*\* (?:返回当前对象记录的|将当前数据转换为).+。\r?\n[ \t]*\*/\r?\n(?=[ \t]*(?:public|protected|private)[ \t]+${constructorName}[ \t]*\()",
        ""
    )
    $fileChanged = $withoutConstructorTemplates -ne $content
    $content = $withoutConstructorTemplates
    $lines = [System.Collections.Generic.List[string]]::new()
    $content.Replace("`r`n", "`n").Split("`n") | ForEach-Object { $lines.Add($_) }
    for ($index = 0; $index -lt $lines.Count; $index++) {
        $line = $lines[$index]
        $indent = [regex]::Match($line, "^\s*").Value
        $replacement = $null

        if ($line -match "\* (执行|读取并返回|判断|更新|转换输入并生成|解析输入并提取) \{@code ([^}]+)\}.*。") {
            $replacement = "${indent}* $(Get-MethodDescription $matches[2] $file.BaseName)"
        } elseif ($line -match "\* (检查输入并返回|移除并返回) \{@code ([^}]+)\}.*。") {
            $replacement = "${indent}* $(Get-MethodDescription $matches[2] $file.BaseName)"
        } elseif ($line -match "\* 当.*时返回 true。.*") {
            $methodName = Find-NextDeclarationName $lines ($index + 1)
            $replacement = "${indent}* $(Get-MethodDescription $methodName $file.BaseName)"
        } elseif ($line -match "\* .*(from文件|app名称|app描述|基础url|seconds|窗口override|settings|forapi|summarized索引|since数量|ol调用|ol结果|pic内容|for提示词|toRecall|out替换|out完整|构建er|query规范化|token 数|for摘要|totokenbudget|fromdisk|formatSection|记忆instructions|htmlcomments|确保dirs|itemfrom|by名称|任务通知block|token状态|terminal状态|turn数量|任务id|andvalidate|文件for编辑|whitespace|occurrences|focuseddiff|diff文本行|search工具规范|路径forGlob|ripgrep并|Glob 模式patterns|限制info|with行number|ripgrep路径|工具wide|读取allow|构建allow|事件sink|执行ning|to[k]?ens|toOl|ol名称).*。") {
            $methodName = Find-NextDeclarationName $lines ($index + 1)
            $replacement = "${indent}* $(Get-MethodDescription $methodName $file.BaseName)"
        } elseif ($line -match "\* (返回当前对象记录的|将当前数据转换为).*。") {
            $replacement = "${indent}* $(Get-DeclarationDescription $lines ($index + 1) $file.BaseName)"
        } elseif ($line -match "\* (解析输入并返回Frontmatter|移除Frontmatter并返回剩余内容|将heading追加到目标内容|将paragraph追加到目标内容|将pagebreak追加到目标内容|将runs追加到目标内容|将输入格式化为section|判断斜杠命令的名称或描述是否匹配规范化查询文本).*。") {
            $methodName = Find-NextDeclarationName $lines ($index + 1)
            $replacement = "${indent}* $(Get-MethodDescription $methodName $file.BaseName)"
        } elseif ($line -match "\* 返回或处理当前对象中的.*。") {
            $methodName = Find-NextDeclarationName $lines ($index + 1)
            $replacement = "${indent}* $(Get-MethodDescription $methodName $file.BaseName)"
        } elseif ($line -match "\* ([A-Za-z0-9_]+) 封装该模块内部使用的状态与操作。") {
            $replacement = "${indent}* $(Get-TypeDescription $matches[1])"
        } elseif ($line -match "\* 执行当前组件定义的核心操作。") {
            $methodName = Find-NextDeclarationName $lines ($index + 1)
            $replacement = "${indent}* $(Get-MethodDescription $methodName $file.BaseName)"
        } elseif ($line -match "\* 使用给定依赖和初始状态创建当前对象。") {
            $typeName = Find-NextDeclarationName $lines ($index + 1)
            $replacement = "${indent}* $(Get-ConstructorDescription $typeName)"
        } elseif ($line -match "\* ([A-Za-z0-9_]+) 定义该模块使用的数据或行为契约。") {
            $replacement = "${indent}* $(Get-TypeDescription $matches[1])"
        }

        if ($null -ne $replacement -and $replacement -ne $line) {
            $lines[$index] = $replacement
            $changedComments++
            $fileChanged = $true
        }
    }

    if ($fileChanged) {
        [System.IO.File]::WriteAllText($file.FullName, [string]::Join($lineEnding, $lines), $utf8NoBom)
    }
}

Write-Output ("Replaced {0} templated Javadoc lines." -f $changedComments)
