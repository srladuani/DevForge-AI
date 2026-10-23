import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { axe } from 'vitest-axe'
import { describe, expect, it } from 'vitest'
import { approval, evidence, finding, ids, project, run } from '../../test/fixtures'
import { renderApp } from '../../test/render'
import { server } from '../../test/server'

type Over = {
  run?: ReturnType<typeof run>
  role?: 'ADMIN' | 'OPERATOR' | 'VIEWER' | 'APPROVER'
  findings?: ReturnType<typeof finding>[]
  approvals?: ReturnType<typeof approval>[]
  stream?: string
}

function setup(o: Over = {}) {
  const r = o.run ?? run()
  server.use(
    http.get(`/api/v1/runs/${r.id}`, () => HttpResponse.json(r)),
    http.get(`/api/v1/workspaces/${ids.project}`, () => HttpResponse.json(project({ yourRole: o.role ?? 'ADMIN' }))),
    http.get(`/api/v1/runs/${r.id}/findings`, () => HttpResponse.json(o.findings ?? [])),
    http.get(`/api/v1/runs/${r.id}/artifacts`, () => HttpResponse.json([{ id: 'a1', type: 'ROOT_CAUSE', current: true, contentHash: 'sha256:aa', createdAt: '2026-10-22T10:00:00Z', content: { summary: '[SIMULATED] 3 findings', hypotheses: [{ text: '[SIMULATED] likely in TrainingVideoBO', confidence: 'LOW', supporting: ['key terms match'], contradicting: ['nothing executed'] }], unknowns: ['[SIMULATED] no logs'] } }])),
    http.get(`/api/v1/runs/${r.id}/changes`, () => HttpResponse.json([])),
    http.get(`/api/v1/runs/${r.id}/validations`, () => HttpResponse.json([])),
    http.get(`/api/v1/runs/${r.id}/steps`, () => HttpResponse.json([])),
    http.get(`/api/v1/workspaces/${ids.project}/approvals`, () => HttpResponse.json(o.approvals ?? [])),
    http.get(`/api/v1/runs/${r.id}/events`, () => (o.stream ? new HttpResponse(o.stream, { headers: { 'content-type': 'text/event-stream' } }) : new HttpResponse(null, { status: 404 }))),
  )
  return r
}

