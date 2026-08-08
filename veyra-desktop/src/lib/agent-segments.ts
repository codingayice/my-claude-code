export function shouldCloseProcessOnAssistantMessage(_hasToolRequests: boolean) {
  // A completed assistant message ends the preceding tool round. Any tool calls
  // declared by this message start a new process segment when their events arrive.
  // This keeps cold replay ordered the same as the live token stream.
  return true
}

export function isActiveStreamingSegment(segment: { streaming?: boolean } | undefined) {
  return segment?.streaming === true
}
