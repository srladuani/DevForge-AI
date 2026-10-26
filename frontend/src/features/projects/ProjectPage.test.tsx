import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { adapterTypes, ids, project, repo, run } from '../../test/fixtures'
import { renderApp } from '../../test/render'
import { server } from '../../test/server'

const web = () => repo({ repositoryId: ids.web, name: 'admin-portal', role: 'WEB_FRONTEND', profile: { headSha: 'b'.repeat(40), fileCount: 40, languages: { JavaScript: 30 }, buildTools: [{ tool: 'yarn', root: '', toolchain: 'node >=20', frameworks: ['react', 'webpack', 'test:karma'], testCommand: 'yarn test' }], instructionFiles: [], notes: [] } })

function setup(role: 'ADMIN' | 'OPERATOR' | 'VIEWER', repos = [repo(), web()]) {
  server.use(
    http.get(`/api/v1/workspaces/${ids.project}`, () => HttpResponse.json(project({ yourRole: role }))),
    http.get(`/api/v1/workspaces/${ids.project}/repositories`, () => HttpResponse.json(repos)),
    http.get(`/api/v1/workspaces/${ids.project}/runs`, () => HttpResponse.json([run(), run({ id: 'r2', title: 'Export times out', status: 'FAILED', mode: 'PROPOSE_FIX' })])),
    http.get(`/api/v1/workspaces/${ids.project}/members`, () => HttpResponse.json([{ userId: 'u1', email: 'dev@example.test', displayName: 'dev', role }])),
    http.get('/api/v1/adapter-types', () => HttpResponse.json(adapterTypes)),
    http.get(`/api/v1/workspaces/${ids.project}/tracker`, () => HttpResponse.json({ configured: false })),
    http.get(`/api/v1/workspaces/${ids.project}/publishing`, () => HttpResponse.json({ branchPrefix: 'devforge/', draft: true })),
  )
}

