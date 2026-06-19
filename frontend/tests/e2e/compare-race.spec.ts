import { test, expect, type Page } from '@playwright/test'

/**
 * Compare / in-place race e2e. Covers the useRaceCompare composable end-to-end:
 *   1. Ticking a similar ride shows an inline race bar (no separate panel).
 *   2. The bar carries HR + gap text and is coloured by gap distance.
 *   3. Pressing Play collapses the table to just the selected racer.
 *   4. No unresolved-component warnings / page errors (guards the <X> icon
 *      import regression and the composable wiring).
 */

function genTrack(n: number, latBase: number) {
  const out: Array<{ lat: number; lon: number; altM: number; hr: number; speedMs: number }> = []
  for (let i = 0; i < n; i++) {
    const t = i / n
    out.push({ lat: latBase + t * 0.08, lon: 8.7 + t * 0.10, altM: 430 + Math.round(t * 200), hr: 130 + Math.round(t * 30), speedMs: 6 })
  }
  return out
}

const TRACK_A = genTrack(240, 47.5)
const TRACK_B = genTrack(260, 47.5)   // longer → ends up "ahead", so a non-zero gap

function act(id: string, name: string, track: { lat: number; lon: number }[]) {
  return {
    id, name, activityType: 'cycling', description: null,
    startTime: '2024-06-01T07:00:00Z', endTime: '2024-06-01T08:00:00Z',
    durationS: 240, movingTimeS: 240, distanceKm: 12.0, elevationGainM: 200,
    avgHr: 150, maxHr: 170, avgSpeedKmh: 25, maxSpeedKmh: 40,
    weatherTempC: 18, weatherHumidityPct: 60, weatherWindKph: 5, weatherCondition: 'clear',
    importedAt: '2024-06-01T10:00:00Z', sourceFilename: 't.fit', originalFilename: 't.fit',
    sourceFormat: 'fit', sourceHash: id, startLat: track[0].lat, startLon: track[0].lon,
    endLat: track[track.length - 1].lat, endLon: track[track.length - 1].lon, tagIds: [],
  }
}

const A = 'agp_race_a_xxxxxxxxxxxxxxxxxx'
const B = 'agp_race_b_xxxxxxxxxxxxxxxxxx'
const ACTIVITIES = [act(A, 'Race Ride A', TRACK_A), act(B, 'Race Ride B', TRACK_B)]

async function mockApi(page: Page) {
  // NOTE: Playwright checks routes in reverse registration order (last wins),
  // so register GENERIC fallbacks first and SPECIFIC routes last.
  await page.route('**/api/activities/**/track/chart', (r) => r.fulfill({ json: TRACK_A.slice(0, 40) }))
  await page.route('**/api/activities/**/track', (r) => r.fulfill({ json: TRACK_A }))
  await page.route('**/api/activities/**/similar', (r) => r.fulfill({ json: [] }))
  await page.route('**/api/activities/**/media', (r) => r.fulfill({ json: [] }))
  await page.route('**/api/markers/activity/**', (r) => r.fulfill({ json: [] }))
  await page.route('**/section.json', (r) => r.fulfill({ json: [] }))
  await page.route('**/api/tags', (r) => r.fulfill({ json: [] }))
  await page.route('**/api/users', (r) => r.fulfill({ json: [{ id: 'u', username: 'tester', displayName: 'Tester', weightKg: 75 }] }))
  await page.route('**/api/settings', (r) => r.fulfill({ json: [] }))
  await page.route('**/api/tile-providers', (r) => r.fulfill({ json: [
    { id: 'osm', label: 'OSM', description: '', type: 'raster', renderer: 'maplibre', urlTemplate: '/api/tiles/{z}/{x}/{y}.png?providerid=osm', styleUrl: null, maxZoom: 19, attribution: '', isElevation: false, isDark: false },
  ] }))
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=', 'base64')
  await page.route('**/api/tiles/**', (r) => r.fulfill({ body: png, contentType: 'image/png' }))
  await page.route('**/api/inbox', (r) => r.fulfill({ json: [] }))
  // Specific routes registered LAST so they take priority over the generics.
  await page.route('**/api/activities', (r) => r.fulfill({ json: ACTIVITIES }))
  await page.route(`**/api/activities/${B}/track`, (r) => r.fulfill({ json: TRACK_B }))
  await page.route(`**/api/activities/${A}/track`, (r) => r.fulfill({ json: TRACK_A }))
  await page.route(`**/api/activities/${A}/similar`, (r) => r.fulfill({ json: [
    { id: B, name: 'Race Ride B', activityType: 'cycling', startTime: '2024-06-01T07:00:00Z', distanceKm: 13.0, durationS: 240, startLocation: null, score: 0.92, matchType: 'gps' },
  ] }))
}

