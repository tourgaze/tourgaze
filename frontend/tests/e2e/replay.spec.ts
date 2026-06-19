import { test, expect, type Page } from '@playwright/test'

/**
 * Replay-specific e2e tests. Started after the strategy-interface refactor
 * surfaced a regression where `camera` could be null when Play was clicked
 * and the camera would silently never follow. These tests assert:
 *   1. The marker appears after clicking Play.
 *   2. The marker MOVES across frames (replay tick is active).
 *   3. The map transform centre changes (camera follows the marker).
 *   4. Pause stops both.
 */

// Synthetic track with enough samples to drive a few seconds of replay.
function genTrack(n = 200) {
  const out: Array<{ lat: number; lon: number; altM: number; hr: number; speedMs: number }> = []
  for (let i = 0; i < n; i++) {
    const t = i / n
    out.push({
      lat: 47.5 + t * 0.08,
      lon: 8.7 + t * 0.10,
      altM: 430 + Math.round(t * 200),
      hr: 140 + Math.round(t * 25),
      speedMs: 6 + Math.sin(t * Math.PI) * 1.5,
    })
  }
  return out
}

const MOCK_TRACK = genTrack(240)

const MOCK_ACTIVITIES = [{
  id: 'agp_replay_test_act_id_xyz',
  name: 'Replay Smoke Test',
  activityType: 'cycling',
  description: null,
  startTime: '2024-06-01T07:00:00Z',
  endTime: '2024-06-01T08:00:00Z',
  durationS: 240,
  movingTimeS: 240,
  distanceKm: 12.0,
  elevationGainM: 200,
  avgHr: 150,
  maxHr: 170,
  avgSpeedKmh: 25.0,
  maxSpeedKmh: 40.0,
  weatherTempC: 18,
  weatherHumidityPct: 60,
  weatherWindKph: 5,
  weatherCondition: 'clear',
  importedAt: '2024-06-01T10:00:00Z',
  sourceFilename: 'test.fit',
  originalFilename: 'replay-test.fit',
  sourceFormat: 'fit',
  sourceHash: 'abc',
  startLat: MOCK_TRACK[0].lat,
  startLon: MOCK_TRACK[0].lon,
  endLat: MOCK_TRACK[MOCK_TRACK.length - 1].lat,
  endLon: MOCK_TRACK[MOCK_TRACK.length - 1].lon,
  tagIds: [],
}]

async function mockApi(page: Page) {
  await page.route('**/api/activities', (route) => route.fulfill({ json: MOCK_ACTIVITIES }))
  await page.route('**/api/activities/**/track', (route) => route.fulfill({ json: MOCK_TRACK }))
  await page.route('**/api/activities/**/track/chart', (route) => route.fulfill({ json: MOCK_TRACK.slice(0, 40) }))
  await page.route('**/api/tags', (route) => route.fulfill({ json: [] }))
  await page.route('**/api/users', (route) => route.fulfill({ json: [{ id: 'u', username: 'tester', displayName: 'Tester', weightKg: 75 }] }))
  await page.route('**/api/settings', (route) => route.fulfill({ json: [] }))
  await page.route('**/api/tile-providers', (route) => route.fulfill({ json: [
    { id: 'osm', label: 'OSM', description: '', type: 'raster', renderer: 'maplibre', urlTemplate: '/api/tiles/{z}/{x}/{y}.png?providerid=osm', styleUrl: null, maxZoom: 19, attribution: '', isElevation: false, isDark: false },
    { id: 'terrain', label: 'Hillshade', description: '', type: 'raster', renderer: 'maplibre', urlTemplate: '/api/tiles/{z}/{x}/{y}.png?providerid=terrain', styleUrl: null, maxZoom: 15, attribution: '', isElevation: true, isDark: false },
  ] }))
  // 1×1 transparent PNG for tiles so MapLibre doesn't 404
  const pngBytes = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=', 'base64')
  await page.route('**/api/tiles/**', (route) => route.fulfill({ body: pngBytes, contentType: 'image/png' }))
  await page.route('**/api/inbox', (route) => route.fulfill({ json: [] }))
}

