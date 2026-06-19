import { test, expect, type Page } from '@playwright/test'

// Regression guard for "endless loading track": selecting an activity must
// resolve the track query within a bounded time so the loading overlay clears.

const MOCK_ACTIVITIES = [
  {
    id: 'abc123',
    name: 'Test Ride',
    activityType: 'cycling',
    description: null,
    startTime: '2024-06-01T07:00:00Z',
    endTime: '2024-06-01T09:30:00Z',
    durationS: 9000,
    movingTimeS: 8500,
    distanceKm: 55.3,
    elevationGainM: 620,
    avgHr: 142,
    maxHr: 175,
    avgSpeedKmh: 22.1,
    maxSpeedKmh: 58.4,
    importedAt: '2024-06-01T10:00:00Z',
  },
]

const MOCK_TRACK = Array.from({ length: 50 }, (_, i) => ({
  lat: 47.5 + i * 0.001,
  lon: 8.7 + i * 0.001,
  altM: 430 + i,
  hr: 140 + (i % 20),
  speedMs: 6 + (i % 5) * 0.1,
}))

async function mockApi(page: Page) {
  await page.route('**/api/activities', (route) => {
    route.fulfill({ json: MOCK_ACTIVITIES, contentType: 'application/json' })
  })
  await page.route('**/api/activities/*/track*', (route) => {
    route.fulfill({ json: MOCK_TRACK, contentType: 'application/json' })
  })
  await page.route('**/api/settings', (route) => {
    route.fulfill({ json: [], contentType: 'application/json' })
  })
  const PNG_1X1 =
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=='
  await page.route('**/api/tiles/**', (route) => {
    route.fulfill({
      status: 200,
      headers: { 'Content-Type': 'image/png' },
      body: Buffer.from(PNG_1X1, 'base64'),
    })
  })
}

test.describe('Track loading', () => {
  test('selecting an activity loads its track and clears the spinner', async ({ page }) => {
    await mockApi(page)
    await page.goto('/')

    await expect(page.getByText('Test Ride')).toBeVisible({ timeout: 10_000 })
    await page.getByText('Test Ride').click()

    await expect(page.locator('.maplibregl-map')).toBeVisible({ timeout: 5_000 })
    await expect(page.getByText('Loading track…')).toBeHidden({ timeout: 10_000 })
  })
})
