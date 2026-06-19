import { test, expect } from '@playwright/test'

const API = 'http://localhost:8085'

// Regression: dropping a parent tag onto the Tours "group by" zone pivots to
// tag-children grouping. Saving that as a preset must persist the parent tag id
// so recalling the preset restores the same grouping (previously it lost the
// tag and fell back to the empty "drop a tag" state).
test('tag-children grouping survives save-preset → switch → re-apply', async ({ page, request }) => {
  // Remove any leftover preset of our own name (other specs share this global
  // table, so don't wipe theirs — just clear ours).
  for (const p of await (await request.get(`${API}/api/filter-presets`)).json()) {
    if (p.name === 'ByTag') await request.delete(`${API}/api/filter-presets/${p.id}`)
  }
  // Need a tag to group by.
  const tags = await (await request.get(`${API}/api/tags`)).json()
  test.skip(tags.length === 0, 'no tags in DB to group by')
  const tag = tags[0]

  await page.goto('/tours')
  await expect(page.getByPlaceholder(/Search — try/)).toBeVisible()

  const dropZone = page.getByTestId('group-drop-zone')

  // Simulate dropping the tag chip (HTML5 DnD with the app's custom mime type).
  const dt = await page.evaluateHandle(([id]) => {
    const d = new DataTransfer()
    d.setData('text/tourgaze-tag', id as string)
    return d
  }, [tag.id])
  await dropZone.dispatchEvent('drop', { dataTransfer: dt })

  // Drop zone now shows "by <tag name> children".
  await expect(dropZone).toContainText(tag.name)

  // Save as a preset and confirm the server stored the parent tag id.
  page.once('dialog', d => d.accept('ByTag'))
  await page.getByTitle('Save current search as preset').click()
  await page.waitForResponse(r => r.url().includes('/api/filter-presets') && r.request().method() === 'POST')
  const saved = await (await request.get(`${API}/api/filter-presets`)).json()
  const mine = saved.find((p: any) => p.name === 'ByTag')
  expect(mine?.groupBy).toBe('tag-children')
  expect(mine?.groupTagId, 'parent tag persisted').toBe(tag.id)

  // Switch grouping away…
  await page.getByRole('button', { name: 'Year', exact: true }).click()
  await expect(dropZone).not.toContainText(tag.name)

  // …then re-apply the preset from the dropdown → grouping restores WITH the tag.
  await page.locator('select').first().selectOption({ label: 'ByTag' })
  await expect(dropZone, 'tag-children grouping restored from preset').toContainText(tag.name)
})
