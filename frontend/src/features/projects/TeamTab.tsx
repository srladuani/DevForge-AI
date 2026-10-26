import { useState, type FormEvent } from 'react'
import { Alert, Spinner } from '../../components/ui/Alert'
import { Button } from '../../components/ui/Button'
import { Card, CardHeader } from '../../components/ui/Card'
import { Input, Select } from '../../components/ui/Field'
import { errorMessage } from '../../lib/api/client'
import { titleCase } from '../../lib/format'
import { useAdapterTypes } from '../connections/api'
import { useClearTracker, useMembers, usePublishSettings, useRemoveMember, useSetMember, useSetPublishSettings, useSetTracker, useTracker, useUpdateProject, type Policy, type Project, type ProjectRole } from './api'

const ROLES: ProjectRole[] = ['VIEWER', 'OPERATOR', 'APPROVER', 'ADMIN']
const ROLE_HELP: Record<ProjectRole, string> = {
  VIEWER: 'Can see runs and results',
  OPERATOR: 'Can start runs and answer questions',
  APPROVER: 'Can also approve changes',
  ADMIN: 'Manages repositories, people and policy',
}

function roleOf(v: string): ProjectRole {
  return ROLES.find((r) => r === v) ?? 'VIEWER'
}

export function TeamTab({ project }: { project: Project }) {
  const canManage = project.yourRole === 'ADMIN'
  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <MembersCard projectId={project.id} canManage={canManage} />
      <PolicyCard project={project} canManage={canManage} />
      <TrackerCard projectId={project.id} canManage={canManage} />
      <PublishingCard projectId={project.id} canManage={canManage} />
    </div>
  )
}

function MembersCard({ projectId, canManage }: { projectId: string; canManage: boolean }) {
  const { data, isPending, error } = useMembers(projectId)
  const set = useSetMember(projectId)
  const remove = useRemoveMember(projectId)
  const [email, setEmail] = useState('')
  const [role, setRole] = useState<ProjectRole>('OPERATOR')

  function add(e: FormEvent) {
    e.preventDefault()
    set.mutate({ email: email.trim(), role }, { onSuccess: () => setEmail('') })
  }

  return (
    <Card>
      <CardHeader title="People" subtitle="Each person uses their own AI credentials for the runs they start." />
      <div className="space-y-3 p-4">
        {isPending && <Spinner label="Loading people" />}
        {error && <Alert tone="danger">{errorMessage(error)}</Alert>}
        <ul className="divide-y divide-border text-sm">
          {data?.map((m) => (
            <li key={m.userId} className="flex flex-wrap items-center justify-between gap-2 py-2">
              <span className="min-w-0">
                <span className="block truncate font-medium">{m.displayName}</span>
                <span className="block truncate text-xs text-muted">{m.email}</span>
              </span>
              <span className="flex items-center gap-2">
                {canManage ? (
                  <select aria-label={`Role of ${m.email}`} value={m.role} onChange={(e) => set.mutate({ email: m.email, role: roleOf(e.target.value) })} className="rounded-md border border-border bg-surface px-2 py-1 text-sm">
                    {ROLES.map((r) => (
                      <option key={r} value={r}>
                        {titleCase(r)}
                      </option>
                    ))}
                  </select>
                ) : (
                  <span>{titleCase(m.role)}</span>
                )}
                {canManage && (
                  <Button variant="ghost" onClick={() => remove.mutate(m.userId)} aria-label={`Remove ${m.email}`}>
                    Remove
                  </Button>
                )}
              </span>
            </li>
          ))}
        </ul>
        {(set.error || remove.error) && <Alert tone="danger">{errorMessage(set.error ?? remove.error)}</Alert>}
        {canManage && (
          <form onSubmit={add} className="space-y-3 border-t border-border pt-3">
            <Input label="Add a person by email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} hint="They must have signed in to DevForge once." required />
            <Select label="Role" value={role} onChange={(e) => setRole(roleOf(e.target.value))} hint={ROLE_HELP[role]}>
              {ROLES.map((r) => (
                <option key={r} value={r}>
                  {titleCase(r)}
                </option>
              ))}
            </Select>
            <Button type="submit" variant="primary" busy={set.isPending} disabled={email.trim() === ''}>
              Add person
            </Button>
          </form>
        )}
      </div>
    </Card>
  )
}

