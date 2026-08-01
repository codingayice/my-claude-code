"use client"

import { ArrowUpRight, X } from "lucide-react"
import { AnimatePresence, motion } from "framer-motion"
import { useEffect, useMemo, useState } from "react"
import type { SubagentEntry, SubagentStatus } from "@/components/chat-panel"
import { cn } from "@/lib/utils"

type SubagentStackProps = {
  subagents: SubagentEntry[]
  onSelect: (subagent: SubagentEntry) => void
}

const STACK_W = 96
const STACK_H = 120

const FEATURED_W = 360
const FEATURED_H = 460
const THUMB_W = 160
const THUMB_H = 200

function agentAvatarDataUri(agent: SubagentEntry): string {
  const seed = encodeURIComponent(`${agent.name}-${agent.subagentType ?? ""}-${agent.taskId.slice(-6)}`)
  return `https://api.dicebear.com/9.x/notionists/svg?seed=${seed}&backgroundColor=transparent&scale=100`
}

function statusDotClass(status: SubagentStatus) {
  switch (status) {
    case "completed": return "bg-green-500"
    case "failed": return "bg-red-500"
    case "killed": return "bg-zinc-400"
    default: return "bg-blue-500"
  }
}

function statusLabel(status: SubagentStatus) {
  switch (status) {
    case "completed": return "已完成"
    case "failed": return "失败"
    case "killed": return "已终止"
    default: return "执行中"
  }
}

function fmtDur(ms: number) {
  const s = Math.max(0, Math.floor(ms / 1000))
  if (s < 60) return `${s}s`
  return `${Math.floor(s / 60)}m ${s % 60}s`
}

function FeaturedCard({
  agent,
  onSelect,
}: {
  agent: SubagentEntry
  onSelect: (agent: SubagentEntry) => void
}) {
  const avatarUri = useMemo(() => agentAvatarDataUri(agent), [agent])

  return (
    <motion.div
      animate={{ opacity: 1, y: 0 }}
      className="relative flex flex-col overflow-hidden rounded-[32px] border border-white/10 bg-[#303030] text-white"
      exit={{ opacity: 0, y: 12 }}
      initial={{ opacity: 0, y: 12 }}
      key={agent.taskId}
      style={{
        width: FEATURED_W,
        height: FEATURED_H,
        boxShadow: "0 30px 80px rgba(0,0,0,0.22)",
      }}
      transition={{ type: "spring", stiffness: 360, damping: 30 }}
    >
      <div className="relative mx-6 mt-6 mb-7 flex h-[200px] items-center justify-center overflow-hidden rounded-[22px] bg-[#444]">
        <img
          alt={agent.name}
          className="h-[180px] w-[180px] object-contain opacity-80 invert"
          src={avatarUri}
        />
        <span
          className={cn(
            "absolute right-4 top-4 size-2.5 rounded-full ring-2 ring-white/20",
            statusDotClass(agent.status),
          )}
        />
      </div>

      <div className="flex flex-1 flex-col px-7 pb-7">
        <span className="mb-1.5 text-sm font-semibold leading-tight text-white/56">
          {agent.subagentType ?? "子agent"}
        </span>
        <span className="mb-3 text-[32px] font-extrabold leading-[0.95] tracking-[-0.04em] truncate">
          {agent.name}
        </span>
        <p className="text-[16px] font-semibold leading-[1.3] tracking-[-0.02em] text-white/82 line-clamp-3">
          {agent.description ?? "—"}
        </p>

        <div className="mt-auto flex items-center gap-2 pt-4">
          <span className={cn("size-1.5 rounded-full", statusDotClass(agent.status))} />
          <span className="text-xs font-medium text-white/50">{statusLabel(agent.status)}</span>
          <span className="tabular-nums text-xs font-medium text-white/50">
            {fmtDur((agent.endedAtMs ?? Date.now()) - agent.startedAtMs)}
          </span>
          {typeof agent.totalToolUseCount === "number" ? (
            <span className="text-xs font-medium text-white/50">
              · {agent.totalToolUseCount} 次工具
            </span>
          ) : null}
        </div>
      </div>

      <button
        className="absolute right-7 bottom-7 z-20 inline-flex h-10 items-center gap-1.5 rounded-full bg-white px-4 text-sm font-bold leading-none text-[#333] transition-transform hover:scale-105"
        onClick={() => onSelect(agent)}
        type="button"
      >
        <span className="shrink-0">查看详情</span>
        <ArrowUpRight className="size-4 shrink-0" />
      </button>
    </motion.div>
  )
}

