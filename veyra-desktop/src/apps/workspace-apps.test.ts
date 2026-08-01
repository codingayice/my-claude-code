import test from "node:test"
import assert from "node:assert/strict"

import { APP_CATEGORY_TABS, WORKSPACE_APPS } from "./workspace-apps.ts"

test("lists the harness workspace apps that are ready", () => {
  const readyAppIds = WORKSPACE_APPS
    .filter((app) => app.status === "ready")
    .map((app) => app.id)
    .sort()

  assert.deepEqual(readyAppIds, ["document", "study"])
})

test("keeps category tabs aligned with registered apps", () => {
  const categoryIds = new Set(APP_CATEGORY_TABS.map((tab) => tab.id))

  for (const app of WORKSPACE_APPS) {
    assert.ok(categoryIds.has(app.category))
  }
})
