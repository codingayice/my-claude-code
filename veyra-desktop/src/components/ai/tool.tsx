"use client"

import type { ToolUIPart } from "ai"
import {
  CheckCircleIcon,
  ChevronDownIcon,
  CircleIcon,
  ClockIcon,
  WrenchIcon,
  XCircleIcon,
} from "lucide-react"
import type { ComponentProps, ReactNode } from "react"
import { isValidElement } from "react"

import { CodeBlock } from "@/components/ai/code-block"
import { Badge } from "@/components/ui/badge"
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible"
import { cn } from "@/lib/utils"

export type ToolProps = ComponentProps<typeof Collapsible>

export const Tool = ({ className, ...props }: ToolProps) => (
  <Collapsible
    className={cn("group not-prose mb-4 w-full", className)}
    {...props}
  />
)

export interface ToolHeaderProps {
  title?: string
  type: ToolUIPart["type"]
  state: ToolUIPart["state"]
  className?: string
}

const getStatusBadge = (status: ToolUIPart["state"]) => {
  const labels: Record<ToolUIPart["state"], string> = {
    "input-streaming": "排队中",
    "input-available": "执行中",
    "approval-requested": "等待授权",
    "approval-responded": "已响应",
    "output-available": "已完成",
    "output-error": "出错",
    "output-denied": "已拒绝",
  }

  const icons: Record<ToolUIPart["state"], ReactNode> = {
    "input-streaming": <CircleIcon className="size-4" />,
    "input-available": <ClockIcon className="size-4 animate-pulse" />,
    "approval-requested": <ClockIcon className="size-4 text-yellow-600" />,
    "approval-responded": <CheckCircleIcon className="size-4 text-blue-600" />,
    "output-available": <CheckCircleIcon className="size-4 text-green-600" />,
    "output-error": <XCircleIcon className="size-4 text-red-600" />,
    "output-denied": <XCircleIcon className="size-4 text-orange-600" />,
  }

  return (
    <Badge className="gap-1.5 rounded-full border-0 bg-muted px-2 py-0.5 font-normal text-xs" variant="secondary">
      {icons[status]}
      {labels[status]}
    </Badge>
  )
}

export const ToolHeader = ({ className, title, type, state, ...props }: ToolHeaderProps) => (
  <CollapsibleTrigger
    className={cn("flex w-full items-center justify-between gap-3 py-1.5 text-left", className)}
    {...props}
  >
    <div className="flex min-w-0 items-center gap-2">
      <WrenchIcon className="size-4 text-muted-foreground" />
      <span className="min-w-0 truncate font-medium text-sm">{title ?? type.split("-").slice(1).join("-")}</span>
      {getStatusBadge(state)}
    </div>
    <ChevronDownIcon className="size-4 text-muted-foreground transition-transform group-data-[state=open]:rotate-180" />
  </CollapsibleTrigger>
)

export type ToolContentProps = ComponentProps<typeof CollapsibleContent>

export const ToolContent = ({ className, ...props }: ToolContentProps) => (
  <CollapsibleContent
    className={cn(
      "data-[state=closed]:fade-out-0 data-[state=closed]:slide-out-to-top-2 data-[state=open]:slide-in-from-top-2 text-popover-foreground outline-none data-[state=closed]:animate-out data-[state=open]:animate-in",
      className,
    )}
    {...props}
  />
)

export type ToolInputProps = ComponentProps<"div"> & {
  input: ToolUIPart["input"]
}

export const ToolInput = ({ className, input, ...props }: ToolInputProps) => (
  <div className={cn("space-y-3 overflow-hidden py-4", className)} {...props}>
    <h4 className="font-medium text-muted-foreground text-xs uppercase tracking-wide">
      参数
    </h4>
    <div>
      <CodeBlock
        className="border-0 bg-transparent shadow-none [&_pre]:bg-transparent!"
        code={JSON.stringify(input, null, 2)}
        language="json"
      />
    </div>
  </div>
)

export type ToolOutputProps = ComponentProps<"div"> & {
  output: ToolUIPart["output"]
  errorText: ToolUIPart["errorText"]
}

export const ToolOutput = ({ className, output, errorText, ...props }: ToolOutputProps) => {
  if (!(output || errorText)) {
    return null
  }

  let Output = <div>{output as ReactNode}</div>

  if (typeof output === "object" && !isValidElement(output)) {
    Output = <CodeBlock code={JSON.stringify(output, null, 2)} language="json" />
  } else if (typeof output === "string") {
    Output = <CodeBlock code={output} language="json" />
  }

  return (
    <div className={cn("space-y-3 py-4", className)} {...props}>
      <h4 className="font-medium text-muted-foreground text-xs uppercase tracking-wide">
        {errorText ? "错误" : "结果"}
      </h4>
      <div
        className={cn(
          "overflow-x-auto text-xs [&_table]:w-full",
          errorText ? "text-destructive" : "text-foreground",
        )}
      >
        {errorText && <div>{errorText}</div>}
        {Output}
      </div>
    </div>
  )
}

export default function ToolDemo() {
  return (
    <div className="w-full max-w-2xl p-6">
      <Tool defaultOpen>
        <ToolHeader title="Weather Lookup" type="tool-invocation" state="output-available" />
        <ToolContent>
          <ToolInput
            input={{
              location: "San Francisco, CA",
              units: "fahrenheit",
            }}
          />
          <ToolOutput
            errorText={undefined}
            output={{
              temperature: 68,
              condition: "Partly cloudy",
              humidity: 65,
              wind: "12 mph NW",
            }}
          />
        </ToolContent>
      </Tool>
    </div>
  )
}
