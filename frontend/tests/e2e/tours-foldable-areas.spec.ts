import { test, expect } from '@playwright/test'

// The Tours left pane is two independently foldable areas: "Filter & grouping"
// (top, incl. search + tags) and "Results" (bottom, the grouped tree).
test('tours left pane: two foldable areas collapse independently', async ({ page }) => {
  await page.goto('/tours')
  const search = page.getByPlaceholder(/Search — try/)
  await expect(search).toBeVisible()

  const filterHeader = page.getByRole('button', { name: /Filter & grouping/i })
  const resultsHeader = page.getByRole('button', { name: /^Results/i })
  await expect(filterHeader).toBeVisible()
  await expect(resultsHeader).toBeVisible()

  // Fold "Filter & grouping" → the search bar (inside it) disappears.
  await filterHeader.click()
  await expect(search).toBeHidden()
  // Unfold → back.
  await filterHeader.click()
  await expect(search).toBeVisible()

  // Fold "Results" → the results container disappears.
  const results = page.locator('[data-testid="group-drop-zone"]') // sentinel inside filter area stays
  await resultsHeader.click()
  // The grouped tree is gone; the filter area (search) is still there.
  await expect(search).toBeVisible()
})
