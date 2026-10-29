import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { ids, openai, project, provider, repo, run } from '../../test/fixtures'
import { renderApp } from '../../test/render'
import { server } from '../../test/server'

function setup(providers = [provider(), openai()], role: 'ADMIN' | 'OPERATOR' | 'VIEWER' = 'OPERATOR', tracker = false) {
  server.use(
    http.get(`/api/v1/workspaces/${ids.project}/tracker`, () => HttpResponse.json(tracker ? { configured: true, providerType: 'JIRA_MCP' } : { configured: false })),
    http.get(`/api/v1/workspaces/${ids.project}`, () => HttpResponse.json(project({ yourRole: role }))),
    http.get(`/api/v1/workspaces/${ids.project}/repositories`, () => HttpResponse.json([repo(), repo({ repositoryId: ids.web, name: 'admin-portal', role: 'WEB_FRONTEND' })])),
    http.get('/api/v1/agent-providers', () => HttpResponse.json(providers)),
    http.get(`/api/v1/runs/${ids.run}`, () => HttpResponse.json(run())),
    http.get(/\/api\/v1\/runs\/[^/]+\/(findings|artifacts|steps|changes|validations)$/, () => HttpResponse.json([])),
    http.get(`/api/v1/runs/${ids.run}/events`, () => new HttpResponse(null, { status: 404 })),
    http.get(`/api/v1/workspaces/${ids.project}/approvals`, () => HttpResponse.json([])),
  )
}

