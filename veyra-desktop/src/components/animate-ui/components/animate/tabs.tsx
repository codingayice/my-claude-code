import {
  Tabs as TabsPrimitive,
  TabsList as TabsListPrimitive,
  TabsTrigger as TabsTriggerPrimitive,
  TabsContent as TabsContentPrimitive,
  TabsContents as TabsContentsPrimitive,
  TabsHighlight as TabsHighlightPrimitive,
  TabsHighlightItem as TabsHighlightItemPrimitive,
  type TabsProps as TabsPrimitiveProps,
  type TabsListProps as TabsListPrimitiveProps,
  type TabsTriggerProps as TabsTriggerPrimitiveProps,
  type TabsContentProps as TabsContentPrimitiveProps,
  type TabsContentsProps as TabsContentsPrimitiveProps,
} from "@/components/animate-ui/primitives/animate/tabs"
import { cn } from "@/lib/utils"

type TabsProps = TabsPrimitiveProps

function Tabs({ className, ...props }: TabsProps) {
  return (
    <TabsPrimitive
      className={cn("flex flex-col gap-2", className)}
      {...props}
    />
  )
}

type TabsListProps = TabsListPrimitiveProps

function TabsList({ className, ...props }: TabsListProps) {
  return (
    <TabsHighlightPrimitive className="absolute inset-0 z-0 rounded-md border border-transparent bg-background shadow-sm dark:border-input dark:bg-input/30">
      <TabsListPrimitive
        className={cn(
          "inline-flex h-9 w-fit items-center justify-center rounded-lg bg-muted p-[3px] text-muted-foreground",
          className
        )}
        {...props}
      />
    </TabsHighlightPrimitive>
  )
}

type TabsTriggerProps = TabsTriggerPrimitiveProps

function TabsTrigger({ className, ...props }: TabsTriggerProps) {
  return (
    <TabsHighlightItemPrimitive value={props.value} className="flex-1">
      <TabsTriggerPrimitive
        className={cn(
          "inline-flex h-[calc(100%-1px)] w-full flex-1 items-center justify-center gap-1.5 rounded-md px-2 py-1 text-sm font-medium whitespace-nowrap text-muted-foreground transition-colors duration-500 ease-in-out focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-1 focus-visible:outline-ring disabled:pointer-events-none disabled:opacity-50 data-[state=active]:text-foreground [&_svg]:pointer-events-none [&_svg]:shrink-0 [&_svg:not([class*='size-'])]:size-4",
          className
        )}
        {...props}
      />
    </TabsHighlightItemPrimitive>
  )
}

type TabsContentsProps = TabsContentsPrimitiveProps

function TabsContents(props: TabsContentsProps) {
  return <TabsContentsPrimitive {...props} />
}

type TabsContentProps = TabsContentPrimitiveProps

function TabsContent({ className, ...props }: TabsContentProps) {
  return (
    <TabsContentPrimitive
      className={cn("outline-none", className)}
      {...props}
    />
  )
}

export {
  Tabs,
  TabsList,
  TabsTrigger,
  TabsContents,
  TabsContent,
  type TabsProps,
  type TabsListProps,
  type TabsTriggerProps,
  type TabsContentsProps,
  type TabsContentProps,
}
