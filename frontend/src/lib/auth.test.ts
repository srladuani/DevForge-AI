import type { User, UserManager } from 'oidc-client-ts'
import { http, HttpResponse } from 'msw'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { client } from './api/client'
import { authorizationHeaders, completeProviderSignIn, configureAuth, loadAuthConfig, sessionExpired, signInWithProvider } from './auth'
import { signIn, signOut } from './session'
import { server } from '../test/server'

const OIDC = { mode: 'OIDC' as const, issuer: 'https://idp.example.test/realms/acme', clientId: 'devforge-web', scopes: ['openid', 'profile', 'email'] }

type Login = () => Promise<User | null>

function fakeManager(silent?: Login) {
  const user = (expiresIn: number) => ({ access_token: 'tok-abc', expires_in: expiresIn, profile: { email: 'ada@example.test' } }) as unknown as User
  const m = {
    signinCallback: vi.fn<Login>(() => Promise.resolve(user(300))),
    signinSilent: vi.fn<Login>(silent ?? (() => Promise.resolve(user(300)))),
    signinRedirect: vi.fn<() => Promise<void>>(() => Promise.resolve()),
    signoutRedirect: vi.fn<() => Promise<void>>(() => Promise.resolve()),
  }
  return { m, asManager: m as unknown as UserManager, user }
}

afterEach(() => {
  configureAuth({ mode: 'DEV', issuer: '', clientId: '', scopes: [] })
  signOut()
  window.sessionStorage.clear()
})

describe('how the server says people sign in', () => {
  it('reads the provider settings, and falls back to development sign-in if the answer is missing or odd', async () => {
    server.use(http.get('/api/v1/auth/config', () => HttpResponse.json(OIDC)))
    expect(await loadAuthConfig()).toEqual(OIDC)
    server.use(http.get('/api/v1/auth/config', () => HttpResponse.json({ mode: 'WHATEVER' })))
    expect((await loadAuthConfig()).mode).toBe('DEV')
    server.use(http.get('/api/v1/auth/config', () => new HttpResponse(null, { status: 500 })))
    expect((await loadAuthConfig()).mode).toBe('DEV')
    server.use(http.get('/api/v1/auth/config', () => HttpResponse.error()))
    expect((await loadAuthConfig()).mode).toBe('DEV')
  })
})

describe('identity headers', () => {
  it('sends the development email header in development mode and nothing when nobody is signed in', async () => {
    expect(await authorizationHeaders()).toEqual({})
    signIn('dev@example.test')
    expect(await authorizationHeaders()).toEqual({ 'X-Devforge-User': 'dev@example.test' })
  })

  it('sends a bearer token only after the provider sign-in completed, and never the development header', async () => {
    const { asManager } = fakeManager()
    configureAuth(OIDC, asManager)
    signIn('dev@example.test')
    expect(await authorizationHeaders()).toEqual({})
    window.sessionStorage.setItem('devforge.returnTo', '/projects/abc?tab=runs')
    expect(await completeProviderSignIn()).toBe('/projects/abc?tab=runs')
    expect(await authorizationHeaders()).toEqual({ Authorization: 'Bearer tok-abc' })
  })

  it('refuses to return to another site or back to the callback page after signing in', async () => {
    const { asManager } = fakeManager()
    configureAuth(OIDC, asManager)
    for (const bad of ['//evil.example/x', 'https://evil.example', '/auth/callback?code=1']) {
      window.sessionStorage.setItem('devforge.returnTo', bad)
      expect(await completeProviderSignIn()).toBe('/')
    }
  })

  it('renews a token that is about to expire, and signs in again if renewing is not possible', async () => {
    const first = fakeManager()
    first.m.signinCallback.mockResolvedValueOnce(first.user(10))
    configureAuth(OIDC, first.asManager)
    await completeProviderSignIn()
    expect(await authorizationHeaders()).toEqual({ Authorization: 'Bearer tok-abc' })
    expect(first.m.signinSilent).toHaveBeenCalledTimes(1)

    const second = fakeManager(() => Promise.reject(new Error('no refresh token')))
    second.m.signinCallback.mockResolvedValueOnce(second.user(5))
    configureAuth(OIDC, second.asManager)
    await completeProviderSignIn()
    expect(await authorizationHeaders()).toEqual({})
    expect(second.m.signinRedirect).toHaveBeenCalledTimes(1)
  })
})

describe('when the API says the session is over', () => {
  it('sends the person to sign in again, once, and keeps them out of a redirect loop', async () => {
    const { m, asManager } = fakeManager()
    configureAuth(OIDC, asManager)
    await completeProviderSignIn()
    server.use(http.get('/api/v1/project-types', () => HttpResponse.json({ code: 'UNAUTHENTICATED' }, { status: 401 })))
    await client.GET('/api/v1/project-types')
    await client.GET('/api/v1/project-types')
    expect(m.signinRedirect).toHaveBeenCalledTimes(1)
  })

  it('does nothing in development mode', async () => {
    sessionExpired()
    await signInWithProvider()
    expect(window.sessionStorage.getItem('devforge.returnTo')).toBeNull()
  })
})
