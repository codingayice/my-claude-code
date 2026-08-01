export function shouldCloseProcessOnAssistantMessage(hasToolRequests: boolean) {
  return !hasToolRequests
}

export function isActiveStreamingSegment(segment: { streaming?: boolean } | undefined) {
  return segment?.streaming === true
}
