import { test, expect } from '@playwright/test'

// Smoke test for the JIRA-style faceted Tours search + saved presets.
test('faceted search filters and a preset round-trips', async ({ page, request }) => {
  // Clean slate for OUR preset only — other specs share this global table.
  const existing = await (await request.get('http://localhost:8085/api/filter-presets')).json()
  for (const p of existing) {
    if (p.name === 'Bike rides') await request.delete(`http://localhost:8085/api/filter-presets/${p.id}`)
  }

  await page.goto('/tours')

  const bar = page.getByPlaceholder(/Search — try/)
  await expect(bar).toBeVisible()

  // Total rides shown before filtering.
  const counter = page.locator('text=/\\d+ tours?/').first()
  await expect(counter).toBeVisible()

  // Type a facet + commit it → a chip should appear.
  await bar.click()
  await bar.fill('sport:cycling')
  await bar.press('Enter')
  await expect(page.locator('text=sport:cycling')).toBeVisible()

  // Save it as a preset via the in-app dialog (replaced window.prompt).
  await page.getByTitle('Save current search as preset').click()
  const dialog = page.getByRole('dialog', { name: 'Save search preset' })
  await expect(dialog).toBeVisible()
  await dialog.getByRole('textbox').fill('Bike rides')
  await dialog.getByRole('button', { name: 'Save preset' }).click()

  // It should appear in the dropdown and be persisted server-side.
  await expect(page.locator('option', { hasText: 'Bike rides' })).toBeAttached()
  const saved = await (await request.get('http://localhost:8085/api/filter-presets')).json()
  expect(saved.some((p: any) => p.name === 'Bike rides' && p.query.includes('sport:cycling'))).toBeTruthy()
})
