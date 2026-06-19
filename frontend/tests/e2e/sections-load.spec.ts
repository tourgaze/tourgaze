import { test, expect } from '@playwright/test'

// Sections come from the curated global /section.json (read-only).
test('section.json loads; tours view error-free', async ({ page }) => {
  const errors: string[] = []
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()) })
  page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message))

  await page.goto('/tours')
  await page.waitForTimeout(1000)

  const names = await page.evaluate(async () => {
    const r = await fetch('/section.json')
    return (await r.json() as { name: string }[]).map(s => s.name)
  })

  expect(names).toContain('Jaufenpass')
  expect(names).toContain('Moritzberg')
  expect(errors, '\n' + errors.join('\n')).toHaveLength(0)
})