describe('Project page', () => {
  it('lists runs with their status and a clear marker for simulated ones', async () => {
    setup('OPERATOR')
    renderApp(`/projects/${ids.project}`)
    expect(await screen.findByRole('link', { name: /navigation videos ignore roles/i })).toHaveAttribute('href', `/runs/${ids.run}`)
    expect(screen.getAllByText('Simulated').length).toBeGreaterThan(0)
    expect(within(screen.getByRole('link', { name: /export times out/i })).getByText('Failed')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'New run' })).toBeInTheDocument()
  })

  it('does not offer New run to a viewer', async () => {
    setup('VIEWER')
    renderApp(`/projects/${ids.project}`)
    await screen.findByRole('link', { name: /navigation videos ignore roles/i })
    expect(screen.queryByRole('link', { name: 'New run' })).not.toBeInTheDocument()
  })

  it('blocks starting runs and says why when the project has no repositories', async () => {
    setup('ADMIN', [])
    renderApp(`/projects/${ids.project}`)
    expect(await screen.findByText('This project has no repositories yet')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'New run' })).not.toBeInTheDocument()
  })

  it('shows each repository with its role, detected toolchain, test command, and the untrusted-instructions note', async () => {
    setup('OPERATOR')
    const user = userEvent.setup()
    renderApp(`/projects/${ids.project}?tab=repositories`)
    expect(await screen.findByText('api')).toBeInTheDocument()
    expect(screen.getByText('admin-portal')).toBeInTheDocument()
    expect(screen.getByText('java 21, gradle 9.5.1', { exact: false })).toBeInTheDocument()
    expect(screen.getByText('test: ./gradlew test')).toBeInTheDocument()
    expect(screen.getByText('test: yarn test')).toBeInTheDocument()
    expect(screen.getByText('Web frontend')).toBeInTheDocument()
    expect(screen.getByText(/treated as untrusted guidance/i)).toBeInTheDocument()
    // an operator cannot manage repositories
    expect(screen.queryByRole('button', { name: 'Refresh' })).not.toBeInTheDocument()
    expect(screen.queryByText('Add a repository')).not.toBeInTheDocument()
    await user.click(screen.getByRole('tab', { name: /people & policy/i }))
    expect(await screen.findByText(/only project admins can change the policy/i)).toBeInTheDocument()
  })

  it('lets an admin change a repository role and save the policy', async () => {
    setup('ADMIN')
    let patched: unknown
    let policy: unknown
    server.use(
      http.patch(`/api/v1/workspaces/${ids.project}/repositories/${ids.backend}`, async ({ request }) => {
        patched = await request.json()
        return HttpResponse.json(repo({ role: 'LIBRARY' }))
      }),
      http.patch(`/api/v1/workspaces/${ids.project}`, async ({ request }) => {
        policy = await request.json()
        return HttpResponse.json(project())
      }),
    )
    const user = userEvent.setup()
    renderApp(`/projects/${ids.project}?tab=repositories`)
    await user.selectOptions(await screen.findByLabelText('Role of api'), 'LIBRARY')
    await waitFor(() => expect(patched).toEqual({ role: 'LIBRARY' }))

    await user.click(screen.getByRole('tab', { name: /people & policy/i }))
    await user.click(await screen.findByRole('checkbox', { name: /claude|anthropic/i }))
    await user.click(screen.getByRole('checkbox', { name: /someone other than the requester/i }))
    await user.click(screen.getByRole('button', { name: 'Save policy' }))
    await waitFor(() => expect(policy).toEqual({ policy: { allowedProviders: ['ANTHROPIC'], requireDifferentApprover: true, maxRunCostUsd: 2, maxConcurrentRuns: 2 } }))
  })

  it('lets an admin choose the project issue tracker and optionally the site, and hides the controls from others', async () => {
    setup('ADMIN')
    let put: unknown
    server.use(
      http.put(`/api/v1/workspaces/${ids.project}/tracker`, async ({ request }) => {
        put = await request.json()
        return HttpResponse.json({ configured: true, providerType: 'JIRA_MCP', siteHint: 'acme' })
      }),
    )
    const user = userEvent.setup()
    renderApp(`/projects/${ids.project}?tab=team`)
    expect(await screen.findByText('No tracker is set, so people paste ticket text when they start a run.')).toBeInTheDocument()
    await user.type(screen.getByLabelText(/Site/), 'acme')
    await user.click(screen.getByRole('button', { name: 'Use Jira (via MCP)' }))
    await waitFor(() => expect(put).toEqual({ providerType: 'JIRA_MCP', siteHint: 'acme' }))
  })

  it('does not show tracker controls to a non-admin', async () => {
    setup('OPERATOR')
    renderApp(`/projects/${ids.project}?tab=team`)
    expect(await screen.findByText('No tracker is set, so people paste ticket text when they start a run.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Use Jira (via MCP)' })).not.toBeInTheDocument()
  })

  it('lets an admin choose where pull requests go: target branch, branch prefix and draft, and shows others the settings read-only', async () => {
    setup('ADMIN')
    let put: unknown
    server.use(
      http.put(`/api/v1/workspaces/${ids.project}/publishing`, async ({ request }) => {
        put = await request.json()
        return HttpResponse.json({ targetBranch: 'develop', branchPrefix: 'feature/', draft: false })
      }),
    )
    const user = userEvent.setup()
    const first = renderApp(`/projects/${ids.project}?tab=team`)
    await user.type(await screen.findByLabelText('Target branch'), 'develop')
    await user.clear(screen.getByLabelText('Branch prefix'))
    await user.type(screen.getByLabelText('Branch prefix'), 'feature/')
    await user.click(screen.getByRole('checkbox', { name: 'Open pull requests as drafts' }))
    await user.click(screen.getAllByRole('button', { name: 'Save' }).at(-1) as HTMLElement)
    await waitFor(() => expect(put).toEqual({ targetBranch: 'develop', branchPrefix: 'feature/', draft: false }))
    first.unmount()

    setup('OPERATOR')
    renderApp(`/projects/${ids.project}?tab=team`)
    expect(await screen.findByLabelText('Target branch')).toBeDisabled()
    expect(screen.getByLabelText('Branch prefix')).toHaveValue('devforge/')
  })
})