function PolicyCard({ project, canManage }: { project: Project; canManage: boolean }) {
  const update = useUpdateProject(project.id)
  const adapters = useAdapterTypes()
  const aiTypes = adapters.data?.filter((a) => a.kind === 'AI_PROVIDER') ?? []
  const [policy, setPolicy] = useState<Policy>(project.policy)
  const allowAll = policy.allowedProviders.length === 0

  function toggle(type: string) {
    const has = policy.allowedProviders.includes(type)
    setPolicy({ ...policy, allowedProviders: has ? policy.allowedProviders.filter((t) => t !== type) : [...policy.allowedProviders, type] })
  }

  return (
    <Card>
      <CardHeader title="Policy" subtitle="Limits set by people. Nothing an AI produces can change them." />
      <form
        className="space-y-4 p-4"
        onSubmit={(e) => {
          e.preventDefault()
          update.mutate({ policy })
        }}
      >
        <fieldset disabled={!canManage} className="space-y-4">
          <div>
            <p className="text-sm font-medium">AI providers allowed in this project</p>
            <p className="text-xs text-muted">{allowAll ? 'All configured providers are allowed.' : 'Only the ticked providers can be chosen for runs.'}</p>
            <div className="mt-2 flex flex-wrap gap-3">
              {aiTypes.map((a) => (
                <label key={a.type} className="flex items-center gap-2 text-sm">
                  <input type="checkbox" checked={policy.allowedProviders.includes(a.type)} onChange={() => toggle(a.type)} />
                  {a.displayName}
                </label>
              ))}
            </div>
          </div>
          <label className="flex items-start gap-2 text-sm">
            <input type="checkbox" className="mt-1" checked={policy.requireDifferentApprover} onChange={(e) => setPolicy({ ...policy, requireDifferentApprover: e.target.checked })} />
            <span>
              Someone other than the requester must approve
              <span className="block text-xs text-muted">Separation of duties for approvals.</span>
            </span>
          </label>
          <div className="grid gap-3 sm:grid-cols-2">
            <Input label="Soft budget per run (USD)" type="number" min={0} max={1000} step="0.5" value={policy.maxRunCostUsd} onChange={(e) => setPolicy({ ...policy, maxRunCostUsd: Number(e.target.value) })} />
            <Input label="Runs executing at once" type="number" min={1} max={20} value={policy.maxConcurrentRuns} onChange={(e) => setPolicy({ ...policy, maxConcurrentRuns: Number(e.target.value) })} />
          </div>
        </fieldset>
        {update.error && <Alert tone="danger" title="Could not save the policy">{errorMessage(update.error)}</Alert>}
        {update.isSuccess && <Alert tone="ok">Policy saved.</Alert>}
        {canManage ? (
          <Button type="submit" variant="primary" busy={update.isPending}>
            Save policy
          </Button>
        ) : (
          <p className="text-xs text-muted">Only project admins can change the policy.</p>
        )}
      </form>
    </Card>
  )
}

