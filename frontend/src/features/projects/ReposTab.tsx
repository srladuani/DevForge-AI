import { useState, type FormEvent } from 'react'
import { Alert, Empty, Spinner } from '../../components/ui/Alert'
import { Badge } from '../../components/ui/Badge'
import { Button } from '../../components/ui/Button'
import { Card, CardHeader } from '../../components/ui/Card'
import { Dialog } from '../../components/ui/Dialog'
import { Input, Select } from '../../components/ui/Field'
import { errorMessage } from '../../lib/api/client'
import { shortSha } from '../../lib/format'
import { REPO_ROLES, roleLabel, useAddRepo, useRefreshRepo, useRemoveRepo, useRepos, useUpdateRepo, type ProjectRepo, type RepoRole } from './api'

export function ReposTab({ projectId, canManage }: { projectId: string; canManage: boolean }) {
  const { data, isPending, error } = useRepos(projectId)
  const [removing, setRemoving] = useState<ProjectRepo | null>(null)
  const remove = useRemoveRepo(projectId)
  const refresh = useRefreshRepo(projectId)
  const update = useUpdateRepo(projectId)

  return (
    <div className="space-y-4">
      {isPending && <Spinner label="Loading repositories" />}
      {error && <Alert tone="danger" title="Could not load repositories">{errorMessage(error)}</Alert>}
      {data && data.length === 0 && <Empty title="No repositories">Add the repositories this project covers.</Empty>}
      {refresh.error && <Alert tone="danger" title="Refresh failed">{errorMessage(refresh.error)}</Alert>}
      <div className="grid gap-3 lg:grid-cols-2">
        {data?.map((r) => (
          <Card key={r.repositoryId}>
            <CardHeader
              title={r.name}
              subtitle={<span className="font-mono">{r.localPath}</span>}
              actions={
                canManage ? (
                  <>
                    <Button onClick={() => refresh.mutate(r.repositoryId)} busy={refresh.isPending && refresh.variables === r.repositoryId}>
                      Refresh
                    </Button>
                    <Button variant="ghost" onClick={() => setRemoving(r)}>
                      Remove
                    </Button>
                  </>
                ) : undefined
              }
            />
            <dl className="space-y-3 p-4 text-sm">
              <div className="flex items-center justify-between gap-3">
                <dt className="text-muted">Role</dt>
                <dd>
                  {canManage ? (
                    <select
                      aria-label={`Role of ${r.name}`}
                      value={r.role}
                      onChange={(e) => update.mutate({ repoId: r.repositoryId, body: { role: REPO_ROLES.find((x) => x.value === e.target.value)?.value ?? 'OTHER' } })}
                      className="rounded-md border border-border bg-surface px-2 py-1 text-sm"
                    >
                      {REPO_ROLES.map((x) => (
                        <option key={x.value} value={x.value}>
                          {x.label}
                        </option>
                      ))}
                    </select>
                  ) : (
                    <Badge>{roleLabel(r.role)}</Badge>
                  )}
                </dd>
              </div>
              <div className="flex justify-between gap-3">
                <dt className="text-muted">Branch / commit</dt>
                <dd className="font-mono text-xs">
                  {r.defaultBranch ?? '–'} @ {shortSha(r.headSha) || '–'}
                </dd>
              </div>
              {r.profile && (
                <>
                  <div className="flex justify-between gap-3">
                    <dt className="text-muted">Languages</dt>
                    <dd className="text-right">{Object.entries(r.profile.languages).map(([l, n]) => `${l} (${n})`).join(', ') || '–'}</dd>
                  </div>
                  <div>
                    <dt className="mb-1 text-muted">Detected build tools</dt>
                    <dd>
                      {r.profile.buildTools.length === 0 && <span className="text-muted">None detected. Only generic search applies; validation is unavailable.</span>}
                      <ul className="space-y-2">
                        {r.profile.buildTools.map((b) => (
                          <li key={`${b.tool}-${b.root}`} className="rounded-md bg-surface-2 p-2">
                            <p className="font-medium">
                              {b.tool}
                              {b.toolchain && <span className="font-normal text-muted"> · {b.toolchain}</span>}
                            </p>
                            {b.frameworks.length > 0 && <p className="text-xs text-muted">{b.frameworks.join(', ')}</p>}
                            {b.testCommand && <p className="mt-1 font-mono text-xs">test: {b.testCommand}</p>}
                          </li>
                        ))}
                      </ul>
                    </dd>
                  </div>
                  {r.profile.instructionFiles.length > 0 && (
                    <div>
                      <dt className="mb-1 text-muted">Instruction files found</dt>
                      <dd>
                        <p className="font-mono text-xs">{r.profile.instructionFiles.join(', ')}</p>
                        <p className="mt-1 text-xs text-muted">Treated as untrusted guidance: they may add restrictions for this repository but can never grant permissions.</p>
                      </dd>
                    </div>
                  )}
                </>
              )}
            </dl>
          </Card>
        ))}
      </div>
      {canManage && <AddRepoForm projectId={projectId} />}

      <Dialog open={removing !== null} onClose={() => setRemoving(null)} title="Remove repository?">
        <p className="text-sm">
          Remove <strong>{removing?.name}</strong> from this project? Existing runs keep their history. The repository on disk is not touched.
        </p>
        {remove.error && <div className="mt-3"><Alert tone="danger">{errorMessage(remove.error)}</Alert></div>}
        <div className="mt-4 flex justify-end gap-2">
          <Button onClick={() => setRemoving(null)}>Cancel</Button>
          <Button
            variant="danger"
            busy={remove.isPending}
            onClick={() => removing && remove.mutate(removing.repositoryId, { onSuccess: () => setRemoving(null) })}
          >
            Remove
          </Button>
        </div>
      </Dialog>
    </div>
  )
}

function AddRepoForm({ projectId }: { projectId: string }) {
  const add = useAddRepo(projectId)
  const [path, setPath] = useState('')
  const [role, setRole] = useState<RepoRole>('BACKEND')
  const [name, setName] = useState('')

  function submit(e: FormEvent) {
    e.preventDefault()
    add.mutate({ path: path.trim(), role, ...(name.trim() ? { name: name.trim() } : {}) }, { onSuccess: () => { setPath(''); setName('') } })
  }

  return (
    <Card>
      <CardHeader title="Add a repository" subtitle="Local path for now; cloning from a URL arrives with managed clones." />
      <form onSubmit={submit} className="space-y-3 p-4">
        <div className="grid gap-3 sm:grid-cols-[1fr_12rem]">
          <Input label="Path on this machine" value={path} onChange={(e) => setPath(e.target.value)} className="font-mono" required />
          <Select label="Role" value={role} onChange={(e) => setRole(REPO_ROLES.find((x) => x.value === e.target.value)?.value ?? 'OTHER')}>
            {REPO_ROLES.map((x) => (
              <option key={x.value} value={x.value}>
                {x.label}
              </option>
            ))}
          </Select>
        </div>
        <Input label="Display name (optional)" value={name} onChange={(e) => setName(e.target.value)} />
        {add.error && <Alert tone="danger" title="Could not add the repository">{errorMessage(add.error)}</Alert>}
        <Button type="submit" variant="primary" busy={add.isPending} disabled={path.trim() === ''}>
          Add repository
        </Button>
      </form>
    </Card>
  )
}
