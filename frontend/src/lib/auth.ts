import { InMemoryWebStorage, UserManager, WebStorageStateStore, type User } from 'oidc-client-ts'
import { useSyncExternalStore } from 'react'
import { currentUserEmail } from './session'

/**
 * How people sign in is decided by the server (GET /api/v1/auth/config): OIDC (the organisation's identity provider; authorization code + PKCE),
 * DEV (local development, an email typed into the page) or NONE. The tokens live only in memory: a reload signs in again, which is silent when
 * the provider already has a session. Nothing here knows about any particular provider.
 */
export interface AuthConfig {
  mode: 'OIDC' | 'DEV' | 'NONE'
  issuer: string
  clientId: string
  scopes: string[]
}

export const CALLBACK_PATH = '/auth/callback'
const RETURN_KEY = 'devforge.returnTo'

let mode: AuthConfig['mode'] = 'DEV'
let manager: UserManager | null = null
let user: User | null = null
const listeners = new Set<() => void>()
let redirecting = false

function notify() {
  listeners.forEach((l) => l())
}

function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v)
}

/** Asks the server how to sign in. If it cannot be reached or answers oddly, the local development sign-in stays in effect. */
export async function loadAuthConfig(): Promise<AuthConfig> {
  const fallback: AuthConfig = { mode: 'DEV', issuer: '', clientId: '', scopes: [] }
  try {
    const res = await globalThis.fetch('/api/v1/auth/config')
    if (!res.ok) return fallback
    const v: unknown = await res.json()
    if (!isRecord(v) || (v.mode !== 'OIDC' && v.mode !== 'DEV' && v.mode !== 'NONE')) return fallback
    return {
      mode: v.mode,
      issuer: typeof v.issuer === 'string' ? v.issuer : '',
      clientId: typeof v.clientId === 'string' ? v.clientId : '',
      scopes: Array.isArray(v.scopes) ? v.scopes.filter((s): s is string => typeof s === 'string') : [],
    }
  } catch {
    return fallback
  }
}

/** @param custom lets a test supply its own manager */
export function configureAuth(config: AuthConfig, custom?: UserManager): void {
  mode = config.mode
  user = null
  manager =
    config.mode !== 'OIDC'
      ? null
      : (custom ??
        new UserManager({
          authority: config.issuer,
          client_id: config.clientId,
          redirect_uri: `${window.location.origin}${CALLBACK_PATH}`,
          post_logout_redirect_uri: window.location.origin,
          response_type: 'code',
          scope: config.scopes.join(' '),
          // in memory: a token never touches storage that other scripts on the page could read
          userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
          automaticSilentRenew: false,
        }))
  notify()
}

export function authMode(): AuthConfig['mode'] {
  return mode
}

/** Headers that identify the caller to the API; empty when nobody is signed in. */
export async function authorizationHeaders(): Promise<Record<string, string>> {
  if (mode !== 'OIDC') {
    const email = currentUserEmail()
    return email ? { 'X-Devforge-User': email } : {}
  }
  const token = await accessToken()
  return token ? { Authorization: `Bearer ${token}` } : {}
}

async function accessToken(): Promise<string | null> {
  if (!manager || !user) return null
  if (user.expires_in !== undefined && user.expires_in < 30) {
    try {
      // uses a refresh token when the provider issued one (scope offline_access); otherwise this fails and the person signs in again
      user = (await manager.signinSilent()) ?? null
    } catch {
      user = null
      notify()
      void signInWithProvider()
      return null
    }
    notify()
  }
  return user?.access_token ?? null
}

/** Sends the browser to the provider's login page and remembers where to come back to. */
export async function signInWithProvider(returnTo?: string): Promise<void> {
  if (!manager || redirecting) return
  redirecting = true
  try {
    window.sessionStorage.setItem(RETURN_KEY, returnTo ?? `${window.location.pathname}${window.location.search}`)
  } catch {
    // storage unavailable: the person lands on the home page after signing in
  }
  await manager.signinRedirect()
}

/** Called on the callback page: completes the login and returns the path to go back to. */
export async function completeProviderSignIn(): Promise<string> {
  if (!manager) throw new Error('Sign-in with an identity provider is not configured')
  user = await manager.signinCallback() ?? null
  redirecting = false
  notify()
  let back = '/'
  try {
    back = window.sessionStorage.getItem(RETURN_KEY) ?? '/'
    window.sessionStorage.removeItem(RETURN_KEY)
  } catch {
    // ignore
  }
  return back.startsWith('/') && !back.startsWith('//') && !back.startsWith(CALLBACK_PATH) ? back : '/'
}

export async function signOutFromProvider(): Promise<void> {
  const m = manager
  user = null
  notify()
  if (m) await m.signoutRedirect()
}

/** Called when the API answers 401 although a token was sent: the session is over, sign in again. */
export function sessionExpired(): void {
  if (mode !== 'OIDC' || redirecting) return
  user = null
  notify()
  void signInWithProvider()
}

function subscribe(cb: () => void): () => void {
  listeners.add(cb)
  return () => listeners.delete(cb)
}

/** The person signed in through the provider, or null (also null when the provider mode is off). */
export function useProviderUser(): User | null {
  return useSyncExternalStore(subscribe, () => user, () => null)
}

export function useAuthMode(): AuthConfig['mode'] {
  return useSyncExternalStore(subscribe, () => mode, () => 'DEV' as const)
}
