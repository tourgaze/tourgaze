import { test, expect } from '@playwright/test'

// Resolve a real ride id at runtime — dev-DB ids aren't fixed, so don't hardcode.
async function firstRideId(request: import('@playwright/test').APIRequestContext): Promise<string | null> {
  const acts = await (await request.get('http://localhost:8085/api/activities')).json()
  return acts[0]?.id ?? null
}

// Every replay camera must keep following the rider — the camera used to park
// forever on the first dead-end "hold" frame, which on a slow/dense ride froze
// drone / hollywood entirely (rider flew off-screen).
for (const strategy of ['drone', 'follow', 'topdown'] as const) {
  test(`replay ${strategy} keeps the marker on-screen`, async ({ page, request }) => {
    const id = await firstRideId(request)
    test.skip(!id, 'need a ride with a track')
    const errors: string[] = []
    page.on('pageerror', e => errors.push(`pageerror: ${e.message}`))

    await page.goto(`/tour/${id}`)
    await expect(page.locator('canvas.maplibregl-canvas').first()).toBeVisible({ timeout: 15000 })
    await page.waitForTimeout(1500)

    await page.locator('select').filter({ has: page.locator('option[value="drone"]') }).selectOption(strategy)
    await page.getByRole('button', { name: /^play$/i }).click()
    await page.waitForTimeout(2000)

    const box = await page.locator('canvas.maplibregl-canvas').first().boundingBox()
    const w = box?.width ?? 800, h = box?.height ?? 600
    const xform = await page.locator('.map-hover-cursor').evaluate(el => (el as HTMLElement).style.transform).catch(() => '')
    const m = xform.match(/translate\((-?\d+(?:\.\d+)?)px,\s*(-?\d+(?:\.\d+)?)px\)\s*rotate/)
    const px = m ? Number(m[1]) : NaN, py = m ? Number(m[2]) : NaN

    expect(errors, 'no runtime errors').toEqual([])
    // Generous margin — just needs to be in the neighbourhood of the canvas,
    // not flung thousands of px away.
    expect(px, `${strategy} marker x`).toBeGreaterThan(-w)
    expect(px, `${strategy} marker x`).toBeLessThan(2 * w)
    expect(py, `${strategy} marker y`).toBeGreaterThan(-h)
    expect(py, `${strategy} marker y`).toBeLessThan(2 * h)
  })
}
