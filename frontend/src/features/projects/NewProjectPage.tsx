import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { Alert, Spinner } from '../../components/ui/Alert'
import { Badge } from '../../components/ui/Badge'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { Input, Select } from '../../components/ui/Field'
import { ApiError, errorMessage } from '../../lib/api/client'
import { addRepository, REPO_ROLES, useCreateProject, useProjectTypes, useRepositorySources, type RepoRole } from './api'
import { locationBody, RepoLocationInput, type RepoSource } from './RepoLocation'

interface RepoDraft {
  key: number
  source: RepoSource
  location: string
  role: RepoRole
  name: string
}

type Outcome = { state: 'ok' } | { state: 'error'; message: string }

let nextKey = 1
const blankRepo = (role: RepoRole = 'BACKEND'): RepoDraft => ({ key: nextKey++, source: 'URL', location: '', role, name: '' })

/** Step 1: choose what kind of project. Step 2: name it and add one or more repositories, each with a role. Step 3: create. */
export function NewProjectPage() {
  const navigate = useNavigate()
  const types = useProjectTypes()
  const create = useCreateProject()
  const sources = useRepositorySources()
  const localPaths = sources.data?.localPaths ?? true
  const [step, setStep] = useState<1 | 2>(1)
  const [kind, setKind] = useState<string>('SUPPORT')
  const [name, setName] = useState('')
  const [repos, setRepos] = useState<RepoDraft[]>(() => [blankRepo('BACKEND'), blankRepo('WEB_FRONTEND')])
  const [projectId, setProjectId] = useState<string | null>(null)
  const [outcomes, setOutcomes] = useState<Record<number, Outcome>>({})
  const [busy, setBusy] = useState(false)
  const [fatal, setFatal] = useState<string | null>(null)

  const selected = types.data?.find((t) => t.kind === kind)
  const nameError = name.trim() === '' ? 'Give the project a name' : undefined
  const readyRepos = repos.filter((r) => r.location.trim() !== '')
  const canCreate = !busy && !nameError && readyRepos.length > 0

  function patch(key: number, change: Partial<RepoDraft>) {
    setRepos((rs) => rs.map((r) => (r.key === key ? { ...r, ...change } : r)))
  }

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (!canCreate || !selected) return
    setBusy(true)
    setFatal(null)
    try {
      let id = projectId
      if (id === null) {
        const created = await create.mutateAsync({ name: name.trim(), kind: selected.kind })
        id = created.id
        setProjectId(id)
      }
      const results: Record<number, Outcome> = { ...outcomes }
      for (const r of readyRepos) {
        if (results[r.key]?.state === 'ok') continue
        try {
          await addRepository(id, { ...locationBody(localPaths ? r.source : 'URL', r.location), role: r.role, ...(r.name.trim() ? { name: r.name.trim() } : {}) })
          results[r.key] = { state: 'ok' }
        } catch (err) {
          results[r.key] = { state: 'error', message: err instanceof ApiError ? err.message : errorMessage(err) }
        }
        setOutcomes({ ...results })
      }
      if (readyRepos.every((r) => results[r.key]?.state === 'ok')) void navigate(`/projects/${id}`)
    } catch (err) {
      setFatal(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  if (types.isPending) return <Spinner label="Loading project types" />
  if (types.error) return <Alert tone="danger" title="Could not load project types">{errorMessage(types.error)}</Alert>

  const failed = readyRepos.filter((r) => outcomes[r.key]?.state === 'error')

  return (
    <form onSubmit={submit} className="mx-auto max-w-3xl space-y-5">
      <div>
        <h1 className="text-lg font-semibold">New project</h1>
        <p className="text-sm text-muted">Step {step} of 2</p>
      </div>

      {step === 1 && (
        <fieldset className="space-y-3">
          <legend className="mb-1 text-sm font-medium">What kind of project is this?</legend>
          {types.data.map((t) => (
            <label key={t.kind} className={`flex items-start gap-3 rounded-lg border p-4 ${t.enabled ? 'cursor-pointer border-border bg-surface has-checked:border-accent has-checked:ring-1 has-checked:ring-accent' : 'cursor-not-allowed border-dashed border-border bg-surface-2 opacity-75'}`}>
              <input type="radio" name="kind" value={t.kind} checked={kind === t.kind} disabled={!t.enabled} onChange={() => setKind(t.kind)} className="mt-1" />
              <span className="space-y-1">
                <span className="flex items-center gap-2 font-medium">
                  {t.displayName}
                  {!t.enabled && <Badge tone="neutral">{t.disabledReason ?? 'Not available'}</Badge>}
                </span>
                <span className="block text-sm text-muted">{t.description}</span>
              </span>
            </label>
          ))}
          <Button type="button" variant="primary" disabled={!selected?.enabled} onClick={() => setStep(2)}>
            Continue
          </Button>
        </fieldset>
      )}

      {step === 2 && (
        <>
          <Input label="Project name" value={name} onChange={(e) => setName(e.target.value)} hint="For example the product or customer this support work is for" required maxLength={100} />

          <fieldset className="space-y-3">
            <legend className="text-sm font-medium">Repositories</legend>
            <p className="text-sm text-muted">
              Add every repository this project spans, for example a backend and a web frontend. A run can look at any of them together. Give each repository’s address and DevForge keeps its own copy up to date, using your connection for that repository.
            </p>
            {repos.map((r, i) => {
              const outcome = outcomes[r.key]
              return (
                <Card key={r.key} className="space-y-3 p-4">
                  <div className="flex items-center justify-between">
                    <h2 className="text-sm font-semibold">Repository {i + 1}</h2>
                    <div className="flex items-center gap-2">
                      {outcome?.state === 'ok' && <Badge tone="ok">Added</Badge>}
                      {repos.length > 1 && (
                        <Button variant="ghost" onClick={() => setRepos((rs) => rs.filter((x) => x.key !== r.key))} aria-label={`Remove repository ${i + 1}`} disabled={outcome?.state === 'ok'}>
                          Remove
                        </Button>
                      )}
                    </div>
                  </div>
                  <RepoLocationInput source={r.source} value={r.location} localPaths={localPaths} disabled={outcome?.state === 'ok'} onSource={(source) => patch(r.key, { source })} onValue={(location) => patch(r.key, { location })} />
                  <div className="grid gap-3 sm:grid-cols-[12rem_1fr]">
                    <Select label="Role" value={r.role} onChange={(e) => patch(r.key, { role: REPO_ROLES.find((x) => x.value === e.target.value)?.value ?? 'OTHER' })} disabled={outcome?.state === 'ok'}>
                      {REPO_ROLES.map((x) => (
                        <option key={x.value} value={x.value}>
                          {x.label}
                        </option>
                      ))}
                    </Select>
                    <Input label="Display name (optional)" value={r.name} onChange={(e) => patch(r.key, { name: e.target.value })} disabled={outcome?.state === 'ok'} maxLength={100} />
                  </div>
                  {outcome?.state === 'error' && <Alert tone="danger" title="Could not add this repository">{outcome.message}</Alert>}
                </Card>
              )
            })}
            <Button onClick={() => setRepos((rs) => [...rs, blankRepo('OTHER')])}>Add another repository</Button>
          </fieldset>

          {fatal && <Alert tone="danger" title="Could not create the project">{fatal}</Alert>}
          {projectId && failed.length > 0 && (
            <Alert tone="warn" title="The project was created, but some repositories could not be added">
              Fix the addresses above and press the button again to retry only those, or{' '}
              <Link className="underline" to={`/projects/${projectId}`}>
                open the project
              </Link>{' '}
              and add them later.
            </Alert>
          )}
          <div className="flex gap-2">
            <Button onClick={() => setStep(1)} disabled={busy || projectId !== null}>
              Back
            </Button>
            <Button type="submit" variant="primary" busy={busy} disabled={!canCreate}>
              {projectId ? 'Retry failed repositories' : 'Create project'}
            </Button>
          </div>
        </>
      )}
    </form>
  )
}
