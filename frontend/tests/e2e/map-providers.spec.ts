import { test, expect } from '@playwright/test'

const API = 'http://localhost:8085/api'

test('Settings → Map providers: add a custom raster basemap, it appears in the catalog', async ({ page, request }) => {
  // clean any prior test rows
  for (const p of await (await request.get(`${API}/map-providers`)).json()) {
    if (p.name === 'E2E Topo') await request.delete(`${API}/map-providers/${p.id}`)
  }

  await page.goto('/settings?cat=maps')
  await expect(page.getByText('Custom basemaps', { exact: false })).toBeVisible({ timeout: 10000 })

  await page.getByRole('button', { name: /Add map provider/ }).click()
  await page.getByPlaceholder(/Name \(e\.g\./).fill('E2E Topo')
  await page.getByPlaceholder(/\{z\}\/\{x\}\/\{y\}/).fill('https://tile.openstreetmap.org/{z}/{x}/{y}.png')
  await page.getByRole('button', { name: 'Add', exact: true }).click()

  // Shows in the section list…
  await expect(page.getByText('E2E Topo', { exact: false })).toBeVisible()
  // …and is merged into the basemap catalog the map picker reads.
  await expect.poll(async () => {
    const cat = await (await request.get(`${API}/tile-providers`)).json()
    return cat.some((p: any) => p.label === 'E2E Topo')
  }).toBeTruthy()

  // cleanup
  for (const p of await (await request.get(`${API}/map-providers`)).json()) {
    if (p.name === 'E2E Topo') await request.delete(`${API}/map-providers/${p.id}`)
  }
})
