import { test, expect } from '@playwright/test'

const API = 'http://localhost:8085/api'
const gearOf = async (request: any, id: string) =>
  (await (await request.get(`${API}/activities`)).json()).find((a: any) => a.id === id)?.gearId ?? null

test('EditTour gear picker assigns and clears gear on an existing ride', async ({ page, request }) => {
  const gear = await (await request.post(`${API}/gear`, { data: { name: 'EditPick Bike', type: 'mtb' } })).json()
  const acts = await (await request.get(`${API}/activities`)).json()
  test.skip(acts.length < 1, 'need an activity')
  const act = acts[0]
  const orig = act.gearId ?? null

  const gearSelect = () => page.locator('label', { hasText: 'Gear' }).locator('select')
  try {
    await page.goto(`/tours?id=${act.id}`)
    await page.getByRole('button', { name: 'Edit', exact: true }).click()
    await expect(page.getByText('Edit tour')).toBeVisible()

    // Assign the gear via the picker, save. Wait for the just-created gear to
    // appear as an option first — the EditTour gear list is a separate query, so
    // selecting before it has refetched is the source of the old flakiness.
    await expect(gearSelect()).toBeVisible()
    await expect(gearSelect().locator(`option[value="${gear.id}"]`)).toBeAttached()
    await gearSelect().selectOption(gear.id)
    await page.getByRole("button", { name: /^Save/ }).last().click()
    await expect.poll(() => gearOf(request, act.id)).toBe(gear.id)

    // Re-open, clear it (— none —), save.
    await page.getByRole('button', { name: 'Edit', exact: true }).click()
    await expect(gearSelect()).toBeVisible()
    await gearSelect().selectOption('')
    await page.getByRole("button", { name: /^Save/ }).last().click()
    await expect.poll(() => gearOf(request, act.id)).toBe(null)
  } finally {
    await request.patch(`${API}/activities/${act.id}`, { data: { gearId: orig ?? '' } })
    await request.delete(`${API}/gear/${gear.id}`)
  }
})

// (Removed: "bulk gear control assigns gear to filtered rides" — the Gear dock
// no longer bulk-mutates ride metadata; it adds a `gear:` search filter instead.
// Gear filtering is covered by gear-and-range-filter.spec.ts, and per-ride gear
// assignment by the EditTour picker test above.)
