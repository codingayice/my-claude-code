"use client"

import { CheckIcon, CopyIcon, TerminalIcon } from "lucide-react"
import {
  type ComponentProps,
  createContext,
  type HTMLAttributes,
  type ComponentType,
  useContext,
  useEffect,
  useRef,
  useState,
} from "react"
import AnsiModule from "ansi-to-react"
import { Button } from "@/components/ui/button"
import { cn } from "@/lib/utils"

type AnsiComponent = ComponentType<{ children?: string; linkify?: boolean | "fuzzy"; className?: string }>

const Ansi = (
  typeof AnsiModule === "function"
    ? AnsiModule
    : (AnsiModule as unknown as { default: AnsiComponent }).default
) as AnsiComponent

interface TerminalContextType {
  output: string
  autoScroll: boolean
}

const TerminalContext = createContext<TerminalContextType>({
  output: "",
  autoScroll: true,
})

export type TerminalProps = HTMLAttributes<HTMLDivElement> & {
  output: string
  autoScroll?: boolean
}

export const Terminal = ({
  output,
  autoScroll = true,
  className,
  children,
  ...props
}: TerminalProps) => (
  <TerminalContext.Provider value={{ output, autoScroll }}>
    <div
      className={cn(
        "flex flex-col overflow-hidden rounded-md border border-zinc-800 bg-zinc-950 text-zinc-100",
        className,
      )}
      {...props}
    >
      {children ?? (
        <>
          <TerminalHeader>
            <TerminalTitle />
            <TerminalCopyButton />
          </TerminalHeader>
          <TerminalContent />
        </>
      )}
    </div>
  </TerminalContext.Provider>
)

export type TerminalHeaderProps = HTMLAttributes<HTMLDivElement>

export const TerminalHeader = ({ className, ...props }: TerminalHeaderProps) => (
  <div
    className={cn("flex items-center justify-between border-zinc-800 border-b px-3 py-2", className)}
    {...props}
  />
)

export type TerminalTitleProps = HTMLAttributes<HTMLDivElement>

export const TerminalTitle = ({ className, children, ...props }: TerminalTitleProps) => (
  <div className={cn("flex items-center gap-2 text-xs text-zinc-400", className)} {...props}>
    <TerminalIcon className="size-3.5" />
    {children ?? "Terminal"}
  </div>
)

export type TerminalCopyButtonProps = ComponentProps<typeof Button> & {
  timeout?: number
}

export const TerminalCopyButton = ({
  timeout = 2000,
  children,
  className,
  ...props
}: TerminalCopyButtonProps) => {
  const [isCopied, setIsCopied] = useState(false)
  const { output } = useContext(TerminalContext)

  const copyToClipboard = async () => {
    if (typeof window === "undefined" || !navigator?.clipboard?.writeText) return

    await navigator.clipboard.writeText(output)
    setIsCopied(true)
    window.setTimeout(() => setIsCopied(false), timeout)
  }

  const Icon = isCopied ? CheckIcon : CopyIcon

  return (
    <Button
      className={cn("size-7 shrink-0 text-zinc-400 hover:bg-zinc-800 hover:text-zinc-100", className)}
      onClick={copyToClipboard}
      size="icon"
      type="button"
      variant="ghost"
      {...props}
    >
      {children ?? <Icon size={14} />}
    </Button>
  )
}

export type TerminalContentProps = HTMLAttributes<HTMLDivElement>

export const TerminalContent = ({ className, children, ...props }: TerminalContentProps) => {
  const { output, autoScroll } = useContext(TerminalContext)
  const containerRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (autoScroll && containerRef.current) {
      containerRef.current.scrollTop = containerRef.current.scrollHeight
    }
  }, [output, autoScroll])

  return (
    <div
      className={cn("max-h-56 overflow-auto p-3 font-mono text-xs leading-relaxed", className)}
      ref={containerRef}
      {...props}
    >
      {children ?? (
        <pre className="whitespace-pre-wrap break-words">
          <Ansi>{output}</Ansi>
        </pre>
      )}
    </div>
  )
}
