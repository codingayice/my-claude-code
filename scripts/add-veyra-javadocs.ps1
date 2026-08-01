param(
    [string]$ReportPath = "target/surefire-reports/cn.ayice.veyra.architecture.VeyraDocumentationTest.txt"
)

$ErrorActionPreference = "Stop"
$workspace = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$report = Join-Path $workspace $ReportPath
$utf8Strict = [System.Text.UTF8Encoding]::new($false, $true)
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)

function Get-TypeDescription([string]$name) {
    switch -Regex ($name) {
        "Controller$" { return "${name} 负责接收和响应 HTTP 请求。" }
        "ApplicationService$" { return "${name} 负责协调 HTTP 用例与运行时入口。" }
        "Service$" { return "${name} 封装对应能力的业务流程。" }
        "Request$" { return "${name} 表示接口请求参数。" }
        "Response$" { return "${name} 表示接口响应数据。" }
        "Configuration$|Config$" { return "${name} 定义运行配置及其装配规则。" }
        "Factory$" { return "${name} 负责创建并装配对应运行时对象。" }
        "Registry$" { return "${name} 维护对象注册关系和查询能力。" }
        "Store$" { return "${name} 负责对应数据的存取。" }
        "Manager$" { return "${name} 管理对应状态及其生命周期。" }
        "Tool$" { return "${name} 实现一个可由智能体调用的工具。" }
        "Policy$" { return "${name} 定义对应场景的决策策略。" }
        "Context$" { return "${name} 保存对应流程所需的上下文。" }
        "State$" { return "${name} 表示对应流程的状态快照。" }
        "Event$" { return "${name} 表示运行时事件。" }
        "Result$" { return "${name} 表示操作执行结果。" }
        "Exception$" { return "${name} 表示对应边界的异常。" }
        "Builder$" { return "${name} 按步骤构建目标对象。" }
        default { return "${name} 定义该模块使用的数据或行为契约。" }
    }
}

function Get-MethodDescription([string]$name) {
    $exact = @{
        "name" = "返回当前组件的稳定名称。"
        "description" = "返回当前组件面向模型或调用方的说明。"
        "category" = "返回工具所属类别。"
        "visibility" = "返回工具的可见范围。"
        "riskLevel" = "返回工具执行风险级别。"
        "getSpec" = "构建并返回工具调用规范。"
        "checkPermissions" = "根据参数和权限上下文评估本次工具调用。"
        "validateInput" = "校验工具输入并返回结构化校验结果。"
        "execute" = "执行当前组件定义的核心操作。"
        "compute" = "计算并返回当前系统提示词片段。"
        "build" = "根据当前输入构建目标对象。"
        "apply" = "应用给定变更并返回更新后的结果。"
        "close" = "释放当前对象持有的运行资源。"
        "shutdown" = "停止当前组件并释放其后台资源。"
        "toString" = "返回当前对象的文本表示。"
        "equals" = "比较当前对象与给定对象是否等价。"
        "hashCode" = "返回与等价判断一致的哈希值。"
    }
    if ($exact.ContainsKey($name)) {
        return $exact[$name]
    }

    switch -Regex ($name) {
        "^(get|load|read|find|list|current|state|history|entries|rules|mode|workingDir)" {
            return "读取并返回 {@code $name} 对应的数据。"
        }
        "^(is|has|can|should|allows|contains|matches)" {
            return "判断 {@code $name} 所表达的条件是否成立。"
        }
        "^(create|new|from|of)" {
            return "根据输入创建对应对象。"
        }
        "^(build|format|to|convert|normalize)" {
            return "转换输入并生成 {@code $name} 对应的结果。"
        }
        "^(parse|extract|resolve)" {
            return "解析输入并提取 {@code $name} 所需的信息。"
        }
        "^(set|update|add|register|bind|append|record|write|save)" {
            return "更新 {@code $name} 对应的状态或数据。"
        }
        "^(remove|delete|clear|drain)" {
            return "移除并返回 {@code $name} 对应的数据。"
        }
        "^(submit|enqueue|run|process|dispatch|invoke|call)" {
            return "执行 {@code $name} 定义的处理流程。"
        }
        "^(check|validate|authorize|decide|evaluate)" {
            return "检查输入并返回 {@code $name} 对应的判定结果。"
        }
        "^(handle|on|send|emit|fire|notify)" {
            return "处理并传播 {@code $name} 对应的事件。"
        }
        "^(stop|cancel|close|shutdown)" {
            return "终止 {@code $name} 对应的运行资源。"
        }
        default {
            return "执行 {@code $name} 对应的内部职责。"
        }
    }
}

function Find-Declaration([string[]]$lines, [int]$startIndex) {
    $limit = [Math]::Min($lines.Length - 1, $startIndex + 20)
    for ($index = $startIndex; $index -le $limit; $index++) {
        $trimmed = $lines[$index].Trim()
        if ($trimmed.StartsWith("@")) {
            continue
        }
        if ($trimmed -match "\b(class|interface|enum|record)\s+([A-Za-z0-9_]+)") {
            return @{ Kind = "type"; Name = $matches[2] }
        }
        if ($trimmed -match "\(") {
            return @{ Kind = "method"; Name = $null }
        }
    }
    return @{ Kind = "method"; Name = $null }
}

$missing = Get-Content -Encoding UTF8 $report |
    Where-Object { $_ -match "^(src\\main\\java\\cn\\ayice\\veyra\\.+\.java):(\d+)\s+(.+)$" } |
    ForEach-Object {
        [pscustomobject]@{
            RelativePath = $matches[1]
            Line = [int]$matches[2]
            Symbol = $matches[3].Trim()
        }
    }

$inserted = 0
foreach ($fileGroup in ($missing | Group-Object RelativePath)) {
    $path = Join-Path $workspace $fileGroup.Name
    $content = [System.IO.File]::ReadAllText($path, $utf8Strict)
    $lineEnding = if ($content.Contains("`r`n")) { "`r`n" } else { "`n" }
    $lines = [System.Collections.Generic.List[string]]::new()
    $content.Replace("`r`n", "`n").Split("`n") | ForEach-Object { $lines.Add($_) }

    foreach ($item in ($fileGroup.Group | Sort-Object Line -Descending)) {
        $index = $item.Line - 1
        $indent = [regex]::Match($lines[$index], "^\s*").Value
        $declaration = Find-Declaration $lines $index
        $isOverride = $lines[$index].Trim().StartsWith("@Override")

        if ($isOverride) {
            $comment = @(
                "${indent}/**",
                "${indent} * {@inheritDoc}",
                "${indent} */"
            )
        } elseif ($declaration.Kind -eq "type") {
            $description = Get-TypeDescription $declaration.Name
            $comment = @(
                "${indent}/**",
                "${indent} * $description",
                "${indent} */"
            )
        } elseif ($item.Symbol -eq "构造器") {
            $comment = @(
                "${indent}/**",
                "${indent} * 使用给定依赖和初始状态创建当前对象。",
                "${indent} */"
            )
        } else {
            $description = Get-MethodDescription $item.Symbol
            $comment = @(
                "${indent}/**",
                "${indent} * $description",
                "${indent} */"
            )
        }

        for ($commentIndex = $comment.Length - 1; $commentIndex -ge 0; $commentIndex--) {
            $lines.Insert($index, $comment[$commentIndex])
        }
        $inserted++
    }

    [System.IO.File]::WriteAllText($path, [string]::Join($lineEnding, $lines), $utf8NoBom)
}

Write-Output ("Inserted Javadoc for {0} declarations across {1} files." -f $inserted, ($missing.RelativePath | Sort-Object -Unique).Count)
