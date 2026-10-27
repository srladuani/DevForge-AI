import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { axe } from 'vitest-axe'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as navigation from '../../lib/navigation'
import { adapterTypes, connection } from '../../test/fixtures'
import { renderApp } from '../../test/render'
import { server } from '../../test/server'

const SECRET = 'sk-ant-THIS-MUST-NEVER-BE-SHOWN-0123456789'

describe('My connections', () => {
  it('treats the secret as write-only: password field, sent once, then gone from the page', async () => {
    let list = [connection()]
    let sent: unknown
    server.use(
      http.get('/api/v1/adapter-types', () => HttpResponse.json(adapterTypes)),
      http.get('/api/v1/me/connections', () => HttpResponse.json(list)),
      http.post('/api/v1/me/connections', async ({ request }) => {
        sent = await request.json()
        list = [...list, connection({ id: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd', label: 'Work key', status: 'UNVERIFIED' })]
        return HttpResponse.json(list[1], { status: 201 })
      }),
    )
    const user = userEvent.setup()
    renderApp('/connections')

    const field = await screen.findByLabelText('API key')
    expect(field).toHaveAttribute('type', 'password')
    expect(field).toHaveAttribute('autocomplete', 'new-password')
    await user.type(screen.getByLabelText('Label'), 'Work key')
    await user.type(field, SECRET)
    await user.click(screen.getByRole('button', { name: 'Save connection' }))

    expect(await screen.findByText(/saved\. press verify/i)).toBeInTheDocument()
    expect(sent).toMatchObject({ kind: 'AI_PROVIDER', providerType: 'ANTHROPIC', authMethod: 'API_KEY', label: 'Work key', secret: SECRET })
    expect(screen.getByLabelText('API key')).toHaveValue('') // cleared from the form state
    expect(document.body.innerHTML).not.toContain(SECRET)
    expect(await screen.findByText('Work key', { exact: false })).toBeInTheDocument()
  })

  it('shows what each provider can do today and offers no OAuth sign-in yet', async () => {
    server.use(http.get('/api/v1/adapter-types', () => HttpResponse.json(adapterTypes)), http.get('/api/v1/me/connections', () => HttpResponse.json([])))
    const user = userEvent.setup()
    renderApp('/connections')
    const provider = await screen.findByLabelText('Provider')
    expect(screen.getByRole('option', { name: /anthropic \(claude\) \(simulated in this build\)/i })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: /openai \(planned/i })).toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Type'), 'GIT_HOST')
    expect(provider).toHaveValue('GITHUB')
    const how = screen.getByLabelText('How you sign in')
    expect(within(how).queryByRole('option', { name: /oauth/i })).not.toBeInTheDocument()
    expect(screen.getByLabelText('Access token')).toHaveAttribute('type', 'password')
  })

  it('verifies and deletes with confirmation, showing the provider’s own message', async () => {
    let conns = [connection({ status: 'UNVERIFIED' })]
    server.use(
      http.get('/api/v1/adapter-types', () => HttpResponse.json(adapterTypes)),
      http.get('/api/v1/me/connections', () => HttpResponse.json(conns)),
      http.post(`/api/v1/me/connections/${connection().id}/verify`, () => {
        conns = [connection({ status: 'INVALID', statusMessage: 'SIMULATED check: the key is too short to be real' })]
        return HttpResponse.json(conns[0])
      }),
      http.delete(`/api/v1/me/connections/${connection().id}`, () => {
        conns = []
        return new HttpResponse(null, { status: 204 })
      }),
    )
    const user = userEvent.setup()
    renderApp('/connections')
    await user.click(await screen.findByRole('button', { name: 'Verify' }))
    expect(await screen.findByText('Invalid')).toBeInTheDocument()
    expect(screen.getByText(/too short to be real/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /delete personal key/i }))
    expect(screen.getByText(/the stored key is erased/i)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(screen.getByText(/no connections yet/i)).toBeInTheDocument())
  })

  it('has no detectable accessibility violations', async () => {
    server.use(http.get('/api/v1/adapter-types', () => HttpResponse.json(adapterTypes)), http.get('/api/v1/me/connections', () => HttpResponse.json([connection()])))
    const { container } = renderApp('/connections')
    await screen.findByText('Personal key', { exact: false })
    expect(await axe(container)).toHaveNoViolations()
  })

  describe('single sign-on', () => {
    beforeEach(() => {
      server.use(
        http.get('/api/v1/adapter-types', () => HttpResponse.json(adapterTypes)),
        http.get('/api/v1/me/connections', () => HttpResponse.json([connection()])),
      )
    })

    it('offers single sign-on for a tracker that supports it, and sends the browser to the provider’s page without ever asking for a token', async () => {
      const goTo = vi.spyOn(navigation, 'goTo').mockImplementation(() => undefined)
      let body: unknown
      server.use(
        http.post('/api/v1/me/oauth/JIRA_MCP/start', async ({ request }) => {
          body = await request.json()
          return HttpResponse.json({ authorizationUrl: 'https://auth.example.test/authorize?state=abc' })
        }),
      )
      const user = userEvent.setup()
      renderApp('/connections')
      await user.selectOptions(await screen.findByLabelText('Type'), 'TRACKER')
      expect(screen.getByText(/doesn.t allow API tokens|does not allow API tokens/i)).toBeInTheDocument()
      await user.click(screen.getByRole('button', { name: /sign in with jira/i }))
      await waitFor(() => expect(goTo).toHaveBeenCalledWith('https://auth.example.test/authorize?state=abc'))
      expect(body).toEqual({ label: 'Single sign-on' })
      goTo.mockRestore()
    })

    it('does not offer single sign-on for a provider that only takes a key or token', async () => {
      renderApp('/connections')
      await screen.findByLabelText('Type')
      expect(screen.queryByRole('button', { name: /sign in with/i })).not.toBeInTheDocument()
    })

    it('says what happened when the browser comes back from the provider, then cleans the address', async () => {
      const first = renderApp('/connections?oauth=success')
      expect(await screen.findByText('Signed in')).toBeInTheDocument()
      await waitFor(() => expect(first.router.state.location.search).toBe(''))
      first.unmount()
      const second = renderApp('/connections?oauth=error&reason=The+provider+did+not+allow+the+sign-in+(access_denied).')
      expect(await screen.findByText('Single sign-on did not complete')).toBeInTheDocument()
      expect(screen.getByText(/access_denied/)).toBeInTheDocument()
      await waitFor(() => expect(second.router.state.location.search).toBe(''))
    })

    it('offers single sign-on for a Git host that has it set up, and says what is missing when an administrator has not set it up', async () => {
      const goTo = vi.spyOn(navigation, 'goTo').mockImplementation(() => undefined)
      let called = 0
      server.use(
        http.post('/api/v1/me/oauth/BITBUCKET_CLOUD/start', () => {
          called++
          return HttpResponse.json({ authorizationUrl: 'https://bitbucket.example.test/site/oauth2/authorize?state=s' })
        }),
      )
      const user = userEvent.setup()
      const first = renderApp('/connections')
      await user.selectOptions(await screen.findByLabelText('Type'), 'GIT_HOST')
      await user.selectOptions(screen.getByLabelText('Provider'), 'BITBUCKET_CLOUD')
      expect(screen.getByText(/instead of creating a token/i)).toBeInTheDocument()
      await user.click(screen.getByRole('button', { name: /sign in with bitbucket cloud/i }))
      await waitFor(() => expect(goTo).toHaveBeenCalledWith('https://bitbucket.example.test/site/oauth2/authorize?state=s'))
      expect(called).toBe(1)
      goTo.mockRestore()
      first.unmount()

      server.use(http.get('/api/v1/adapter-types', () => HttpResponse.json(adapterTypes.map((a) => (a.type === 'BITBUCKET_CLOUD' ? { ...a, singleSignOn: false } : a)))))
      renderApp('/connections')
      await user.selectOptions(await screen.findByLabelText('Type'), 'GIT_HOST')
      await user.selectOptions(screen.getByLabelText('Provider'), 'BITBUCKET_CLOUD')
      expect(screen.queryByRole('button', { name: /sign in with bitbucket cloud/i })).not.toBeInTheDocument()
      expect(screen.getByText('Single sign-on is not set up for Bitbucket Cloud')).toBeInTheDocument()
      expect(screen.getByText(/DEVFORGE_OAUTH_BITBUCKET_CLIENT_ID/)).toBeInTheDocument()
      expect(screen.getByText(/\/api\/v1\/oauth\/callback/)).toBeInTheDocument()
    })
  })
})
