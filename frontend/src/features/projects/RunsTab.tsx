import { useState } from 'react'
import { Link } from 'react-router'
import { Alert, Empty, Spinner } from '../../components/ui/Alert'
import { Badge } from '../../components/ui/Badge'
import { Select } from '../../components/ui/Field'
import { errorMessage } from '../../lib/api/client'
import { modeLabel, timeAgo } from '../../lib/format'
import { RunStatusBadge, SimulatedBadge } from '../runs/RunBadges'
import { useRuns, type Run } from '../runs/api'

const FILTERS: Array<{ value: '' | Run['status']; label: string }> = [
  { value: '', label: 'All runs' },
  { value: 'FINDINGS_AVAILABLE', label: 'Findings ready' },
  { value: 'AWAITING_APPROVAL', label: 'Awaiting approval' },
  { value: 'AWAITING_REVIEW', label: 'Awaiting review' },
  { value: 'AWAITING_INPUT', label: 'Needs input' },
  { value: 'FAILED', label: 'Failed' },
  { value: 'COMPLETED', label: 'Completed' },
]

export function RunsTab({ projectId, canRun }: { projectId: string; canRun: boolean }) {
  const [filter, setFilter] = useState<'' | Run['status']>('')
  const { data, isPending, error } = useRuns(projectId, filter || undefined)
  return (
    <div className="space-y-3">
      <div className="max-w-56">
        <Select label="Show" value={filter} onChange={(e) => setFilter(FILTERS.find((f) => f.value === e.target.value)?.value ?? '')}>
          {FILTERS.map((f) => (
            <option key={f.value} value={f.value}>
              {f.label}
            </option>
          ))}
        </Select>
      </div>
      {isPending && <Spinner label="Loading runs" />}
      {error && <Alert tone="danger" title="Could not load runs">{errorMessage(error)}</Alert>}
      {data && data.length === 0 && (
        <Empty title={filter ? 'No runs match this filter' : 'No runs yet'}>
          {canRun ? (
            <Link className="text-accent underline" to={`/projects/${projectId}/runs/new`}>
              Start the first run
            </Link>
          ) : (
            'Ask a project operator to start one.'
          )}
        </Empty>
      )}
      {data && data.length > 0 && (
        <ul className="divide-y divide-border rounded-lg border border-border bg-surface">
          {data.map((r) => (
            <li key={r.id}>
              <Link to={`/runs/${r.id}`} className="grid gap-1 px-4 py-3 hover:bg-surface-2 sm:grid-cols-[1fr_auto] sm:items-center">
                <span className="min-w-0">
                  <span className="block truncate text-sm font-medium">{r.title}</span>
                  <span className="mt-0.5 block text-xs text-muted">
                    {modeLabel(r.mode)} · {r.repositories.length} repositor{r.repositories.length === 1 ? 'y' : 'ies'} · {r.agent.model} · {timeAgo(r.createdAt)}
                  </span>
                </span>
                <span className="flex flex-wrap items-center gap-2">
                  {r.agent.simulated && <SimulatedBadge />}
                  {r.incomplete && <Badge tone="warn">Incomplete</Badge>}
                  <RunStatusBadge status={r.status} />
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
