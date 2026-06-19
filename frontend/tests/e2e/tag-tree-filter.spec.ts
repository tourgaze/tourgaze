import { test, expect } from '@playwright/test'

const API = 'http://localhost:8085/api'

// The Tours list now uses the matros-style TagTree in "pick" mode: double-click
// or Enter on a tag adds a `tag:` WHERE condition, matched transitively (a
// parent tag matches rides tagged with any descendant).
test('tag tree double-click filters the Tours list recursively', async ({ page, request }) => {
  const mk = async (name: string, parentId?: string) =>
    (await request.post(`${API}/tags`, { data: { name, color: '#3b82f6', parentId } })).json()
  const parent = await mk('__e2e_parent')
  const child = await mk('__e2e_child', parent.id)

  const acts = await (await request.get(`${API}/activities`)).json()
  test.skip(acts.length < 1 || !acts[0].name, 'need at least one named activity')
  const act = acts[0]
  const originalTagIds: string[] = act.tagIds ?? []
  // Tag the activity with the CHILD only.
  await request.patch(`${API}/activities/${act.id}`, { data: { tagIds: [child.id] } })

  try {
    await page.goto('/tours')
    await expect(page.getByPlaceholder(/Search — try/)).toBeVisible()

    // Make sure the tag tree dock is open.
    const anyRow = page.locator('[data-tag-row-id]').first()
    if (!(await anyRow.isVisible().catch(() => false))) {
      await page.getByRole('button', { name: /dbl-click filters/ }).click()
    }
    const parentRow = page.locator(`[data-tag-row-id="${parent.id}"]`)
    await expect(parentRow).toBeVisible()

    // Filter by the PARENT tag.
    await parentRow.dblclick()
    await expect(page.getByTitle('Remove filter')).toHaveCount(1)

    // Recursion: the child-tagged ride survives a parent-tag filter.
    await expect(page.getByText(act.name, { exact: false }).first()).toBeVisible()

    // Dedup: double-clicking the same tag again doesn't add a second chip.
    await parentRow.dblclick()
    await expect(page.getByTitle('Remove filter')).toHaveCount(1)
  } finally {
    await request.patch(`${API}/activities/${act.id}`, { data: { tagIds: originalTagIds } })
    await request.delete(`${API}/tags/${child.id}`)
    await request.delete(`${API}/tags/${parent.id}`)
  }
})