function TrackerCard({ projectId, canManage }: { projectId: string; canManage: boolean }) {
  const tracker = useTracker(projectId)
  const types = useAdapterTypes()
  const set = useSetTracker(projectId)
  const clear = useClearTracker(projectId)
  const [site, setSite] = useState<string | null>(null)

  if (tracker.isPending || types.isPending) return <Spinner label="Loading issue tracker" />
  if (tracker.error) return <Alert tone="danger" title="Could not load the issue tracker">{errorMessage(tracker.error)}</Alert>
  const available = (types.data ?? []).filter((t) => t.kind === 'TRACKER' && t.availability === 'REAL')
  const current = tracker.data
  const siteValue = site ?? current.siteHint ?? ''

  function save(providerType: string) {
    set.mutate({ providerType, ...(siteValue.trim() ? { siteHint: siteValue.trim() } : {}) }, { onSuccess: () => setSite(null) })
  }

  return (
    <Card>
      <CardHeader title="Issue tracker" />
      <div className="space-y-3 p-4 text-sm">
        <p className="text-muted">
          {current.configured
            ? 'Runs can read tickets from this tracker. Each person uses their own connection; nothing is ever written back to it.'
            : 'No tracker is set, so people paste ticket text when they start a run.'}
        </p>
        {available.length === 0 ? (
          <p className="text-muted">No tracker integration is available on this server.</p>
        ) : (
          <>
            <p>
              {current.configured ? (
                <>
                  Using <strong>{available.find((t) => t.type === current.providerType)?.displayName ?? current.providerType}</strong>
                </>
              ) : (
                'Not set'
              )}
            </p>
            {canManage && (
              <div className="space-y-2">
                <Input label="Site (optional)" value={siteValue} onChange={(e) => setSite(e.target.value)} hint="Only needed when a credential reaches several sites: use the site name or its address." />
                <div className="flex gap-2">
                  {available.map((t) => (
                    <Button key={t.type} variant="primary" busy={set.isPending} onClick={() => save(t.type)}>
                      {current.configured && current.providerType === t.type ? 'Save' : `Use ${t.displayName}`}
                    </Button>
                  ))}
                  {current.configured && (
                    <Button variant="secondary" busy={clear.isPending} onClick={() => clear.mutate()}>
                      Remove
                    </Button>
                  )}
                </div>
              </div>
            )}
            {(set.error ?? clear.error) && <Alert tone="danger" title="Could not change the tracker">{errorMessage(set.error ?? clear.error)}</Alert>}
          </>
        )}
      </div>
    </Card>
  )
}

function PublishingCard({ projectId, canManage }: { projectId: string; canManage: boolean }) {
  const settings = usePublishSettings(projectId)
  const save = useSetPublishSettings(projectId)
  const [target, setTarget] = useState<string | null>(null)
  const [prefix, setPrefix] = useState<string | null>(null)
  const [draft, setDraft] = useState<boolean | null>(null)

  if (settings.isPending) return <Spinner label="Loading pull request settings" />
  if (settings.error) return <Alert tone="danger" title="Could not load pull request settings">{errorMessage(settings.error)}</Alert>
  const current = settings.data
  const targetValue = target ?? current.targetBranch ?? ''
  const prefixValue = prefix ?? current.branchPrefix
  const draftValue = draft ?? current.draft

  function submit(e: FormEvent) {
    e.preventDefault()
    save.mutate({ ...(targetValue.trim() ? { targetBranch: targetValue.trim() } : {}), branchPrefix: prefixValue.trim(), draft: draftValue }, { onSuccess: () => { setTarget(null); setPrefix(null); setDraft(null) } })
  }

  return (
    <Card>
      <CardHeader title="Pull requests" subtitle="Where DevForge sends a finished change" />
      <form onSubmit={submit} className="space-y-3 p-4 text-sm">
        <p className="text-muted">
          After a change is approved, DevForge pushes a new branch named <code className="font-mono text-xs">{prefixValue}&lt;ticket&gt;-&lt;summary&gt;</code> and opens a pull request into the target branch. Nothing is merged.
        </p>
        <Input label="Target branch" value={targetValue} onChange={(e) => setTarget(e.target.value)} disabled={!canManage} placeholder="the repository’s default branch" className="font-mono" hint="For example develop. Leave empty to use each repository’s default branch." />
        <Input label="Branch prefix" value={prefixValue} onChange={(e) => setPrefix(e.target.value)} disabled={!canManage} className="font-mono" hint="For example feature/ or devforge/." />
        <label className="flex items-center gap-2">
          <input type="checkbox" checked={draftValue} disabled={!canManage} onChange={(e) => setDraft(e.target.checked)} />
          Open pull requests as drafts
        </label>
        {save.error && <Alert tone="danger" title="Could not save">{errorMessage(save.error)}</Alert>}
        {canManage && (
          <Button type="submit" variant="primary" busy={save.isPending}>
            Save
          </Button>
        )}
      </form>
    </Card>
  )
}
