import createClient, { type Middleware } from 'openapi-fetch'
import { currentUserEmail } from '../session'
import type { components, paths } from './schema'

export type Schemas = components['schemas']

/** An RFC 7807 problem response from the backend, with its stable machine-readable code. */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly traceId: string | undefined
  readonly fieldErrors: Record<string, string>

  constructor(status: number, code: string, message: string, traceId?: string, fieldErrors: Record<string, string> = {}) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.traceId = traceId
    this.fieldErrors = fieldErrors
  }
}

function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v)
}

export function toApiError(status: number, body: unknown): ApiError {
  if (!isRecord(body)) return new ApiError(status, `HTTP_${status}`, status === 401 ? 'Please sign in' : 'The request failed')
  const code = typeof body.code === 'string' ? body.code : `HTTP_${status}`
  const detail = typeof body.detail === 'string' ? body.detail : typeof body.title === 'string' ? body.title : 'The request failed'
  const errors: Record<string, string> = {}
  if (isRecord(body.errors)) for (const [k, v] of Object.entries(body.errors)) if (typeof v === 'string') errors[k] = v
  return new ApiError(status, code, detail, typeof body.traceId === 'string' ? body.traceId : undefined, errors)
}

const withIdentity: Middleware = {
  onRequest({ request }) {
    const email = currentUserEmail()
    if (email) request.headers.set('X-Devforge-User', email)
    return request
  },
}

// fetch is resolved on every call (not captured at import) so a host or test can substitute it after this module has loaded.
export const client = createClient<paths>({
  baseUrl: typeof window === 'undefined' ? '' : window.location.origin,
  fetch: (request) => globalThis.fetch(request),
})
client.use(withIdentity)

interface Result<T> {
  data?: T
  error?: unknown
  response: Response
}

/** Returns the body of a successful call, or throws an {@link ApiError}. Never casts: an empty success body is an error here. */
export async function unwrap<T>(call: Promise<Result<T>>): Promise<T> {
  const r = await call
  if (!r.response.ok) throw toApiError(r.response.status, r.error)
  if (r.data === undefined) throw new ApiError(r.response.status, 'EMPTY_RESPONSE', 'The server returned no data')
  return r.data
}

/** For endpoints that answer 204 No Content. */
export async function unwrapEmpty(call: Promise<{ error?: unknown; response: Response }>): Promise<void> {
  const r = await call
  if (!r.response.ok) throw toApiError(r.response.status, r.error)
}

export function errorMessage(e: unknown): string {
  return e instanceof ApiError ? e.message : e instanceof Error ? e.message : 'Something went wrong'
}
