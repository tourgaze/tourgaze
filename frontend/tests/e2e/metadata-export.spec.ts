import { test, expect } from '@playwright/test'

const API = 'http://localhost:8085/api'

test('ride metadata endpoint + recovery export zip', async ({ request }) => {
  const acts = await (await request.get(`${API}/activities`)).json()
  test.skip(acts.length < 1, 'need an activity')
  const a = acts[0]

  // GET /activities/{id}/metadata returns the documented RideMetadata shape.
  const meta = await (await request.get(`${API}/activities/${a.id}/metadata`)).json()
  expect(meta.schemaVersion).toBe(1)
  expect(meta.id).toBe(a.id)
  expect(meta.source?.sourceFilename).toBeTruthy()
  expect(Array.isArray(meta.tags)).toBeTruthy()

  // Editing the ride is reflected by the metadata endpoint (live from DB).
  const orig = a.name ?? ''
  await request.patch(`${API}/activities/${a.id}`, { data: { name: 'MetaCheck Ride' } })
  await expect.poll(async () =>
    (await (await request.get(`${API}/activities/${a.id}/metadata`)).json()).name,
  ).toBe('MetaCheck Ride')
  await request.patch(`${API}/activities/${a.id}`, { data: { name: orig } })

  // Export zip recreates original files + sidecars.
  const res = await request.get(`${API}/admin/export`)
  expect(res.status()).toBe(200)
  expect(res.headers()['content-type']).toContain('zip')
  const body = await res.body()
  expect(body.length).toBeGreaterThan(1000)
})
