import { expect, test } from '@playwright/test'
import { makeProject } from './fixtures.ts'

const shot = (name: string) => `test-results/shots/${name}.png`
const ADMIN = 'e2e-admin@example.test'

test('a developer takes a ticket from description to an approved, reviewed patch across a backend and a frontend repository', async ({ page }) => {
  const project = makeProject()
  try {
    await test.step('sign in (local development sign-in)', async () => {
      await page.goto('/')
      await expect(page.getByText('Local development sign-in')).toBeVisible()
      await page.getByLabel('Email').fill(ADMIN)
      await page.getByRole('button', { name: 'Continue' }).click()
      await expect(page.getByRole('heading', { name: 'Projects' })).toBeVisible()
    })

    await test.step('add my own Claude key and verify it', async () => {
      await page.getByRole('link', { name: 'My connections' }).click()
      await page.getByLabel('Label').fill('E2E key')
      await page.getByLabel('API key').fill('sim-key-e2e-0123456789')
      await page.getByRole('button', { name: 'Save connection' }).click()
      await expect(page.getByText(/saved\. press verify/i)).toBeVisible()
      await expect(page.getByLabel('API key')).toHaveValue('')
      await page.getByRole('button', { name: 'Verify' }).first().click()
      await expect(page.getByText('Verified', { exact: true })).toBeVisible()
      await expect(page.locator('body')).not.toContainText('sim-key-e2e-0123456789')
      await page.screenshot({ path: shot('01-connections') })
    })

    await test.step('create a Support project with two repositories', async () => {
      await page.getByRole('link', { name: 'Projects' }).click()
      await page.getByRole('link', { name: 'New project' }).click()
      await expect(page.getByRole('radio', { name: /^new project/i })).toBeDisabled()
      await expect(page.getByText('Coming soon')).toBeVisible()
      await page.screenshot({ path: shot('02-project-type') })
      await page.getByRole('button', { name: 'Continue' }).click()
      await page.getByLabel('Project name').fill('Acme support')
      const paths = page.getByLabel('Path on this machine')
      await paths.nth(0).fill(project.backend)
      await paths.nth(1).fill(project.web)
      await page.getByRole('button', { name: 'Create project' }).click()
      await expect(page.getByRole('heading', { name: 'Acme support' })).toBeVisible()
      await page.getByRole('tab', { name: /repositories/i }).click()
      await expect(page.getByText('gradle', { exact: false }).first()).toBeVisible()
      await expect(page.getByText('java 21, gradle 9.5.1', { exact: false })).toBeVisible()
      await expect(page.getByText('node >=20', { exact: false })).toBeVisible()
      await expect(page.getByText('test: yarn test')).toBeVisible()
      await page.screenshot({ path: shot('03-repositories') })
    })

    await test.step('start a run: pick repositories, mode, provider and model', async () => {
      await page.getByRole('link', { name: 'New run' }).click()
      await page.getByLabel('Description').fill('Navigation videos show the same full list for every role.\nExpected: each role only sees the videos assigned to it. The TrainingVideo list at /api/v1/training-videos returns everything.')
      await page.getByRole('radio', { name: /investigate, fix and prepare a patch/i }).check()
      await expect(page.getByLabel('AI provider')).toHaveValue('ANTHROPIC')
      await expect(page.getByLabel('Model', { exact: true })).toHaveValue('claude-sonnet-5-5')
      await expect(page.getByRole('option', { name: /openai/i })).toBeDisabled()
      await page.screenshot({ path: shot('04-new-run') })
      await page.getByRole('button', { name: 'Start run' }).click()
    })

    await test.step('watch it work, then read the evidence-backed findings', async () => {
      await expect(page.getByText('This run uses the built-in simulation')).toBeVisible()
      await expect(page.getByText('Awaiting approval').first()).toBeVisible()
      await page.getByRole('tab', { name: /findings/i }).click()
      await expect(page.getByText(/cross-repo link/i)).toBeVisible()
      await page.getByRole('button', { name: /confirmed:.*TrainingVideoBO\.java/i }).first().click()
      const drawer = page.getByRole('dialog', { name: 'Evidence' })
      await expect(drawer.getByText('Confirmed in the repository')).toBeVisible()
      await expect(drawer.getByText(/returns every navigation video/)).toBeVisible()
      await page.screenshot({ path: shot('05-evidence') })
      await drawer.getByRole('button', { name: 'Close' }).click()
      await expect(drawer).toBeHidden()
      await page.getByRole('tab', { name: 'Overview' }).click()
      await expect(page.getByRole('tab', { name: 'Overview' })).toHaveAttribute('aria-selected', 'true')
      await expect(page.getByRole('listitem').filter({ hasText: 'acme-api' }).filter({ hasText: 'Backend' })).toBeVisible()
      await expect(page.getByRole('listitem').filter({ hasText: 'acme-admin' }).filter({ hasText: 'Web frontend' })).toBeVisible()
    })

    await test.step('approve exactly what was proposed', async () => {
      await page.getByRole('tab', { name: /approvals/i }).click()
      await expect(page.getByText('Your decision is needed')).toBeVisible()
      await expect(page.getByText(/nothing is applied, committed or pushed/i)).toBeVisible()
      await page.screenshot({ path: shot('06-approval') })
      await page.getByRole('button', { name: 'Approve' }).click()
      await expect(page.getByText('Awaiting review').first()).toBeVisible()
    })

    await test.step('review the patches and the honest validation result', async () => {
      await page.getByRole('tab', { name: 'Changes' }).click()
      await expect(page.getByText('Nothing has been applied to any repository on disk')).toBeVisible()
      await expect(page.getByLabel('Unified diff')).toHaveCount(2)
      await expect(page.getByText('Not executed (simulated)').first()).toBeVisible()
      await expect(page.getByText('Passed', { exact: true })).toHaveCount(0)
      await page.screenshot({ path: shot('07-changes'), fullPage: true })
      await page.getByRole('button', { name: 'Accept changes' }).click()
      await expect(page.getByText('Completed').first()).toBeVisible()
    })

    await test.step('the timeline recorded everything and the stream closed', async () => {
      await page.getByRole('tab', { name: /timeline/i }).click()
      await expect(page.getByText('Stream ended')).toBeVisible()
      await expect(page.getByText(/Awaiting approval → Preparing changes/)).toBeVisible()
      await page.screenshot({ path: shot('08-timeline'), fullPage: true })
    })

    await test.step('the audit log (admin) shows who did what, without secrets', async () => {
      await page.getByRole('link', { name: /audit log/i }).click()
      await expect(page.getByRole('cell', { name: 'RUN_CREATED' }).first()).toBeVisible()
      await expect(page.getByRole('cell', { name: 'APPROVAL_APPROVED' }).first()).toBeVisible()
      await expect(page.locator('body')).not.toContainText('sim-key-e2e-0123456789')
      await page.screenshot({ path: shot('09-audit') })
    })
  } finally {
    project.cleanup()
  }
})