describe('Run page', () => {
  it('shows the status, the simulation notice, and only the actions the server allows', async () => {
    setup()
    renderApp(`/runs/${ids.run}`)
    expect(await screen.findByRole('heading', { name: 'Navigation videos ignore roles' })).toBeInTheDocument()
    expect(screen.getByText('Findings ready')).toBeInTheDocument()
    expect(screen.getByText('This run uses the built-in simulation')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Request fix' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Finish without changes' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Accept changes' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Resume' })).not.toBeInTheDocument()
  })

  it('offers no actions to someone who can only view', async () => {
    setup({ run: run({ nextActions: [] }), role: 'VIEWER' })
    renderApp(`/runs/${ids.run}`)
    await screen.findByRole('heading', { name: 'Navigation videos ignore roles' })
    expect(screen.queryByRole('button', { name: /request fix|finish|cancel|accept|resume/i })).not.toBeInTheDocument()
  })

  it('labels every finding and opens the cited code with its verification status', async () => {
    setup({
      findings: [
        finding(),
        finding({ id: 'f2', label: 'HYPOTHESIS', requestedLabel: 'VERIFIED', text: 'Claims a file that does not exist', downgradeReason: 'None of the cited evidence could be verified against the repository', evidence: [evidence({ id: 'e2', verified: false, path: 'src/Ghost.java', startLine: 1, endLine: 3, excerpt: undefined, contentHash: undefined, problem: 'File does not exist at that commit' })] }),
      ],
    })
    const user = userEvent.setup()
    renderApp(`/runs/${ids.run}?tab=findings`)
    expect(await screen.findByTitle(/backed by code the server re-read/i)).toHaveTextContent('Verified')
    expect(screen.getByTitle(/a guess; not confirmed/i)).toHaveTextContent('Hypothesis')
    expect(screen.getByText(/claimed as verified, downgraded: none of the cited evidence/i)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /confirmed: api:src\/main\/java\/com\/acme\/video\/trainingvideobo\.java:6-7/i }))
    const drawer = await screen.findByRole('dialog', { name: 'Evidence' })
    expect(within(drawer).getByText('Confirmed in the repository')).toBeInTheDocument()
    expect(within(drawer).getByText(/returns every navigation video regardless of role/)).toBeInTheDocument()
    expect(within(drawer).getByText('6')).toBeInTheDocument() // line numbers start at the cited line
    await user.click(within(drawer).getByRole('button', { name: 'Close' }))

    await user.click(screen.getByRole('button', { name: /not confirmed: api:src\/ghost\.java:1-3/i }))
    const drawer2 = await screen.findByRole('dialog', { name: 'Evidence' })
    expect(within(drawer2).getByText('Could not be confirmed')).toBeInTheDocument()
    expect(within(drawer2).getByText('File does not exist at that commit')).toBeInTheDocument()
  })

  it('renders untrusted text as plain text, never as markup', async () => {
    setup({ findings: [finding({ text: '<img src=x onerror=alert(1)> and <script>alert(2)</script>' })] })
    const { container } = renderApp(`/runs/${ids.run}?tab=findings`)
    expect(await screen.findByText(/<img src=x onerror=alert\(1\)>/)).toBeInTheDocument()
    expect(container.querySelector('img')).toBeNull()
    expect(container.querySelector('script')).toBeNull()
  })

  it('lets an approver approve exactly the proposal they were shown, and sends its hash', async () => {
    const pending = approval()
    let decision: unknown
    setup({ run: run({ status: 'AWAITING_APPROVAL', nextActions: ['cancel'] }), role: 'APPROVER', approvals: [pending] })
    server.use(
      http.post(`/api/v1/approvals/${pending.id}/decision`, async ({ request }) => {
        decision = await request.json()
        return HttpResponse.json({ ...pending, status: 'APPROVED' })
      }),
    )
    const user = userEvent.setup()
    renderApp(`/runs/${ids.run}`) // opens on the approvals tab because that is what the run is waiting for
    expect(await screen.findByText('Your decision is needed')).toBeInTheDocument()
    expect(screen.getByText('api:src/main/java/Foo.java')).toBeInTheDocument()
    expect(screen.getByText('api: ./gradlew test')).toBeInTheDocument()
    expect(screen.getByText(/nothing is applied, committed or pushed/i)).toBeInTheDocument()
    await user.type(screen.getByLabelText('Comment (optional)'), 'ok to proceed')
    await user.click(screen.getByRole('button', { name: 'Approve' }))
    await waitFor(() => expect(decision).toEqual({ decision: 'APPROVE', artifactHash: pending.artifactHash, comment: 'ok to proceed' }))
  })

  it('does not offer approval to someone whose role cannot approve', async () => {
    setup({ run: run({ status: 'AWAITING_APPROVAL', nextActions: [] }), role: 'OPERATOR', approvals: [approval()] })
    renderApp(`/runs/${ids.run}`)
    expect(await screen.findByText(/your role in this project cannot approve changes/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument()
  })

  it('tells the approver when the proposal changed under them instead of approving blindly', async () => {
    const pending = approval()
    setup({ run: run({ status: 'AWAITING_APPROVAL', nextActions: [] }), role: 'ADMIN', approvals: [pending] })
    server.use(http.post(`/api/v1/approvals/${pending.id}/decision`, () => HttpResponse.json({ code: 'ARTIFACT_CHANGED', detail: 'What you reviewed is not what is awaiting approval; reload and review again', status: 409 }, { status: 409 })))
    const user = userEvent.setup()
    renderApp(`/runs/${ids.run}`)
    await user.click(await screen.findByRole('button', { name: 'Approve' }))
    expect(await screen.findByText('The proposal changed')).toBeInTheDocument()
    expect(screen.getByText(/reload and review again/)).toBeInTheDocument()
  })

  it('asks the owner to fix a rejected key and links to where, instead of showing a generic failure', async () => {
    setup({ run: run({ status: 'AWAITING_INPUT', awaitReason: 'CREDENTIAL', resumeState: 'INVESTIGATION', nextActions: ['resume', 'cancel'] }) })
    renderApp(`/runs/${ids.run}`)
    expect(await screen.findByText('Your AI credential needs attention')).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: 'My connections' }).length).toBeGreaterThan(0)
    expect(screen.getByRole('button', { name: 'Resume' })).toBeInTheDocument()
  })

  it('shows the questions the run is blocked on and sends the answer', async () => {
    let sent: unknown
    setup({
      run: run({ status: 'AWAITING_INPUT', awaitReason: 'CLARIFICATION', nextActions: ['answer', 'cancel'] }),
      stream: `id:1\nevent:NEEDS_INPUT\ndata:${JSON.stringify({ seq: 1, type: 'NEEDS_INPUT', payload: { reason: 'CLARIFICATION', questions: ['Which user role is affected?'] }, createdAt: '2026-10-22T10:00:00Z' })}\n\n`,
    })
    server.use(
      http.post(`/api/v1/runs/${ids.run}/actions/answer`, async ({ request }) => {
        sent = await request.json()
        return HttpResponse.json(run())
      }),
    )
    const user = userEvent.setup()
    renderApp(`/runs/${ids.run}`)
    expect(await screen.findByText('Which user role is affected?')).toBeInTheDocument()
    await user.type(screen.getByLabelText('Your answer'), 'Worker role on QA')
    await user.click(screen.getByRole('button', { name: 'Send answer' }))
    await waitFor(() => expect(sent).toEqual({ text: 'Worker role on QA' }))
  })

  it('says plainly when an investigation stopped early and that the result is partial', async () => {
    setup({ run: run({ incomplete: true, agent: { provider: 'ANTHROPIC', model: 'claude-sonnet-5-5', simulated: false } }) })
    renderApp(`/runs/${ids.run}`)
    expect(await screen.findByText('This investigation stopped early')).toBeInTheDocument()
    expect(screen.getByText('Incomplete')).toBeInTheDocument()
    expect(screen.queryByText('This run uses the built-in simulation')).not.toBeInTheDocument() // a real run is not labelled as a simulation
  })

  it('asks for confirmation before cancelling', async () => {
    let cancelled = 0
    setup()
    server.use(
      http.post(`/api/v1/runs/${ids.run}/actions/cancel`, () => {
        cancelled++
        return HttpResponse.json(run({ status: 'CANCELLED', nextActions: [] }))
      }),
    )
    const user = userEvent.setup()
    renderApp(`/runs/${ids.run}`)
    await user.click(await screen.findByRole('button', { name: 'Cancel run' }))
    expect(cancelled).toBe(0)
    const dialog = await screen.findByRole('dialog', { name: 'Cancel this run?' })
    await user.click(within(dialog).getByRole('button', { name: 'Cancel run' }))
    await waitFor(() => expect(cancelled).toBe(1))
  })

  it('shows a failed run’s reason and whether it can be retried', async () => {
    setup({ run: run({ status: 'FAILED', failure: { code: 'MODEL_TIMEOUT', step: 'INVESTIGATION', message: 'SIMULATED provider timeout (gave up after 3 attempts)', retryable: true }, nextActions: ['resume', 'cancel'] }) })
    renderApp(`/runs/${ids.run}`)
    expect(await screen.findByText('The run failed in investigation')).toBeInTheDocument()
    expect(screen.getByText(/this is retryable/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Resume' })).toBeInTheDocument()
  })

  it('has no detectable accessibility violations on the findings view', async () => {
    setup({ findings: [finding()] })
    const { container } = renderApp(`/runs/${ids.run}?tab=findings`)
    await screen.findByTitle(/backed by code the server re-read/i)
    expect(await axe(container)).toHaveNoViolations()
  })

  it('shows a real check result as counts and failing tests, keeps build output collapsed, and never presents NOT_RUN as a pass', async () => {
    const r = setup({ run: run({ status: 'AWAITING_REVIEW', mode: 'FIX_AND_PATCH', agent: { provider: 'ANTHROPIC', model: 'claude-sonnet-5-5', simulated: false }, nextActions: ['accept', 'request-changes', 'cancel'] }) })
    const change = { id: 'c1', repositoryId: ids.backend, repoName: 'api', baseSha: 'a'.repeat(40), patch: '--- a/X.java\n+++ b/X.java\n@@ -1 +1 @@\n-old\n+new\n', patchHash: 'sha256:1', files: ['X.java'], simulated: false, attempt: 1 }
    server.use(
      http.get(`/api/v1/runs/${r.id}/changes`, () => HttpResponse.json([change])),
      http.get(`/api/v1/runs/${r.id}/validations`, () =>
        HttpResponse.json([
          { id: 'v1', codeChangeId: 'c1', repoName: 'api', command: './gradlew test --offline', status: 'FAILED', attempt: 1, summary: '3 test(s): 1 failed, 0 skipped. Failing: A.bad\n--- output (end) ---\nAssertionError: expected 1' },
          { id: 'v2', codeChangeId: 'c1', repoName: 'api', status: 'NOT_RUN', attempt: 2, summary: 'No sandbox is available on this server, so no repository code was run' },
        ]),
      ),
    )
    const user = userEvent.setup()
    renderApp(`/runs/${ids.run}?tab=changes`)
    expect(await screen.findByText(/3 test\(s\): 1 failed/)).toBeInTheDocument()
    expect(screen.getByText('Failed')).toBeInTheDocument()
    expect(screen.getByText('Not run')).toBeInTheDocument()
    expect(screen.queryByText('Passed')).not.toBeInTheDocument()
    const details = screen.getByText('Show the end of the output').closest('details')
    expect(details).not.toHaveAttribute('open')
    await user.click(screen.getByText('Show the end of the output'))
    expect(screen.getByText(/AssertionError: expected 1/)).toBeVisible()
  })
})
