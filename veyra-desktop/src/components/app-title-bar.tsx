import { useEffect, useState, type ReactNode } from "react"
import { getCurrentWindow } from "@tauri-apps/api/window"
import { Copy, Minus, MoonStar, Square, SunMedium, X } from "lucide-react"

import { useTheme } from "@/components/theme-provider"
import { Button } from "@/components/ui/button"
import { Separator } from "@/components/ui/separator"

type AppTitleBarProps = {
  title: string
  subtitle?: string
  subtitleNode?: ReactNode
}

export function AppTitleBar({ title, subtitle, subtitleNode }: AppTitleBarProps) {
  const [isMaximized, setIsMaximized] = useState(false)
  const [windowHandle, setWindowHandle] =
    useState<ReturnType<typeof getCurrentWindow> | null>(null)
  const { theme, setTheme } = useTheme()

  useEffect(() => {
    let cleanup: undefined | (() => void)
    let disposed = false

    const setup = async () => {
      try {
        const currentWindow = getCurrentWindow()
        if (disposed) {
          return
        }

        setWindowHandle(currentWindow)
        setIsMaximized(await currentWindow.isMaximized())
        cleanup = await currentWindow.onResized(async () => {
          setIsMaximized(await currentWindow.isMaximized())
        })
      } catch {
        cleanup = undefined
      }
    }

    void setup()

    return () => {
      disposed = true
      cleanup?.()
    }
  }, [])

  return (
    <div className="flex h-9 shrink-0 items-center border-b border-border bg-background/95">
      <div
        className="flex h-full min-w-0 flex-1 items-center gap-2 px-3 select-none"
        data-tauri-drag-region
      >
        <div className="flex size-5 items-center justify-center rounded-md bg-primary/10 text-primary">
          <Square className="size-3 fill-current" />
        </div>
        <div className="min-w-0">
          <div className="truncate text-[13px] font-semibold">{title}</div>
        </div>
        {subtitleNode || subtitle ? (
          <>
            <Separator className="h-4" orientation="vertical" />
            {subtitleNode ? (
              <div className="min-w-0">{subtitleNode}</div>
            ) : (
              <div className="truncate text-xs text-muted-foreground">
                {subtitle}
              </div>
            )}
          </>
        ) : null}
      </div>

      <div className="flex items-center gap-1 px-1">
        <Button
          className="rounded-md"
          onClick={() => setTheme(theme === "dark" ? "light" : "dark")}
          size="icon-xs"
          type="button"
          variant="ghost"
        >
          {theme === "dark" ? <SunMedium /> : <MoonStar />}
          <span className="sr-only">切换主题</span>
        </Button>

        <Button
          className="rounded-md"
          disabled={!windowHandle}
          onClick={() => windowHandle?.minimize()}
          size="icon-xs"
          type="button"
          variant="ghost"
        >
          <Minus />
          <span className="sr-only">最小化</span>
        </Button>
        <Button
          className="rounded-md"
          disabled={!windowHandle}
          onClick={() => windowHandle?.toggleMaximize()}
          size="icon-xs"
          type="button"
          variant="ghost"
        >
          {isMaximized ? <Copy /> : <Square />}
          <span className="sr-only">切换最大化</span>
        </Button>
        <Button
          className="rounded-md hover:bg-destructive hover:text-destructive-foreground"
          disabled={!windowHandle}
          onClick={() => windowHandle?.close()}
          size="icon-xs"
          type="button"
          variant="ghost"
        >
          <X />
          <span className="sr-only">关闭</span>
        </Button>
      </div>
    </div>
  )
}
