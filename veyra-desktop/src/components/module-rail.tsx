import type { LucideIcon } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip"
import { cn } from "@/lib/utils"

export type ModuleRailItem = {
  id: string
  label: string
  icon: LucideIcon
  badge?: string
}

type ModuleRailProps = {
  items: ModuleRailItem[]
  activeId: string
  onChange: (id: string) => void
}

export function ModuleRail({
  items,
  activeId,
  onChange,
}: ModuleRailProps) {
  return (
    <aside className="flex w-16 shrink-0 flex-col items-center gap-3 border-r border-border bg-muted/20 px-2 py-3">
      <div className="flex size-10 items-center justify-center rounded-xl bg-primary text-primary-foreground shadow-sm">
        <span className="text-sm font-semibold">VY</span>
      </div>

      <div className="flex flex-1 flex-col items-center gap-2">
        {items.map((item) => {
          const Icon = item.icon
          const active = item.id === activeId

          return (
            <Tooltip key={item.id}>
              <TooltipTrigger asChild>
                <Button
                  aria-pressed={active}
                  className={cn(
                    "relative size-10 rounded-xl",
                    active &&
                      "border border-primary/20 bg-primary/10 text-primary hover:bg-primary/12"
                  )}
                  onClick={() => onChange(item.id)}
                  size="icon"
                  type="button"
                  variant={active ? "secondary" : "ghost"}
                >
                  <Icon className="size-4" />
                  <span className="sr-only">{item.label}</span>
                  {item.badge ? (
                    <Badge className="absolute top-0 right-0 min-w-4 rounded-full px-1 text-[10px]">
                      {item.badge}
                    </Badge>
                  ) : null}
                </Button>
              </TooltipTrigger>
              <TooltipContent side="right">{item.label}</TooltipContent>
            </Tooltip>
          )
        })}
      </div>
    </aside>
  )
}