test.describe('replay', () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page)
  })

  test('marker appears and moves after pressing Play', async ({ page }) => {
    await page.goto('/tours')
    await page.getByText('Replay Smoke Test').click()

    // Wait for the map canvas + track-line layer — proxy for "track data
    // loaded into MapLibre, withDist populated, playback can actually start."
    await page.waitForSelector('.maplibregl-canvas', { timeout: 8000 })
    await page.waitForTimeout(800)  // settle: addTrackOverlays + renderTrack + initial flyToTrack

    await page.getByRole('button', { name: /^play$/i }).click()

    const cursor = page.locator('.map-hover-cursor')
    await expect(cursor).toBeVisible({ timeout: 5000 })

    // Read cursor transform twice with a delay — replay should move it.
    const before = await cursor.evaluate((el) => (el as HTMLElement).style.transform)
    await page.waitForTimeout(1200)
    const after = await cursor.evaluate((el) => (el as HTMLElement).style.transform)

    expect(before).toBeTruthy()
    expect(after).toBeTruthy()
    expect(after).not.toBe(before)
  })

  test('camera follows the marker (cursor stays inside the canvas)', async ({ page }) => {
    await page.goto('/tours')
    await page.getByText('Replay Smoke Test').click()
    await page.waitForSelector('.maplibregl-canvas', { timeout: 8000 })
    await page.waitForTimeout(800)

    await page.getByRole('button', { name: /^play$/i }).click()
    const cursor = page.locator('.map-hover-cursor')
    await expect(cursor).toBeVisible({ timeout: 5000 })

    // Sample the cursor's pixel position several times. If the camera follows,
    // the cursor stays roughly stationary on screen (within the strategy's
    // offset zone). If the camera doesn't follow, the cursor drifts off the
    // canvas because the rider keeps moving while the camera stays put.
    const positions: Array<{ x: number; y: number }> = []
    for (let i = 0; i < 5; i++) {
      await page.waitForTimeout(300)
      const box = await cursor.boundingBox()
      if (box) positions.push({ x: box.x + box.width / 2, y: box.y + box.height / 2 })
    }

    const canvas = await page.locator('.maplibregl-canvas').boundingBox()
    expect(canvas).not.toBeNull()
    expect(positions.length).toBeGreaterThanOrEqual(3)

    // Every sample must be inside the canvas. A non-following camera would
    // let the marker drift past the canvas edge as the rider advances.
    for (const p of positions) {
      expect(p.x).toBeGreaterThan(canvas!.x - 20)
      expect(p.x).toBeLessThan(canvas!.x + canvas!.width + 20)
      expect(p.y).toBeGreaterThan(canvas!.y - 20)
      expect(p.y).toBeLessThan(canvas!.y + canvas!.height + 20)
    }
  })

  test('Pause stops the marker', async ({ page }) => {
    await page.goto('/tours')
    await page.getByText('Replay Smoke Test').click()
    await page.getByRole('button', { name: /^play$/i }).click()
    await page.waitForTimeout(300)
    await page.getByRole('button', { name: /^pause$/i }).click()

    // The marker is geo-anchored, so its SCREEN transform keeps changing while
    // the follow-camera is still easing — and the default helicopter strategy
    // fires a ~2 s cinematic ease, so a fixed wait was flaky. Instead poll until
    // the transform settles (two equal samples = camera idle), THEN assert it
    // stays put: that's the real "playback stopped → marker doesn't advance".
    const cursor = page.locator('.map-hover-cursor')
    const read = () => cursor.evaluate((el) => (el as HTMLElement).style.transform)
    let prev = '', settled = ''
    for (let i = 0; i < 30; i++) {
      await page.waitForTimeout(150)
      const t = await read()
      if (t && t === prev) { settled = t; break }
      prev = t
    }
    expect(settled, 'camera should settle after pause').toBeTruthy()
    await page.waitForTimeout(500)
    expect(await read()).toBe(settled)
  })
})
