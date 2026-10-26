import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router'
import { Alert, Empty, Spinner } from '../../components/ui/Alert'
import { Badge, type Tone } from '../../components/ui/Badge'
import { Button } from '../../components/ui/Button'
import { Card, CardHeader } from '../../components/ui/Card'
import { Dialog } from '../../components/ui/Dialog'
import { Input, Select } from '../../components/ui/Field'
import { errorMessage } from '../../lib/api/client'
import { goTo } from '../../lib/navigation'
import { timeAgo, titleCase } from '../../lib/format'
import { useAdapterTypes, useConnections, useCreateConnection, useDeleteConnection, useStartSignIn, useVerifyConnection, type AdapterType, type Connection } from './api'

type Kind = Connection['kind']
type Auth = Connection['authMethod']

const KINDS: Array<{ value: Kind; label: string }> = [
  { value: 'AI_PROVIDER', label: 'AI provider' },
  { value: 'GIT_HOST', label: 'Git host' },
  { value: 'TRACKER', label: 'Issue tracker' },
]
const STATUS: Record<string, { label: string; tone: Tone }> = {
  VALID: { label: 'Verified', tone: 'ok' },
  UNVERIFIED: { label: 'Not verified', tone: 'neutral' },
  INVALID: { label: 'Invalid', tone: 'danger' },
  QUOTA: { label: 'Quota exhausted', tone: 'warn' },
}
const AVAILABILITY: Record<string, string> = { REAL: '', SIMULATED: ' (simulated in this build)', PLANNED: ' (planned: can be saved, not used yet)' }

function authLabel(a: Auth): string {
  return a === 'API_KEY' ? 'API key' : a === 'PAT' ? 'Access token' : 'Sign in with OAuth'
}

/** What the browser carries back from a single sign-on page: ?oauth=success, or ?oauth=error&reason=… (kept in page state, then removed from the address). */
function useSignInResult(): { outcome: 'success' | 'error'; reason: string } | null {
  const [params, setParams] = useSearchParams()
  // the page is opened fresh by the redirect, so the first render holds the answer; it is kept, and the address is cleaned afterwards
  const [result] = useState<{ outcome: 'success' | 'error'; reason: string } | null>(() => {
    const seen = params.get('oauth')
    return seen === 'success' || seen === 'error' ? { outcome: seen, reason: params.get('reason') ?? '' } : null
  })
  const hasResultInAddress = params.has('oauth')
  useEffect(() => {
    if (hasResultInAddress) setParams({}, { replace: true })
  }, [hasResultInAddress, setParams])
  return result
}

export function ConnectionsPage() {
  const connections = useConnections()
  const adapters = useAdapterTypes()
  const signInResult = useSignInResult()
  return (
    <div className="mx-auto max-w-3xl space-y-5">
      <div>
        <h1 className="text-lg font-semibold">My connections</h1>
        <p className="text-sm text-muted">The accounts DevForge uses on your behalf: your own AI provider keys, Git host access and issue tracker. Runs you start use only your credentials.</p>
      </div>
      <Alert tone="info" title="How your keys are handled">
        A key is encrypted when you save it and can never be shown again, not even to you. It is used only for runs you start, and it never appears in prompts, logs or this page. To change it, replace it.
      </Alert>

      {signInResult?.outcome === 'success' && <Alert tone="ok" title="Signed in">Your single sign-on connection was saved and checked. It appears below; the project’s tracker can now read tickets with it.</Alert>}
      {signInResult?.outcome === 'error' && <Alert tone="danger" title="Single sign-on did not complete">{signInResult.reason || 'The sign-in was not completed.'} You can try again below.</Alert>}

      {connections.isPending && <Spinner label="Loading connections" />}
      {connections.error && <Alert tone="danger" title="Could not load connections">{errorMessage(connections.error)}</Alert>}
      {connections.data && connections.data.length === 0 && <Empty title="No connections yet">Add an AI provider key to start runs.</Empty>}
      <ul className="space-y-3">
        {connections.data?.map((c) => (
          <ConnectionRow key={c.id} c={c} adapter={adapters.data?.find((a) => a.type === c.providerType && a.kind === c.kind)} />
        ))}
      </ul>
      {adapters.data && <AddConnection adapters={adapters.data} />}
    </div>
  )
}

