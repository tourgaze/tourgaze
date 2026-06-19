import { test, expect } from '@playwright/test'

// Keyboard navigation of the suggestion dropdown (matros-style): typing a facet
// key shows value suggestions; ArrowDown highlights one; Enter selects it.
test('search suggestions are keyboard navigable', async ({ page }) => {
  await page.goto('/tours')

  const bar = page.getByPlaceholder(/Search — try/)
  await bar.click()
  await bar.fill('sport:')

  // The value dropdown should offer "cycling".
  const firstSug = page.locator('[data-sug-idx="0"]')
  await expect(firstSug).toContainText('cycling')

  // ArrowDown highlights the first suggestion (active styling = text-primary).
  await bar.press('ArrowDown')
  await expect(firstSug).toHaveClass(/text-primary/)

  // Enter selects the highlighted suggestion → a sport:cycling chip appears.
  await bar.press('Enter')
  await expect(page.locator('text=sport:cycling')).toBeVisible()
})
