import { test, expect, type Page } from '@playwright/test'

// ─── Shared mock data ────────────────────────────────────────────────────────

const MOCK_ACTIVITIES = [
  {
    id: 1,
    name: 'Morning Ride',
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
  {
    id: 2,
    name: 'Evening Run',
    activityType: 'running',
    description: null,
    startTime: '2024-06-02T18:00:00Z',
    endTime: '2024-06-02T18:45:00Z',
    durationS: 2700,
    movingTimeS: 2650,
    distanceKm: 8.2,
    elevationGainM: 45,
    avgHr: 158,
    maxHr: 182,
    avgSpeedKmh: 10.9,
    maxSpeedKmh: 14.2,
    importedAt: '2024-06-02T19:00:00Z',
  },
]

const MOCK_TRACK = [
  { lat: 47.5, lon: 8.7, altM: 430, hr: 140, speedMs: 6.1 },
  { lat: 47.51, lon: 8.71, altM: 435, hr: 142, speedMs: 6.3 },
  { lat: 47.52, lon: 8.72, altM: 440, hr: 145, speedMs: 6.0 },
]

// Helper: mock all API routes
async function mockApi(page: Page) {
  await page.route('**/api/activities', (route) => {
    route.fulfill({ json: MOCK_ACTIVITIES, contentType: 'application/json' })
  })

  await page.route('**/api/activities/*/track', (route) => {
    route.fulfill({ json: MOCK_TRACK, contentType: 'application/json' })
  })

  await page.route('**/api/inbox', (route) => {
    if (route.request().method() === 'POST') {
      route.fulfill({
        status: 202,
        json: { status: 'queued', inboxName: 'test.fit' },
        contentType: 'application/json',
      })
    }
  })

  // Stub tile requests so Leaflet doesn't hang (provider param included)
  await page.route('**/api/tiles/**', (route) => {
    // 1x1 transparent PNG
    const PNG_1X1 =
      'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=='
    route.fulfill({
      status: 200,
      headers: { 'Content-Type': 'image/png' },
      body: Buffer.from(PNG_1X1, 'base64'),
    })
  })
}

// ─── Tests ───────────────────────────────────────────────────────────────────

test.describe('TourGaze app', () => {
  test('loads the tours view with header and faceted search', async ({ page }) => {
    await mockApi(page)
    await page.goto('/')

    // Home redirects to /tours.
    await expect(page).toHaveURL(/\/tours/)
    await expect(page.getByText('TourGaze').first()).toBeVisible()
    await expect(page.getByPlaceholder(/Search — try/)).toBeVisible()
  })

  test('shows activity list from API', async ({ page }) => {
    await mockApi(page)
    await page.goto('/tours')

    await expect(page.getByText('Morning Ride')).toBeVisible()
    await expect(page.getByText('Evening Run')).toBeVisible()
  })

  test('clicking an activity selects it (deep-linkable URL)', async ({ page }) => {
    await mockApi(page)
    await page.goto('/tours')

    await page.getByText('Morning Ride').click()
    await expect(page).toHaveURL(/[?&]id=1/)
  })

  test('faceted search filters the list by sport', async ({ page }) => {
    await mockApi(page)
    await page.goto('/tours')

    await expect(page.getByText('Morning Ride')).toBeVisible()

    const bar = page.getByPlaceholder(/Search — try/)
    await bar.click()
    await bar.fill('sport:running')
    await bar.press('Enter')

    // Chip committed; only the running activity survives.
    await expect(page.locator('text=sport:running')).toBeVisible()
    await expect(page.getByText('Evening Run')).toBeVisible()
    await expect(page.getByText('Morning Ride')).toHaveCount(0)
  })
})