test.describe('compare / race', () => {
  test('inline race bar appears, is gap-coloured, and replay collapses to racers', async ({ page }) => {
    const warnings: string[] = []
    page.on('console', (m) => { if (m.type() === 'warning' || m.type() === 'error') warnings.push(m.text()) })
    const pageErrors: string[] = []
    page.on('pageerror', (e) => pageErrors.push(e.message))

    await mockApi(page)
    await page.goto('/tours')
    await page.getByText('Race Ride A').click()
    await page.waitForSelector('.maplibregl-canvas', { timeout: 8000 })

    // Open the Compare tab in the bottom panel.
    await page.getByRole('button', { name: /^Compare/ }).click()

    const row = page.locator('[data-compare-row]', { hasText: 'Race Ride B' })
    await expect(row).toBeVisible()
    await row.locator('input[type=checkbox]').check()

    // Inline bar shows HR (♥) + a gap reading, no separate race panel.
    await expect(page.getByText(/♥/).first()).toBeVisible({ timeout: 8000 })
    await expect(page.getByText(/even|[+−]\s?\d/).first()).toBeVisible()

    // The bar is coloured by gap distance (one of the gapColor ramp values).
    const barBg = await page.locator('[data-compare-row] span[style*="rgb"]').filter({ hasText: /♥|even|m/ }).first()
      .evaluate((el) => getComputedStyle(el).backgroundColor)
    expect(['rgb(16, 185, 129)', 'rgb(234, 179, 8)', 'rgb(249, 115, 22)', 'rgb(239, 68, 68)']).toContain(barBg)

    // Press Play → table collapses to just the one selected racer.
    await page.getByRole('button', { name: /^play$/i }).click()
    await page.waitForTimeout(1200)
    await expect(page.locator('[data-compare-row]')).toHaveCount(1)

    // No unresolved-component warnings (e.g. the <X> icon) and no page errors.
    expect(warnings.filter((w) => /Failed to resolve component/.test(w))).toEqual([])
    expect(pageErrors).toEqual([])
  })

  test('off-screen ghost shows an edge arrow without crashing Vue', async ({ page }) => {
    const pageErrors: string[] = []
    page.on('pageerror', (e) => pageErrors.push(e.message))

    await mockApi(page)
    await page.goto('/tours')
    await page.getByText('Race Ride A').click()
    await page.waitForSelector('.maplibregl-canvas', { timeout: 8000 })
    await page.getByRole('button', { name: /^Compare/ }).click()
    await page.locator('[data-compare-row]', { hasText: 'Race Ride B' }).locator('input[type=checkbox]').check()
    await page.getByText(/♥/).first().waitFor({ timeout: 8000 })
    await page.getByRole('button', { name: /^play$/i }).click()

    // Pan the rider hard off-screen → the off-screen branch appends an edge
    // arrow. The old code appended into the Vue-managed map div and crashed the
    // renderer ("Cannot set properties of null"); the canvas-container target
    // must not.
    const canvas = page.locator('.maplibregl-canvas').first()
    const box = (await canvas.boundingBox())!
    const cx = box.x + box.width / 2, cy = box.y + box.height / 2
    await page.mouse.move(cx, cy)
    await page.mouse.down()
    await page.mouse.move(cx - box.width, cy - box.height, { steps: 12 })
    await page.mouse.up()
    await page.waitForTimeout(1000)

    await expect(page.locator('.map-ghost-edge')).toHaveCount(1)
    expect(pageErrors).toEqual([])
  })

  test('compare detail page shows baseline-vs-compare stats', async ({ page }) => {
    const pageErrors: string[] = []
    page.on('pageerror', (e) => pageErrors.push(e.message))
    await mockApi(page)
    await page.goto(`/compare/${A}/${B}`)

    await expect(page.getByRole('heading', { name: /Ride comparison/ })).toBeVisible({ timeout: 8000 })
    await expect(page.getByText('Baseline')).toBeVisible()
    await expect(page.getByText('Compare', { exact: true })).toBeVisible()
    // A few of the stat rows render.
    await expect(page.getByText('Distance', { exact: true })).toBeVisible()
    await expect(page.getByText('Avg speed', { exact: true })).toBeVisible()
    await expect(page.getByText('Elevation gain', { exact: true })).toBeVisible()
    // Both ride names show in the header cards.
    await expect(page.getByText('Race Ride A')).toBeVisible()
    await expect(page.getByText('Race Ride B')).toBeVisible()
    expect(pageErrors).toEqual([])
  })

  test('Play works again after a replay has run to the end', async ({ page }) => {
    await mockApi(page)
    await page.goto('/tours')
    await page.getByText('Race Ride A').click()
    await page.waitForSelector('.maplibregl-canvas', { timeout: 8000 })
    await page.waitForTimeout(500)

    const speed = page.locator('input[type=range]').first()
    const playBtn = page.getByRole('button', { name: /^play$/i })

    // Run it to the end fast, then it should park at the finish (button → Play).
    await speed.fill('50')
    await playBtn.click()
    await expect(playBtn).toBeVisible({ timeout: 8000 })

    // Press Play again at a slow speed — with the bug it instantly re-hit the
    // end-of-track stop and flipped straight back to Play. Fixed, it restarts
    // from the top and keeps playing.
    await speed.fill('2')
    await playBtn.click()
    await page.waitForTimeout(700)
    await expect(page.getByRole('button', { name: /^pause$/i })).toBeVisible()
  })
})
