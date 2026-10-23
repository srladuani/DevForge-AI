import { useSyncExternalStore } from 'react'

/**
 * Local-development sign-in only: the backend's "local" profile trusts an email header. Production uses single sign-on (a later
 * milestone), at which point this module is replaced; nothing else in the app knows how identity is established.
 * The email is a per-viewer convenience kept in localStorage, never a secret.
 */
const KEY = 'devforge.devUser'
const listeners = new Set<() => void>()
let memory: string | null = null

function read(): string | null {
  try {
    return window.localStorage.getItem(KEY) ?? memory
  } catch {
    return memory
  }
}

export function currentUserEmail(): string | null {
  return read()
}

export function signIn(email: string): void {
  memory = email
  try {
    window.localStorage.setItem(KEY, email)
  } catch {
    // storage unavailable (private window): the in-memory value still works for this tab
  }
  listeners.forEach((l) => l())
}

export function signOut(): void {
  memory = null
  try {
    window.localStorage.removeItem(KEY)
  } catch {
    // ignore
  }
  listeners.forEach((l) => l())
}

function subscribe(cb: () => void): () => void {
  listeners.add(cb)
  return () => listeners.delete(cb)
}

export function useUserEmail(): string | null {
  return useSyncExternalStore(subscribe, read, () => null)
}