function ConnectionRow({ c, adapter }: { c: Connection; adapter: AdapterType | undefined }) {
  const verify = useVerifyConnection()
  const del = useDeleteConnection()
  const [confirm, setConfirm] = useState(false)
  const s = STATUS[c.status] ?? { label: c.status, tone: 'neutral' as Tone }
  return (
    <li>
      <Card>
        <div className="flex flex-wrap items-start justify-between gap-3 p-4">
          <div className="min-w-0">
            <p className="font-medium">
              {c.label} <span className="font-normal text-muted">· {adapter?.displayName ?? c.providerType}</span>
            </p>
            <p className="mt-0.5 text-xs text-muted">
              {titleCase(c.kind)} · {authLabel(c.authMethod)} · added {timeAgo(c.createdAt)}
              {c.lastVerifiedAt ? ` · checked ${timeAgo(c.lastVerifiedAt)}` : ''}
            </p>
            {c.statusMessage && <p className="mt-1 text-xs">{c.statusMessage}</p>}
          </div>
          <div className="flex items-center gap-2">
            <Badge tone={s.tone}>{s.label}</Badge>
            <Button onClick={() => verify.mutate(c.id)} busy={verify.isPending}>
              Verify
            </Button>
            <Button variant="ghost" onClick={() => setConfirm(true)} aria-label={`Delete ${c.label}`}>
              Delete
            </Button>
          </div>
        </div>
        {verify.error && <div className="px-4 pb-4"><Alert tone="danger">{errorMessage(verify.error)}</Alert></div>}
      </Card>
      <Dialog open={confirm} onClose={() => setConfirm(false)} title="Delete this connection?">
        <p className="text-sm">The stored key is erased. Runs that are paused waiting for it cannot resume until you add another.</p>
        {del.error && <div className="mt-3"><Alert tone="danger">{errorMessage(del.error)}</Alert></div>}
        <div className="mt-4 flex justify-end gap-2">
          <Button onClick={() => setConfirm(false)}>Keep it</Button>
          <Button variant="danger" busy={del.isPending} onClick={() => del.mutate(c.id, { onSuccess: () => setConfirm(false) })}>
            Delete
          </Button>
        </div>
      </Dialog>
    </li>
  )
}

