import { Link, useParams, useSearchParams } from 'react-router'
import { Alert, Spinner } from '../../components/ui/Alert'
import { Badge } from '../../components/ui/Badge'
import { Tabs } from '../../components/ui/Tabs'
import { errorMessage } from '../../lib/api/client'
import { formatTokens, modeLabel } from '../../lib/format'
import { useProject } from '../projects/api'
import { useApprovals } from '../approvals/api'
import { useFindings, useRun, useRunEvents } from './api'
import { ApprovalsTab } from './ApprovalsTab'
import { ChangesTab } from './ChangesTab'
import { FindingsTab } from './FindingsTab'
import { OverviewTab } from './OverviewTab'
import { ProposalTab } from './ProposalTab'
import { RunStatusBadge, SimulatedBadge } from './RunBadges'
import { RunActionsBar } from './RunActionsBar'
import { TimelineTab } from './TimelineTab'

const TABS = ['overview', 'findings', 'proposal', 'changes', 'approvals', 'timeline'] as const
type Tab = (typeof TABS)[number]

function defaultTab(status: string): Tab {
  if (status === 'AWAITING_APPROVAL') return 'approvals'
  if (status === 'AWAITING_REVIEW') return 'changes'
  if (status === 'FINDINGS_AVAILABLE') return 'findings'
  if (status === 'FIX_PROPOSAL_AVAILABLE') return 'proposal'
  return 'overview'
}

export function RunPage() {
  const { runId = '' } = useParams()
  const [params, setParams] = useSearchParams()
  const run = useRun(runId)
  const live = useRunEvents(runId)
  const project = useProject(run.data?.workspaceId ?? '')
  const findings = useFindings(runId)
  const approvals = useApprovals(run.data?.workspaceId ?? '', runId)

  if (run.isPending) return <Spinner label="Loading run" />
  if (run.error) return <Alert tone="danger" title="Could not open this run">{errorMessage(run.error)}</Alert>

  const r = run.data
  const requested = params.get('tab')
  const tab: Tab = TABS.find((t) => t === requested) ?? defaultTab(r.status)
  const canApprove = project.data?.yourRole === 'APPROVER' || project.data?.yourRole === 'ADMIN'
  const pending = approvals.data?.filter((a) => a.status === 'PENDING').length ?? 0

  return (
    <div className="space-y-4">
      <div>
        <p className="text-sm text-muted">
          <Link className="underline" to={`/projects/${r.workspaceId}`}>
            {project.data?.name ?? 'Project'}
          </Link>
        </p>
        <div className="mt-1 flex flex-wrap items-center gap-2">
          <h1 className="text-lg font-semibold">{r.title}</h1>
          <RunStatusBadge status={r.status} />
          {r.agent.simulated && <SimulatedBadge />}
          {r.incomplete && <Badge tone="warn">Incomplete</Badge>}
        </div>
        <p className="mt-1 text-xs text-muted">
          {modeLabel(r.mode)} · {r.agent.provider} / {r.agent.model} · {formatTokens(r.inputTokens)} in / {formatTokens(r.outputTokens)} out tokens
          {r.costUsd === undefined ? ' · cost not available' : ` · $${r.costUsd.toFixed(2)}`}
        </p>
      </div>

      {r.agent.simulated && (
        <Alert tone="sim" title="This run uses the built-in simulation">
          No AI model was called. Evidence is real, read from your repositories at the pinned commits; the wording of analysis is templated and marked [SIMULATED]. Nothing is executed or applied anywhere.
        </Alert>
      )}

      {r.incomplete && (
        <Alert tone="warn" title="This investigation stopped early">
          It hit a limit (time, tokens, tool calls or turns) before finishing. What it found so far is shown, and the assessment lists what is missing. Treat the result as partial.
        </Alert>
      )}

      <RunActionsBar run={r} events={live.events} />

      <Tabs
        label="Run sections"
        active={tab}
        onChange={(id) => setParams({ tab: id }, { replace: true })}
        tabs={[
          { id: 'overview', label: 'Overview' },
          { id: 'findings', label: 'Findings', count: findings.data?.length },
          { id: 'proposal', label: 'Fix proposal' },
          { id: 'changes', label: 'Changes' },
          { id: 'approvals', label: 'Approvals', count: pending || undefined },
          { id: 'timeline', label: 'Timeline', count: live.events.length || undefined },
        ]}
      >
        {tab === 'overview' && <OverviewTab run={r} />}
        {tab === 'findings' && <FindingsTab runId={r.id} />}
        {tab === 'proposal' && <ProposalTab runId={r.id} />}
        {tab === 'changes' && <ChangesTab runId={r.id} workspaceId={r.workspaceId} canPublish={r.nextActions.includes('publish')} exportable={(r.status === 'AWAITING_REVIEW' || r.status === 'COMPLETED') && !r.agent.simulated} />}
        {tab === 'approvals' && <ApprovalsTab run={r} canApprove={canApprove} />}
        {tab === 'timeline' && <TimelineTab events={live.events} stream={live.status} />}
      </Tabs>
    </div>
  )
}
