import { test, expect } from '@playwright/test'

test('About page: FOSS content + goat brand mark', async ({ page }) => {
  await page.goto('/tours')
  // Navigate via the activity-bar Info button.
  await page.getByRole('button', { name: /About TourGaze/i }).click()
  await expect(page).toHaveURL(/\/about$/)

  await expect(page.getByRole('heading', { name: 'TourGaze' })).toBeVisible()
  await expect(page.getByText('Free & open source', { exact: false })).toBeVisible()
  await expect(page.getByText('Local-first', { exact: false }).first()).toBeVisible()
  await expect(page.getByText('OpenStreetMap', { exact: false }).first()).toBeVisible()
  await expect(page.getByText('View on GitHub', { exact: false }).first()).toBeVisible()

  // The favicon is the goat svg, served from /public.
  const favicon = await page.locator('link[rel="icon"]').getAttribute('href')
  expect(favicon).toContain('goat.svg')
  const res = await page.request.get('http://localhost:5173/goat.svg')
  expect(res.status()).toBe(200)
  expect(await res.text()).toContain('<svg')
})
