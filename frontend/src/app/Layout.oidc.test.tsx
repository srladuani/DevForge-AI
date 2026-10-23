import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import type { User, UserManager } from 'oidc-client-ts'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { completeProviderSignIn, configureAuth } from '../lib/auth'
import { renderApp } from '../test/render'
import { server } from '../test/server'

const OIDC = { mode: 'OIDC' as const, issuer: 'https://idp.example.test', clientId: 'devforge-web', scopes: ['openid'] }

function manager() {
  const m = {
    signinRedirect: vi.fn<() => Promise<void>>(() => Promise.resolve()),
    signoutRedirect: vi.fn<() => Promise<void>>(() => Promise.resolve()),
    signinCallback: vi.fn<() => Promise<User>>(() => Promise.resolve({ access_token: 'tok', expires_in: 600, profile: { email: 'ada@example.test' } } as unknown as User)),
    signinSilent: vi.fn<() => Promise<User>>(),
  }
  return { m, asManager: m as unknown as UserManager }
}

afterEach(() => configureAuth({ mode: 'DEV', issuer: '', clientId: '', scopes: [] }))

describe('Sign-in with the organisation’s identity provider', () => {
  it('sends a signed-out visitor to the provider instead of showing the development sign-in', async () => {
    const { m, asManager } = manager()
    configureAuth(OIDC, asManager)
    renderApp('/projects', null)
    expect(screen.getByText('Signing you in…')).toBeInTheDocument()
    await waitFor(() => expect(m.signinRedirect).toHaveBeenCalled())
    expect(screen.queryByLabelText(/email/i)).not.toBeInTheDocument()
  })

  it('shows who is signed in once the provider sign-in completed and signs out through the provider', async () => {
    const { m, asManager } = manager()
    configureAuth(OIDC, asManager)
    await completeProviderSignIn()
    server.use(http.get('/api/v1/workspaces', () => HttpResponse.json([])))
    renderApp('/projects', null)
    expect(await screen.findByText('ada@example.test')).toBeInTheDocument()
    await userEvent.setup().click(screen.getByRole('button', { name: 'Sign out' }))
    expect(m.signoutRedirect).toHaveBeenCalled()
  })

  it('says so when the server has no sign-in configured', () => {
    configureAuth({ mode: 'NONE', issuer: '', clientId: '', scopes: [] })
    renderApp('/projects', null)
    expect(screen.getByText(/no sign-in configured/i)).toBeInTheDocument()
  })
})