function AddConnection({ adapters }: { adapters: AdapterType[] }) {
  const create = useCreateConnection()
  const signIn = useStartSignIn()
  const [kind, setKind] = useState<Kind>('AI_PROVIDER')
  const forKind = useMemo(() => adapters.filter((a) => a.kind === kind), [adapters, kind])
  const [provider, setProvider] = useState('ANTHROPIC')
  const [auth, setAuth] = useState<Auth>('API_KEY')
  const [label, setLabel] = useState('')
  const [secret, setSecret] = useState('')
  const [saved, setSaved] = useState(false)

  const adapter = forKind.find((a) => a.type === provider) ?? forKind[0]
  const singleSignOn = adapter !== undefined && adapter.singleSignOn
  const ssoNotSetUp = adapter !== undefined && !adapter.singleSignOn && adapter.availability === 'REAL' && adapter.authMethods.includes('OAUTH')
  const usableAuth = adapter?.authMethods.filter((m) => m !== 'OAUTH') ?? []

  function changeKind(k: Kind) {
    setKind(k)
    const first = adapters.find((a) => a.kind === k)
    if (first) {
      setProvider(first.type)
      setAuth(first.authMethods.find((m) => m !== 'OAUTH') ?? 'API_KEY')
    }
  }

  function submit(e: FormEvent) {
    e.preventDefault()
    if (!adapter) return
    setSaved(false)
    create.mutate(
      { kind, providerType: adapter.type, authMethod: usableAuth.find((m) => m === auth) ?? usableAuth[0] ?? 'API_KEY', label: label.trim(), secret },
      {
        onSuccess: () => {
          setSecret('') // the secret never outlives the request in page state
          setLabel('')
          setSaved(true)
        },
      },
    )
  }

  return (
    <Card>
      <CardHeader title="Add a connection" />
      <form onSubmit={submit} className="space-y-3 p-4" autoComplete="off">
        <div className="grid gap-3 sm:grid-cols-2">
          <Select label="Type" value={kind} onChange={(e) => changeKind(KINDS.find((k) => k.value === e.target.value)?.value ?? 'AI_PROVIDER')}>
            {KINDS.map((k) => (
              <option key={k.value} value={k.value}>
                {k.label}
              </option>
            ))}
          </Select>
          <Select
            label="Provider"
            value={adapter?.type ?? ''}
            onChange={(e) => {
              const a = forKind.find((x) => x.type === e.target.value)
              if (a) {
                setProvider(a.type)
                setAuth(a.authMethods.find((m) => m !== 'OAUTH') ?? 'API_KEY')
              }
            }}
          >
            {forKind.map((a) => (
              <option key={a.type} value={a.type}>
                {a.displayName}
                {AVAILABILITY[a.availability] ?? ''}
              </option>
            ))}
          </Select>
        </div>
        {singleSignOn && adapter && (
          <div className="space-y-2 rounded-md border border-border bg-surface-2 p-3 text-sm">
            <p className="font-medium">Sign in with single sign-on</p>
            <p className="text-muted">
              {adapter.kind === 'TRACKER'
                ? `Use this when your organisation does not allow API tokens (a token then fails with “You don’t have permission to connect via API token”). You sign in on ${adapter.displayName}’s own page; DevForge never sees your password and only asks for read access.`
                : `Use this instead of creating a token. You sign in on ${adapter.displayName}’s own page and approve what DevForge may do (push branches and open pull requests); DevForge never sees your password.`}{' '}
              The tokens are stored encrypted and renewed automatically.
            </p>
            {signIn.error && <Alert tone="danger" title="Could not start the sign-in">{errorMessage(signIn.error)}</Alert>}
            <Button
              variant="primary"
              busy={signIn.isPending}
              onClick={() => signIn.mutate({ providerType: adapter.type, label: label.trim() || 'Single sign-on' }, { onSuccess: (r) => goTo(r.authorizationUrl) })}
            >
              Sign in with {adapter.displayName}
            </Button>
          </div>
        )}
        <div className="grid gap-3 sm:grid-cols-2">
          <Select label="How you sign in" value={auth} onChange={(e) => setAuth(usableAuth.find((m) => m === e.target.value) ?? 'API_KEY')} hint={singleSignOn ? 'Or use single sign-on above.' : ssoNotSetUp ? `Single sign-on for ${adapter?.displayName ?? 'this provider'} is not set up on this server (an administrator has to register an OAuth app); use a token.` : adapter?.authMethods.includes('OAUTH') ? 'Sign-in with OAuth is not available for this provider yet; use a token.' : undefined}>
            {usableAuth.map((m) => (
              <option key={m} value={m}>
                {authLabel(m)}
              </option>
            ))}
          </Select>
          <Input label="Label" value={label} onChange={(e) => setLabel(e.target.value)} placeholder="Personal key" maxLength={80} required />
        </div>
        <Input
          label={auth === 'PAT' ? 'Access token' : 'API key'}
          type="password"
          name="secret"
          autoComplete="new-password"
          spellCheck={false}
          value={secret}
          onChange={(e) => setSecret(e.target.value)}
          hint="Encrypted on save. You will not be able to see it again."
          required
        />
        {create.error && <Alert tone="danger" title="Could not save the connection">{errorMessage(create.error)}</Alert>}
        {saved && <Alert tone="ok">Saved. Press Verify on it to check that it works.</Alert>}
        <Button type="submit" variant="primary" busy={create.isPending} disabled={!adapter || label.trim() === '' || secret.trim() === ''}>
          Save connection
        </Button>
      </form>
    </Card>
  )
}