function ThumbnailCard({
  agent,
  isActive,
  onClick,
  onHover,
}: {
  agent: SubagentEntry
  isActive: boolean
  onClick: () => void
  onHover: () => void
}) {
  const avatarUri = useMemo(() => agentAvatarDataUri(agent), [agent])

  return (
    <motion.button
      animate={{
        scale: isActive ? 1.04 : 1,
        y: isActive ? -6 : 0,
      }}
      className={cn(
        "relative flex shrink-0 cursor-pointer flex-col overflow-hidden rounded-[22px] border bg-white text-left transition-colors",
        isActive
          ? "border-[#303030] border-2 shadow-lg"
          : "border-black/8 hover:border-black/20",
      )}
      onClick={onClick}
      onMouseEnter={onHover}
      style={{ width: THUMB_W, height: THUMB_H }}
      transition={{ type: "spring", stiffness: 360, damping: 28 }}
      type="button"
    >
      <div
        className={cn(
          "relative mx-3 mt-3 mb-2 flex h-[90px] items-center justify-center overflow-hidden rounded-[14px]",
          isActive ? "bg-[#f4f4f4]" : "bg-[#f0f0f0]",
        )}
      >
        <img
          alt={agent.name}
          className="h-[80px] w-[80px] object-contain opacity-90"
          src={avatarUri}
        />
        <span
          className={cn(
            "absolute right-2 top-2 size-1.5 rounded-full ring-1 ring-black/5",
            statusDotClass(agent.status),
          )}
        />
      </div>

      <div className="flex flex-1 flex-col px-3 pb-3">
        <span className="mb-0.5 text-[10px] font-semibold leading-tight text-[#8b8b8b] truncate">
          {agent.subagentType ?? "子agent"}
        </span>
        <span className="text-[13px] font-bold leading-tight tracking-[-0.02em] text-[#242424] truncate">
          {agent.name}
        </span>
        <div className="mt-auto flex items-center gap-1 pt-1.5">
          <span className={cn("size-1 rounded-full", statusDotClass(agent.status))} />
          <span className="text-[9px] font-medium text-[#9a9a9a]">
            {statusLabel(agent.status)}
          </span>
        </div>
      </div>
    </motion.button>
  )
}