describe('New run', () => {
  it('lists providers first and models of the chosen provider; unusable providers stay visible with the reason', async () => {
    setup()
    renderApp(`/projects/${ids.project}/runs/new`)
    const providerSelect = await screen.findByLabelText('AI provider')
    expect(within(providerSelect).getByRole('option', { name: /claude \(anthropic\)/i })).toBeEnabled()
    const planned = within(providerSelect).getByRole('option', { name: /openai/i })
    expect(planned).toBeDisabled()
    expect(planned).toHaveTextContent('planned')
    // defaults to the usable provider and its balanced model
    expect(providerSelect).toHaveValue('ANTHROPIC')
    const model = screen.getByLabelText('Model')
    expect(model).toHaveValue('claude-sonnet-5-5')
    expect(within(model).getAllByRole('option').map((o) => o.textContent)).toEqual(['Claude Opus 5.5 · Strong', 'Claude Sonnet 5.5 · Balanced', 'Claude Haiku 5.5 · Fast'])
    expect(screen.getByText(/the provider adapter is simulated/i)).toBeInTheDocument()
  })

  it('explains what to do when no provider is ready', async () => {
    setup([provider({ hasUsableConnection: false, selectable: false, unavailableReason: 'Add and verify your own Claude (Anthropic) key under My connections' }), openai()])
    renderApp(`/projects/${ids.project}/runs/new`)
    expect(await screen.findByText('No AI provider is ready to use')).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: 'My connections' }).map((a) => a.getAttribute('href'))).toEqual(['/connections', '/connections']) // sidebar + the hint
    expect(screen.getByRole('button', { name: 'Start run' })).toBeDisabled()
  })

  it('starts a run on the chosen repositories, mode, provider and model, with a safe-to-retry key', async () => {
    setup()
    let body: unknown
    let key: string | null = null
    server.use(
      http.post(`/api/v1/workspaces/${ids.project}/runs`, async ({ request }) => {
        key = request.headers.get('Idempotency-Key')
        body = await request.json()
        return HttpResponse.json(run(), { status: 202 })
      }),
    )
    const user = userEvent.setup()
    const { router } = renderApp(`/projects/${ids.project}/runs/new`)
    await user.type(await screen.findByLabelText('Description'), 'Videos ignore the role of the logged in user')
    await user.click(screen.getByRole('checkbox', { name: /admin-portal/i })) // look at the backend only
    await user.click(screen.getByRole('radio', { name: /investigate and propose a fix/i }))
    await user.selectOptions(screen.getByLabelText('Model'), 'claude-opus-5-5')
    await user.click(screen.getByRole('button', { name: 'Start run' }))

    await waitFor(() => expect(router.state.location.pathname).toBe(`/runs/${ids.run}`))
    expect(body).toEqual({
      mode: 'PROPOSE_FIX',
      input: { kind: 'PROMPT', text: 'Videos ignore the role of the logged in user' },
      repositoryIds: [ids.backend],
      agent: { provider: 'ANTHROPIC', model: 'claude-opus-5-5' },
    })
    expect(key).toMatch(/^[0-9a-f-]{36}$/)
  })

  it('requires a well-formed ticket key in ticket mode and says the project has no tracker', async () => {
    setup()
    const user = userEvent.setup()
    renderApp(`/projects/${ids.project}/runs/new`)
    await user.click(await screen.findByRole('radio', { name: 'From a ticket' }))
    expect(screen.getByText(/has no issue tracker set/i)).toBeInTheDocument()
    await user.type(screen.getByLabelText('Ticket text'), 'Pasted ticket body')
    const start = screen.getByRole('button', { name: 'Start run' })
    expect(start).toBeDisabled()
    await user.type(screen.getByLabelText('Ticket key'), 'not a key')
    expect(await screen.findByText('Use a key like ABC-123')).toBeInTheDocument()
    expect(start).toBeDisabled()
    await user.clear(screen.getByLabelText('Ticket key'))
    await user.type(screen.getByLabelText('Ticket key'), 'pbpr-8500')
    expect(screen.getByLabelText('Ticket key')).toHaveValue('PBPR-8500')
    expect(start).toBeEnabled()
  })

  it('reads the ticket from the project tracker: previews it, then starts the run without any pasted text', async () => {
    setup([provider(), openai()], 'OPERATOR', true)
    let body: unknown
    server.use(
      http.get(`/api/v1/workspaces/${ids.project}/tickets/PBX-7`, () =>
        HttpResponse.json({
          tracker: 'JIRA_MCP', key: 'PBX-7', title: 'Videos ignore the role', type: 'Bug', status: 'To Do', priority: 'High', description: 'Steps to reproduce', labels: [], components: ['Videos'],
          comments: [{ author: 'Lee', body: 'Reproduced', routine: false }, { author: 'Sam', body: 'Daily scrum update', routine: true }],
        }),
      ),
      http.post(`/api/v1/workspaces/${ids.project}/runs`, async ({ request }) => {
        body = await request.json()
        return HttpResponse.json(run(), { status: 202 })
      }),
    )
    const user = userEvent.setup()
    const { router } = renderApp(`/projects/${ids.project}/runs/new`)
    await user.click(await screen.findByRole('radio', { name: 'From a ticket' }))
    expect(screen.queryByLabelText('Ticket text')).not.toBeInTheDocument()
    await user.type(screen.getByLabelText('Ticket key'), 'pbx-7')
    await user.click(screen.getByRole('button', { name: 'Preview ticket' }))
    expect(await screen.findByText(/Videos ignore the role/)).toBeInTheDocument()
    expect(screen.getByText(/1 comment\(s\) will be read; 1 routine status update\(s\) are skipped/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Start run' }))
    await waitFor(() => expect(router.state.location.pathname).toBe(`/runs/${ids.run}`))
    expect(body).toMatchObject({ input: { kind: 'TICKET', ref: 'PBX-7' } })
    expect(body).not.toHaveProperty('input.text')
  })

  it('explains a failed preview and lets the person paste the ticket instead', async () => {
    setup([provider(), openai()], 'OPERATOR', true)
    server.use(http.get(`/api/v1/workspaces/${ids.project}/tickets/PBX-7`, () => HttpResponse.json({ code: 'TRACKER_NOT_CONFIGURED', detail: 'Add and verify your JIRA_MCP connection under My connections, or paste the ticket text', status: 422 }, { status: 422 })))
    const user = userEvent.setup()
    renderApp(`/projects/${ids.project}/runs/new`)
    await user.click(await screen.findByRole('radio', { name: 'From a ticket' }))
    await user.type(screen.getByLabelText('Ticket key'), 'PBX-7')
    await user.click(screen.getByRole('button', { name: 'Preview ticket' }))
    expect(await screen.findByText('The ticket could not be read')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Paste the text instead' }))
    await user.type(await screen.findByLabelText('Ticket text'), 'Pasted body')
    expect(screen.getByRole('button', { name: 'Start run' })).toBeEnabled()
  })

  it('shows the server’s reason when it refuses, with a way to fix a missing key', async () => {
    setup()
    server.use(http.post(`/api/v1/workspaces/${ids.project}/runs`, () => HttpResponse.json({ code: 'NO_USABLE_CONNECTION', detail: 'Add and verify your own key', status: 422 }, { status: 422 })))
    const user = userEvent.setup()
    renderApp(`/projects/${ids.project}/runs/new`)
    await user.type(await screen.findByLabelText('Description'), 'Something is wrong with exports')
    await user.click(screen.getByRole('button', { name: 'Start run' }))
    expect(await screen.findByText('The run could not be started')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open My connections' })).toBeInTheDocument()
  })

  it('with a real model offers investigation only, says so, and never sends a fix mode', async () => {
    setup([provider({ availability: 'REAL' })])
    let body: unknown
    server.use(
      http.post(`/api/v1/workspaces/${ids.project}/runs`, async ({ request }) => {
        body = await request.json()
        return HttpResponse.json(run(), { status: 202 })
      }),
    )
    const user = userEvent.setup()
    renderApp(`/projects/${ids.project}/runs/new`)
    await user.type(await screen.findByLabelText('Description'), 'Videos ignore the role of the logged in user')
    expect(screen.getByText('Real model')).toBeInTheDocument()
    expect(screen.queryByText(/the provider adapter is simulated/i)).not.toBeInTheDocument()
    expect(screen.getByRole('radio', { name: /investigate only/i })).toBeChecked()
    expect(screen.getByRole('radio', { name: /investigate and propose a fix/i })).toBeEnabled()
    await user.click(screen.getByRole('radio', { name: /investigate, fix and prepare a patch/i }))
    expect(screen.getByText(/private copy of each repository/i)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Start run' }))
    await waitFor(() => expect((body as { mode: string }).mode).toBe('FIX_AND_PATCH'))
  })

  it('does not let a viewer start runs', async () => {
    setup(undefined, 'VIEWER')
    renderApp(`/projects/${ids.project}/runs/new`)
    expect(await screen.findByText('Your role cannot start runs')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Start run' })).not.toBeInTheDocument()
  })
})
