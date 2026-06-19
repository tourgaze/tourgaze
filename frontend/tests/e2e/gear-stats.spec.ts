import { test, expect } from '@playwright/test'

const API = 'http://localhost:8085/api'

test('dashboard shows a per-gear breakdown for each bike', async ({ page, request }) => {
  const mtb = await (await request.post(`${API}/gear`, { data: { name: 'Zzz MTB', type: 'mtb' } })).json()
  const race = await (await request.post(`${API}/gear`, { data: { name: 'Zzz Racebike', type: 'racebike' } })).json()
  const acts = await (await request.get(`${API}/activities`)).json()
  test.skip(acts.length < 2, 'need two activities')
  const a0 = acts[0], a1 = acts[1]
  const orig0 = a0.gearId ?? null, orig1 = a1.gearId ?? null
  await request.patch(`${API}/activities/${a0.id}`, { data: { gearId: mtb.id } })
  await request.patch(`${API}/activities/${a1.id}`, { data: { gearId: race.id } })

  try {
    await page.goto('/dashboard')
    await expect(page.getByText('By gear', { exact: false })).toBeVisible({ timeout: 10000 })
    await expect(page.getByText('Zzz MTB', { exact: false })).toBeVisible()
    await expect(page.getByText('Zzz Racebike', { exact: false })).toBeVisible()
    // type badges render
    await expect(page.getByText('mtb', { exact: true }).first()).toBeVisible()
    await expect(page.getByText('Distance per gear', { exact: false })).toBeVisible()
  } finally {
    await request.patch(`${API}/activities/${a0.id}`, { data: { gearId: orig0 } })
    await request.patch(`${API}/activities/${a1.id}`, { data: { gearId: orig1 } })
    await request.delete(`${API}/gear/${mtb.id}`)
    await request.delete(`${API}/gear/${race.id}`)
  }
})
