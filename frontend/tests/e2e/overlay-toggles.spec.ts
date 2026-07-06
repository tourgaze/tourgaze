import { test, expect } from '@playwright/test'

// The Photos / Highlights toolbar checkboxes must be the single source of
// truth for the map pins. Regression: the map used to OR the Photos checkbox
// with "gallery tab open" (`bottomView === 'photos'`) — and since the bottom
// tab is persisted, photo pins showed forever once the gallery had been
// opened, no matter what the checkbox said.
async function rideWithOverlays(request: import('@playwright/test').APIRequestContext): Promise<string | null> {
  const acts = await (await request.get('http://localhost:8085/api/activities')).json()
  for (const a of acts) {
    const media = await (await request.get(`http://localhost:8085/api/activities/${a.id}/media`)).json().catch(() => [])
    if (media?.length) return a.id
  }
  return null
}

test('unchecking Photos/Highlights removes the map pins — even with the gallery tab open', async ({ page, request }) => {
  test.setTimeout(60_000)
  await page.addInitScript(() => localStorage.setItem('tourgaze.welcome.seen.v1', 'true'))
  // Clean layout: persisted checkbox / bottom-tab state must not skew the run.
  await page.addInitScript(() => {
    for (const k of Object.keys(localStorage)) if (k.startsWith('tourgaze.layout.')) localStorage.removeItem(k)
  })
  const id = await rideWithOverlays(request)
  test.skip(!id, 'need a ride with photos')

  await page.goto(`/tours?id=${id}`)
  await expect(page.locator('canvas.maplibregl-canvas').first()).toBeVisible({ timeout: 15000 })
  await page.waitForTimeout(2500)

  const photoPins = () => page.locator('.map-photo-pin').count()
  const highlightPins = () => page.locator('.map-highlight').count()
  expect(await photoPins(), 'photo pins visible while checkbox is on').toBeGreaterThan(0)

  // Uncheck both toolbar toggles → all pins gone.
  await page.locator('label', { hasText: 'Photos' }).locator('input[type=checkbox]').first().uncheck()
  await page.locator('label', { hasText: 'Highlights' }).locator('input[type=checkbox]').first().uncheck()
  await expect.poll(photoPins).toBe(0)
  await expect.poll(highlightPins).toBe(0)

  // Open the bottom Photos (gallery) tab — pins must STAY hidden.
  await page.getByRole('button', { name: /Photos/ }).last().click()
  await page.waitForTimeout(800)
  expect(await photoPins(), 'checkbox off must win over the open gallery tab').toBe(0)

  // Re-check → pins return.
  await page.locator('label', { hasText: 'Photos' }).locator('input[type=checkbox]').first().check()
  await expect.poll(photoPins).toBeGreaterThan(0)
})
