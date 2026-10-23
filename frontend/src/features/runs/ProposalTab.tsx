import { Alert, Empty, Spinner } from '../../components/ui/Alert'
import { Card, CardHeader } from '../../components/ui/Card'
import { errorMessage } from '../../lib/api/client'
import { shortHash } from '../../lib/format'
import { useArtifacts } from './api'
import { SimulatedBadge } from './RunBadges'

function strings(v: unknown): string[] {
  return Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : []
}

function List({ title, items, mono = false }: { title: string; items: string[]; mono?: boolean }) {
  if (items.length === 0) return null
  return (
    <div>
      <h3 className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted">{title}</h3>
      <ul className={`list-disc space-y-1 pl-5 text-sm ${mono ? 'font-mono text-xs' : ''}`}>
        {items.map((i) => (
          <li key={i}>{i}</li>
        ))}
      </ul>
    </div>
  )
}

export function ProposalTab({ runId }: { runId: string }) {
  const { data, isPending, error } = useArtifacts(runId)
  if (isPending) return <Spinner label="Loading proposal" />
  if (error) return <Alert tone="danger" title="Could not load the proposal">{errorMessage(error)}</Alert>
  const proposals = data.filter((a) => a.type === 'FIX_PROPOSAL')
  const current = proposals.find((a) => a.current)
  if (!current) return <Empty title="No fix proposal yet">A proposal is written after the investigation, when a fix is requested.</Empty>
  const c = current.content
  return (
    <Card>
      <CardHeader
        title="Fix proposal"
        subtitle={
          <>
            Fingerprint <span className="font-mono">{shortHash(current.contentHash)}</span>. Approvals are bound to exactly this text{proposals.length > 1 ? `; ${proposals.length - 1} earlier version(s) were replaced` : ''}.
          </>
        }
        actions={c.simulated === true ? <SimulatedBadge /> : undefined}
      />
      <div className="space-y-4 p-4">
        <p className="text-sm">{String(c.summary ?? '')}</p>
        <div>
          <h3 className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted">Recommended approach</h3>
          <p className="text-sm">{String(c.recommended ?? '')}</p>
        </div>
        <List title="Files that would change" items={strings(c.filesToChange)} mono />
        <List title="Commands that would run to check it" items={strings(c.commands)} mono />
        <List title="Alternatives considered" items={strings(c.alternatives)} />
        <List title="Risks" items={strings(c.risks)} />
        <List title="Test plan" items={strings(c.testPlan)} />
        <List title="Deliberately out of scope" items={strings(c.outOfScope)} />
      </div>
    </Card>
  )
}
