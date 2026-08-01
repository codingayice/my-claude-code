"use client"

import { BotIcon } from "lucide-react"
import type { ComponentProps, HTMLAttributes, ReactNode } from "react"
import { memo } from "react"

import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion"
import { Badge } from "@/components/ui/badge"
import { cn } from "@/lib/utils"

export type AgentProps = HTMLAttributes<HTMLDivElement>

export const Agent = memo(({ className, ...props }: AgentProps) => (
  <div className={cn("not-prose w-full rounded-md border", className)} {...props} />
))

export type AgentHeaderProps = HTMLAttributes<HTMLDivElement> & {
  name: string
  model?: string
}

export const AgentHeader = memo(({ className, name, model, ...props }: AgentHeaderProps) => (
  <div className={cn("flex w-full items-center justify-between gap-4 p-3", className)} {...props}>
    <div className="flex items-center gap-2">
      <BotIcon className="size-4 text-muted-foreground" />
      <span className="font-medium text-sm">{name}</span>
      {model ? (
        <Badge className="font-mono text-xs" variant="secondary">
          {model}
        </Badge>
      ) : null}
    </div>
  </div>
))

export type AgentContentProps = HTMLAttributes<HTMLDivElement>

export const AgentContent = memo(({ className, ...props }: AgentContentProps) => (
  <div className={cn("space-y-4 p-4 pt-0", className)} {...props} />
))

export type AgentInstructionsProps = HTMLAttributes<HTMLDivElement> & {
  children: string
}

export const AgentInstructions = memo(
  ({ className, children, ...props }: AgentInstructionsProps) => (
    <div className={cn("space-y-2", className)} {...props}>
      <span className="font-medium text-muted-foreground text-sm">说明</span>
      <div className="rounded-md bg-muted/50 p-3 text-muted-foreground text-sm">
        <p>{children}</p>
      </div>
    </div>
  ),
)

export type AgentToolsProps = HTMLAttributes<HTMLDivElement> & {
  children?: ReactNode
  type?: "single" | "multiple"
}

export const AgentTools = memo(({ className, type = "multiple", children, ...props }: AgentToolsProps) => {

  return (
    <div className={cn("space-y-2", className)} {...props}>
      <span className="font-medium text-muted-foreground text-sm">工具</span>
      {type === "single" ? (
        <Accordion className="rounded-md border" collapsible type="single">
          {children}
        </Accordion>
      ) : (
        <Accordion className="rounded-md border" type="multiple">
          {children}
        </Accordion>
      )}
    </div>
  )
})

interface ToolSchema {
  description?: string
  jsonSchema?: object
  inputSchema?: object
}

export type AgentToolProps = ComponentProps<typeof AccordionItem> & {
  tool: ToolSchema
}

export const AgentTool = memo(({ className, tool, value, ...props }: AgentToolProps) => {
  const schema = "jsonSchema" in tool && tool.jsonSchema ? tool.jsonSchema : tool.inputSchema

  return (
    <AccordionItem className={cn("border-b last:border-b-0", className)} value={value} {...props}>
      <AccordionTrigger className="px-3 py-2 text-sm hover:no-underline">
        {tool.description ?? "无说明"}
      </AccordionTrigger>
      <AccordionContent className="px-3 pb-3">
        <div className="rounded-md bg-muted/50">
          <pre className="overflow-auto p-3 font-mono text-xs">
            {JSON.stringify(schema, null, 2)}
          </pre>
        </div>
      </AccordionContent>
    </AccordionItem>
  )
})

export type AgentOutputProps = HTMLAttributes<HTMLDivElement> & {
  schema: string
}

export const AgentOutput = memo(({ className, schema, ...props }: AgentOutputProps) => (
  <div className={cn("space-y-2", className)} {...props}>
    <span className="font-medium text-muted-foreground text-sm">输出结构</span>
    <div className="rounded-md bg-muted/50">
      <pre className="overflow-auto p-3 font-mono text-xs">{schema}</pre>
    </div>
  </div>
))

Agent.displayName = "Agent"
AgentHeader.displayName = "AgentHeader"
AgentContent.displayName = "AgentContent"
AgentInstructions.displayName = "AgentInstructions"
AgentTools.displayName = "AgentTools"
AgentTool.displayName = "AgentTool"
AgentOutput.displayName = "AgentOutput"