export function SubagentStack({ subagents, onSelect }: SubagentStackProps) {
  const [overlayOpen, setOverlayOpen] = useState(false)
  const [activeIndex, setActiveIndex] = useState(0)

  useEffect(() => {
    if (!overlayOpen) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOverlayOpen(false)
    }
    window.addEventListener("keydown", onKey)
    return () => window.removeEventListener("keydown", onKey)
  }, [overlayOpen])

  if (subagents.length === 0) return null

  const runningCount = subagents.filter(a => a.status === "running").length
  const activeAgent = subagents[activeIndex] ?? subagents[0]

  const handleOpen = () => {
    setActiveIndex(subagents.length - 1)
    setOverlayOpen(true)
  }

  return (
    <>
      <div className="absolute top-3 right-3 z-50" style={{ pointerEvents: "none" }}>
        <motion.button
          animate={{ scale: overlayOpen ? 0.9 : 1 }}
          className="relative cursor-pointer rounded-2xl border border-black/10 bg-white shadow-lg"
          onClick={handleOpen}
          style={{
            width: STACK_W,
            height: STACK_H,
            pointerEvents: "auto",
          }}
          transition={{ type: "spring", stiffness: 300, damping: 26 }}
          whileHover={{ scale: 1.05 }}
          type="button"
        >
          {subagents.slice(-3).map((agent, i, arr) => {
            const isTop = i === arr.length - 1
            return (
              <div
                key={agent.taskId}
                className={cn(
                  "absolute left-1/2 top-1/2 flex flex-col overflow-hidden rounded-xl border",
                  isTop ? "border-white/10 bg-[#303030]" : "border-black/10 bg-white",
                )}
                style={{
                  width: STACK_W - 12,
                  height: STACK_H - 12,
                  marginLeft: -(STACK_W - 12) / 2,
                  marginTop: -(STACK_H - 12) / 2,
                  transform: `translate(${(i - 1) * 3}px, ${(i - 1) * 3}px) rotate(${(i - 1) * 3}deg)`,
                  zIndex: i,
                }}
              >
                <div
                  className={cn(
                    "flex h-8 items-center justify-center overflow-hidden",
                    isTop ? "bg-[#444]" : "bg-[#f0f0f0]",
                  )}
                >
                  <img
                    alt={agent.name}
                    className={cn(
                      "h-7 w-7 object-contain",
                      isTop ? "opacity-80 invert" : "opacity-90",
                    )}
                    src={agentAvatarDataUri(agent)}
                  />
                </div>
                <div className="flex flex-1 flex-col items-center justify-center px-1">
                  <span
                    className={cn(
                      "text-[8px] font-semibold leading-tight",
                      isTop ? "text-white/56" : "text-[#8b8b8b]",
                    )}
                  >
                    {agent.subagentType ?? "子agent"}
                  </span>
                  <span
                    className={cn(
                      "text-[10px] font-bold leading-tight truncate max-w-full",
                      isTop ? "text-white" : "text-[#242424]",
                    )}
                  >
                    {agent.name}
                  </span>
                </div>
              </div>
            )
          })}

          {runningCount > 0 ? (
            <span className="absolute -left-2 -top-2 flex min-w-5 items-center justify-center rounded-full bg-blue-500 px-1.5 text-[11px] font-bold text-white shadow-md ring-2 ring-white">
              {runningCount}
            </span>
          ) : null}
        </motion.button>
      </div>

      <AnimatePresence>
        {overlayOpen ? (
          <motion.div
            animate={{ opacity: 1 }}
            className="fixed inset-0 z-[100] flex flex-col items-center justify-center gap-8 px-6"
            exit={{ opacity: 0 }}
            initial={{ opacity: 0 }}
            onClick={() => setOverlayOpen(false)}
            style={{ background: "rgba(0,0,0,0.5)", backdropFilter: "blur(6px)" }}
            transition={{ duration: 0.2 }}
          >
            <button
              className="absolute right-6 top-6 flex size-10 cursor-pointer items-center justify-center rounded-full bg-white/10 text-white transition-colors hover:bg-white/20"
              onClick={() => setOverlayOpen(false)}
              type="button"
            >
              <X className="size-5" />
            </button>

            <div onClick={(e) => e.stopPropagation()}>
              <AnimatePresence mode="wait">
                <FeaturedCard
                  agent={activeAgent}
                  key={activeAgent.taskId}
                  onSelect={(selected) => {
                    onSelect(selected)
                    setOverlayOpen(false)
                  }}
                />
              </AnimatePresence>
            </div>

            <div
              className="flex max-w-[840px] gap-3.5 overflow-x-auto overflow-y-visible px-1 pt-4 pb-3"
              onClick={(e) => e.stopPropagation()}
              style={{ scrollbarWidth: "thin" }}
            >
              {subagents.map((agent, i) => (
                <ThumbnailCard
                  agent={agent}
                  isActive={i === activeIndex}
                  key={agent.taskId}
                  onClick={() => setActiveIndex(i)}
                  onHover={() => setActiveIndex(i)}
                />
              ))}
            </div>
          </motion.div>
        ) : null}
      </AnimatePresence>
    </>
  )
}
