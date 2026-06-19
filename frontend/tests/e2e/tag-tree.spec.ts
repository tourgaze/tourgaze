import { test, expect } from '@playwright/test'

const API = 'http://localhost:8085/api/tags'

// The redesigned tag pane: matros-style header actions + filter box, and a
// per-tag Lucide icon that persists to the backend.
test('tag pane has filter + header actions and icons persist', async ({ page, request }) => {
  await page.goto('/settings')
  await page.getByText('Tags', { exact: true }).first().click()

  // Pane chrome.
  await expect(page.getByPlaceholder('Filter tags…')).toBeVisible()
  await expect(page.getByTitle('New root tag')).toBeVisible()
  await expect(page.getByTitle('Refresh')).toBeVisible()

  // Create a root tag with an icon.
  await page.getByTitle('New root tag').click()
  await page.getByPlaceholder('Tag name').fill('__e2e_icon_tag')
  await page.getByText('Icon…').click()
  await page.getByPlaceholder('Search icons…').fill('Mountain')
  await page.getByTitle('Mountain', { exact: true }).first().click()
  await page.getByRole('button', { name: 'Add', exact: true }).click()

  // It persisted with the icon name.
  await expect.poll(async () => {
    const tags = await (await request.get(API)).json()
    return tags.find((t: any) => t.name === '__e2e_icon_tag')?.icon ?? null
  }).toBe('Mountain')

  // Filter narrows the tree to the match.
  await page.getByPlaceholder('Filter tags…').fill('__e2e_icon')
  await expect(page.getByText('__e2e_icon_tag')).toBeVisible()

  // Cleanup.
  const tags = await (await request.get(API)).json()
  const mine = tags.find((t: any) => t.name === '__e2e_icon_tag')
  if (mine) await request.delete(`${API}/${mine.id}`)
})
