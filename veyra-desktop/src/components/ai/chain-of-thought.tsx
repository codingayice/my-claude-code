"use client"

import {
  BrainIcon,
  ChevronDownIcon,
  DotIcon,
  type LucideIcon,
} from "lucide-react"
import type { ComponentProps, ReactNode } from "react"
import { createContext, memo, useContext, useMemo, useState } from "react"

import { Badge } from "@/components/ui/badge"
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible"
import { cn } from "@/lib/utils"

interface ChainOfThoughtContextValue {
  isOpen: boolean
  setIsOpen: (open: boolean) => void
}

const ChainOfThoughtContext = createContext<ChainOfThoughtContextValue | null>(null)

const useChainOfThought = () => {
  const context = useContext(ChainOfThoughtContext)
  if (!context) {
    throw new Error("ChainOfThought components must be used within ChainOfThought")
  }
  return context
}

export type ChainOfThoughtProps = ComponentProps<"div"> & {
  open?: boolean
  defaultOpen?: boolean
  onOpenChange?: (open: boolean) => void
}

export const ChainOfThought = memo(
  ({
    className,
    open,
    defaultOpen = false,
    onOpenChange,
    children,
    ...props
  }: ChainOfThoughtProps) => {
    const [internalOpen, setInternalOpen] = useState(defaultOpen)
    const isControlled = open !== undefined
    const isOpen = isControlled ? open : internalOpen

    const setIsOpen = (next: boolean) => {
      if (!isControlled) {
        setInternalOpen(next)
      }
      onOpenChange?.(next)
    }

    const chainOfThoughtContext = useMemo(
      () => ({ isOpen, setIsOpen }),
      [isOpen],
    )

    return (
      <ChainOfThoughtContext.Provider value={chainOfThoughtContext}>
        <div className={cn("not-prose w-full space-y-1.5", className)} {...props}>
          {children}
        </div>
      </ChainOfThoughtContext.Provider>
    )
  },
)

export type ChainOfThoughtHeaderProps = ComponentProps<typeof CollapsibleTrigger>

export const ChainOfThoughtHeader = memo(
  ({ className, children, ...props }: ChainOfThoughtHeaderProps) => {
    const { isOpen, setIsOpen } = useChainOfThought()

    return (
      <Collapsible onOpenChange={setIsOpen} open={isOpen}>
        <CollapsibleTrigger
          className={cn(
            "flex w-full items-center gap-2 py-1.5 text-sm text-muted-foreground transition-colors hover:text-foreground",
            className,
          )}
          {...props}
        >
          <BrainIcon className="size-4 shrink-0" />
          <span className="min-w-0 flex-1 text-left">{children ?? "思考过程"}</span>
          <ChevronDownIcon
            className={cn("size-4 shrink-0 transition-transform", isOpen ? "rotate-180" : "rotate-0")}
          />
        </CollapsibleTrigger>
      </Collapsible>
    )
  },
)

export type ChainOfThoughtStepProps = ComponentProps<"div"> & {
  icon?: LucideIcon
  label: ReactNode
  description?: ReactNode
  status?: "complete" | "active" | "pending"
  badge?: string
}

export const ChainOfThoughtStep = memo(
  ({
    className,
    icon: Icon = DotIcon,
    label,
    description,
    status = "complete",
    badge,
    children,
    ...props
  }: ChainOfThoughtStepProps) => {
    const statusStyles = {
      complete: "text-muted-foreground",
      active: "text-foreground",
      pending: "text-muted-foreground/60",
    }

    return (
      <div
        className={cn(
          "group/cot-step relative grid grid-cols-[1rem_minmax(0,1fr)] gap-x-2 text-sm",
          statusStyles[status],
          "fade-in-0 slide-in-from-top-2 animate-in",
          className,
        )}
        {...props}
      >
        <div className="relative flex justify-center pt-0.5">
          <Icon className="size-4 shrink-0" />
          <div className="absolute top-6 bottom-0 left-1/2 w-px -translate-x-1/2 bg-border/80 group-last/cot-step:hidden" />
        </div>
        <div className="min-w-0 overflow-hidden pb-3">
          <div className="flex min-h-5 items-center gap-2">
            <div className="min-w-0">{label}</div>
            {badge ? (
              <Badge className="text-[10px]" variant="secondary">
                {badge}
              </Badge>
            ) : null}
          </div>
          {description ? <div className="text-muted-foreground text-xs">{description}</div> : null}
          {children ? <div className="mt-2 border-l border-border/70 pl-4">{children}</div> : null}
        </div>
      </div>
    )
  },
)

export type ChainOfThoughtContentProps = ComponentProps<typeof CollapsibleContent>

export const ChainOfThoughtContent = memo(
  ({ className, children, ...props }: ChainOfThoughtContentProps) => {
    const { isOpen } = useChainOfThought()

    return (
      <Collapsible open={isOpen}>
        <CollapsibleContent
          className={cn(
            "data-[state=closed]:fade-out-0 data-[state=closed]:slide-out-to-top-2 data-[state=open]:slide-in-from-top-2 outline-none data-[state=closed]:animate-out data-[state=open]:animate-in",
            className,
          )}
          {...props}
        >
          <div className="py-1">
            {children}
          </div>
        </CollapsibleContent>
      </Collapsible>
    )
  },
)

export type ChainOfThoughtSearchResultsProps = ComponentProps<"div">

export const ChainOfThoughtSearchResults = memo(
  ({ className, ...props }: ChainOfThoughtSearchResultsProps) => (
    <div className={cn("flex flex-wrap items-center gap-2", className)} {...props} />
  ),
)

export type ChainOfThoughtSearchResultProps = ComponentProps<typeof Badge>

export const ChainOfThoughtSearchResult = memo(
  ({ className, children, ...props }: ChainOfThoughtSearchResultProps) => (
    <Badge
      className={cn("gap-1 px-2 py-0.5 font-normal text-xs", className)}
      variant="secondary"
      {...props}
    >
      {children}
    </Badge>
  ),
)

ChainOfThought.displayName = "ChainOfThought"
ChainOfThoughtHeader.displayName = "ChainOfThoughtHeader"
ChainOfThoughtStep.displayName = "ChainOfThoughtStep"
ChainOfThoughtContent.displayName = "ChainOfThoughtContent"
ChainOfThoughtSearchResults.displayName = "ChainOfThoughtSearchResults"
ChainOfThoughtSearchResult.displayName = "ChainOfThoughtSearchResult"
