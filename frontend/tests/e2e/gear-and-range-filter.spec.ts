import { test, expect } from '@playwright/test'

const API = 'http://localhost:8085/api'

test('gear: facet filters the Tours list by gear name', async ({ page, request }) => {
  // Create gear and attach it to the first activity.
  const gear = await (await request.post(`${API}/gear`, { data: { name: 'HiBike Test', type: 'bike' } })).json()
  const acts = await (await request.get(`${API}/activities`)).json()
  test.skip(acts.length < 1 || !acts[0].name, 'need a named activity')
  const act = acts[0]
  const origGear = act.gearId ?? null
  await request.patch(`${API}/activities/${act.id}`, { data: { gearId: gear.id } })

  try {
    await page.goto('/tours')
    const bar = page.getByPlaceholder(/Search — try/)
    await expect(bar).toBeVisible()

    // gear:hibike (substring, case-insensitive) → chip appears, activity shown.
    await bar.click()
    await bar.fill('gear:hibike')
    await bar.press('Enter')
    await expect(page.getByText('gear:hibike', { exact: false })).toBeVisible()
    await expect(page.getByText(act.name, { exact: false }).first()).toBeVisible()
  } finally {
    await request.patch(`${API}/activities/${act.id}`, { data: { gearId: origGear } })
    await request.delete(`${API}/gear/${gear.id}`)
  }
})

test('year range +/- and between parse into chips', async ({ page }) => {
  await page.goto('/tours')
  const bar = page.getByPlaceholder(/Search — try/)
  await expect(bar).toBeVisible()

  for (const [typed, chip] of [['year:2014+', 'year:2014+'], ['year:2010..2012', 'year:2010..2012']] as const) {
    await bar.click()
    await bar.fill(typed)
    await bar.press('Enter')
    await expect(page.getByText(chip, { exact: false })).toBeVisible()
    // clear for next
    await page.getByTitle('Clear search').click()
  }
})
