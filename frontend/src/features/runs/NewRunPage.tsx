import { useMemo, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { Alert, Spinner } from '../../components/ui/Alert'
import { Badge } from '../../components/ui/Badge'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { Input, Textarea } from '../../components/ui/Field'
import { ApiError, errorMessage } from '../../lib/api/client'
import { useAgentProviders } from '../connections/api'
import { roleLabel, useProject, useRepos, useTracker } from '../projects/api'
import { AgentPicker, type AgentChoice } from './AgentPicker'
import { useCreateRun, usePreviewTicket, type RunMode, type TicketPreview } from './api'

const MODES: Array<{ value: RunMode; title: string; help: string }> = [
  { value: 'INVESTIGATE_ONLY', title: 'Investigate only', help: 'Find where and why. Nothing is changed. You decide what happens next.' },
  { value: 'PROPOSE_FIX', title: 'Investigate and propose a fix', help: 'Also writes a fix proposal for you to review. Still changes nothing.' },
  { value: 'FIX_AND_PATCH', title: 'Investigate, fix and prepare a patch', help: 'After you approve the proposal, prepares a patch per repository for review.' },
]

export function NewRunPage() {
  const { projectId = '' } = useParams()
  const navigate = useNavigate()
  const project = useProject(projectId)
  const repos = useRepos(projectId)
  const providers = useAgentProviders(projectId)
  const create = useCreateRun(projectId)
  const tracker = useTracker(projectId)
  const preview = usePreviewTicket(projectId)

  const [kind, setKind] = useState<'PROMPT' | 'TICKET'>('PROMPT')
  const [ticketKey, setTicketKey] = useState('')
  const [text, setText] = useState('')
  const [pasteInstead, setPasteInstead] = useState(false)
  const [mode, setMode] = useState<RunMode>('INVESTIGATE_ONLY')
  const [chosen, setChosen] = useState<Set<string> | null>(null)
  const [agent, setAgent] = useState<AgentChoice>({ provider: '', model: '', fastModel: '' })
  // One key per form, so a double click or a retry after a dropped connection can never start two runs.
  const idempotencyKey = useMemo(() => crypto.randomUUID(), [])

  if (project.isPending || repos.isPending || providers.isPending || tracker.isPending) return <Spinner label="Preparing the form" />
  if (project.error) return <Alert tone="danger" title="Could not open this project">{errorMessage(project.error)}</Alert>
  if (repos.error) return <Alert tone="danger" title="Could not load repositories">{errorMessage(repos.error)}</Alert>
  if (project.data.yourRole === 'VIEWER') return <Alert tone="warn" title="Your role cannot start runs">Ask a project admin to make you an operator.</Alert>

  const selected = chosen ?? new Set(repos.data.map((r) => r.repositoryId))
  const keyOk = kind === 'PROMPT' || /^[A-Z][A-Z0-9]{1,9}-\d{1,9}$/.test(ticketKey.trim())
  // With a tracker set, the ticket is read for the run from the tracker; otherwise (or by choice) the person pastes it.
  const fromTracker = kind === 'TICKET' && tracker.data?.configured === true && !pasteInstead
  const ready = selected.size > 0 && (fromTracker ? ticketKey.trim() !== '' : text.trim() !== '') && keyOk && agent.provider !== '' && agent.model !== ''

  function toggle(id: string) {
    const next = new Set(selected)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    setChosen(next)
  }

  function submit(e: FormEvent) {
    e.preventDefault()
    if (!ready) return
    create.mutate(
      {
        idempotencyKey,
        body: {
          mode,
          input: { kind, ...(fromTracker ? {} : { text: text.trim() }), ...(kind === 'TICKET' ? { ref: ticketKey.trim() } : {}) },
          repositoryIds: [...selected],
          agent: { provider: agent.provider, model: agent.model, ...(agent.fastModel ? { fastModel: agent.fastModel } : {}) },
        },
      },
      { onSuccess: (run) => void navigate(`/runs/${run.id}`) },
    )
  }

  const err = create.error
  return (
    <form onSubmit={submit} className="mx-auto max-w-3xl space-y-5">
      <div>
        <p className="text-sm text-muted">
          <Link className="underline" to={`/projects/${projectId}`}>
            {project.data.name}
          </Link>
        </p>
        <h1 className="text-lg font-semibold">New run</h1>
      </div>

      <fieldset className="space-y-3">
        <legend className="text-sm font-medium">What should DevForge look into?</legend>
        <div className="flex gap-4 text-sm">
          <label className="flex items-center gap-2">
            <input type="radio" name="kind" checked={kind === 'PROMPT'} onChange={() => setKind('PROMPT')} /> Describe it
          </label>
          <label className="flex items-center gap-2">
            <input type="radio" name="kind" checked={kind === 'TICKET'} onChange={() => setKind('TICKET')} /> From a ticket
          </label>
        </div>
        {kind === 'TICKET' && (
          <>
            <Input label="Ticket key" value={ticketKey} onChange={(e) => setTicketKey(e.target.value.toUpperCase())} placeholder="ABC-123" className="max-w-48 font-mono" error={ticketKey !== '' && !keyOk ? 'Use a key like ABC-123' : undefined} />
            {fromTracker ? (
              <TicketPreviewPanel
                busy={preview.isPending}
                disabled={!keyOk || ticketKey.trim() === ''}
                ticket={preview.data}
                error={preview.error}
                onPreview={() => preview.mutate(ticketKey.trim())}
                onPaste={() => setPasteInstead(true)}
              />
            ) : tracker.data?.configured ? (
              <p className="text-sm">
                <button type="button" className="underline" onClick={() => setPasteInstead(false)}>Read it from the tracker instead</button>
              </p>
            ) : (
              <Alert tone="info">This project has no issue tracker set, so paste the ticket text below; the key is kept for reference.</Alert>
            )}
          </>
        )}
        {!fromTracker && <Textarea label={kind === 'TICKET' ? 'Ticket text' : 'Description'} value={text} onChange={(e) => setText(e.target.value)} rows={7} maxLength={20000} hint="Say what happens, what you expected, and any names, screens or endpoints you know. This text is treated as data, never as instructions." />}
      </fieldset>

      <fieldset className="space-y-2">
        <legend className="text-sm font-medium">Repositories to look at</legend>
        <ul className="space-y-2">
          {repos.data.map((r) => (
            <li key={r.repositoryId}>
              <label className="flex items-center gap-3 rounded-md border border-border bg-surface px-3 py-2 text-sm">
                <input type="checkbox" checked={selected.has(r.repositoryId)} onChange={() => toggle(r.repositoryId)} />
                <span className="font-medium">{r.name}</span>
                <Badge>{roleLabel(r.role)}</Badge>
                <span className="ml-auto font-mono text-xs text-muted">{r.profile?.buildTools[0]?.tool ?? 'no build tool detected'}</span>
              </label>
            </li>
          ))}
        </ul>
        {selected.size === 0 && <p role="alert" className="text-xs text-danger">Choose at least one repository.</p>}
      </fieldset>

      <fieldset className="space-y-2">
        <legend className="text-sm font-medium">How far should it go?</legend>
        <p className="text-xs text-muted">Changes are made in a private copy of each repository and tests run in an isolated sandbox with no network. Your checkouts are never touched, and nothing is applied without your approval.</p>
        {MODES.map((m) => {
          return (
            <label key={m.value} className={`flex items-start gap-3 rounded-md border p-3 text-sm border-border bg-surface has-checked:border-accent has-checked:ring-1 has-checked:ring-accent`}>
              <input type="radio" name="mode" className="mt-1" checked={mode === m.value} onChange={() => setMode(m.value)} />
              <span>
                <span className="block font-medium">{m.title}</span>
                <span className="block text-muted">{m.help}</span>
              </span>
            </label>
          )
        })}
      </fieldset>

      <Card className="space-y-3 p-4">
        <h2 className="text-sm font-semibold">AI model</h2>
        <AgentPicker providers={providers.data ?? []} value={agent} onChange={setAgent} />
      </Card>

      {err && (
        <Alert tone="danger" title="The run could not be started">
          {errorMessage(err)}
          {err instanceof ApiError && err.code === 'NO_USABLE_CONNECTION' && (
            <>
              {' '}
              <Link className="underline" to="/connections">
                Open My connections
              </Link>
            </>
          )}
        </Alert>
      )}
      <Button type="submit" variant="primary" busy={create.isPending} disabled={!ready}>
        Start run
      </Button>
    </form>
  )
}

function TicketPreviewPanel({ busy, disabled, ticket, error, onPreview, onPaste }: { busy: boolean; disabled: boolean; ticket: TicketPreview | undefined; error: unknown; onPreview: () => void; onPaste: () => void }) {
  const code = error instanceof ApiError ? error.code : ''
  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center gap-3">
        <Button onClick={onPreview} busy={busy} disabled={disabled}>
          Preview ticket
        </Button>
        <span className="text-xs text-muted">Read with your own tracker connection. Nothing is changed in the tracker.</span>
        <button type="button" className="ml-auto text-xs underline" onClick={onPaste}>
          Paste the text instead
        </button>
      </div>
      {error !== null && error !== undefined && (
        <Alert tone="warn" title="The ticket could not be read">
          {errorMessage(error)}{' '}
          {(code === 'TRACKER_NOT_CONFIGURED' || code === 'TRACKER_AUTH_INVALID') && (
            <Link className="underline" to="/connections">
              Open My connections
            </Link>
          )}
        </Alert>
      )}
      {ticket && (
        <Card className="space-y-2 p-3 text-sm">
          <p className="font-medium">
            <span className="font-mono">{ticket.key}</span> · {ticket.title}
          </p>
          <p className="flex flex-wrap gap-2 text-xs text-muted">
            {[ticket.type, ticket.status, ticket.priority, ...ticket.components].filter((v): v is string => typeof v === 'string' && v !== '').map((v) => (
              <Badge key={v}>{v}</Badge>
            ))}
          </p>
          {ticket.description && <p className="line-clamp-6 whitespace-pre-wrap text-muted">{ticket.description}</p>}
          <p className="text-xs text-muted">
            {ticket.comments.filter((c) => !c.routine).length} comment(s) will be read; {ticket.comments.filter((c) => c.routine).length} routine status update(s) are skipped.
          </p>
        </Card>
      )}
    </div>
  )
}
