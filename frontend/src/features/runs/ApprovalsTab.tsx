import { useState } from 'react'
import { Alert, Empty, Spinner } from '../../components/ui/Alert'
import { Badge, type Tone } from '../../components/ui/Badge'
import { Button } from '../../components/ui/Button'
import { Card, CardHeader } from '../../components/ui/Card'
import { Textarea } from '../../components/ui/Field'
import { ApiError, errorMessage } from '../../lib/api/client'
import { shortHash, timeAgo, titleCase } from '../../lib/format'
import { useApprovals, useDecide, type Approval } from '../approvals/api'
import type { Run } from './api'

const TONE: Record<string, Tone> = { PENDING: 'warn', APPROVED: 'ok', REJECTED: 'danger', SUPERSEDED: 'neutral', EXPIRED: 'neutral' }

function parseSummary(s: string): { filesToChange: string[]; commands: string[]; risks: string[]; simulated: boolean } {
  const out = { filesToChange: [] as string[], commands: [] as string[], risks: [] as string[], simulated: false }
  try {
    const v: unknown = JSON.parse(s)
    if (typeof v === 'object' && v !== null) {
      const o = v as Record<string, unknown>
      const arr = (x: unknown) => (Array.isArray(x) ? x.filter((y): y is string => typeof y === 'string') : [])
      out.filesToChange = arr(o.filesToChange)
      out.commands = arr(o.commands)
      out.risks = arr(o.risks)
      out.simulated = o.simulated === true
    }
  } catch {
    // an unreadable summary is shown as empty; the hash still identifies what is approved
  }
  return out
}

export function ApprovalsTab({ run, canApprove }: { run: Run; canApprove: boolean }) {
  const { data, isPending, error } = useApprovals(run.workspaceId, run.id)
  if (isPending) return <Spinner label="Loading approvals" />
  if (error) return <Alert tone="danger" title="Could not load approvals">{errorMessage(error)}</Alert>
  if (data.length === 0) return <Empty title="Nothing to approve">Approvals are requested before any change is prepared.</Empty>
  const sorted = [...data].sort((a, b) => b.createdAt.localeCompare(a.createdAt))
  return (
    <div className="space-y-4">
      {sorted.map((a) => (a.status === 'PENDING' ? <PendingApproval key={a.id} approval={a} run={run} canApprove={canApprove} /> : <PastApproval key={a.id} approval={a} />))}
    </div>
  )
}

function PendingApproval({ approval, run, canApprove }: { approval: Approval; run: Run; canApprove: boolean }) {
  const decide = useDecide(run.id)
  const [comment, setComment] = useState('')
  const s = parseSummary(approval.summary)
  const err = decide.error
  const stale = err instanceof ApiError && err.code === 'ARTIFACT_CHANGED'

  return (
    <Card className="border-warn">
      <CardHeader title="Your decision is needed" subtitle={`Expires ${new Date(approval.expiresAt).toLocaleString()}`} actions={<Badge tone="warn">Pending</Badge>} />
      <div className="space-y-4 p-4 text-sm">
        <p>
          Approving allows this run to <strong>prepare patches</strong> for the files below. Nothing is applied, committed or pushed. This approval is bound to proposal{' '}
          <span className="font-mono">{shortHash(approval.artifactHash)}</span> and stops counting if the proposal changes.
        </p>
        <div>
          <h3 className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted">Files that would change</h3>
          <ul className="list-disc pl-5 font-mono text-xs">{s.filesToChange.length ? s.filesToChange.map((f) => <li key={f}>{f}</li>) : <li>none listed</li>}</ul>
        </div>
        {s.commands.length > 0 && (
          <div>
            <h3 className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted">Commands that would be used to check it</h3>
            <ul className="list-disc pl-5 font-mono text-xs">{s.commands.map((c) => <li key={c}>{c}</li>)}</ul>
          </div>
        )}
        {s.risks.length > 0 && (
          <div>
            <h3 className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted">Risks</h3>
            <ul className="list-disc pl-5">{s.risks.map((r) => <li key={r}>{r}</li>)}</ul>
          </div>
        )}
        {!canApprove && <Alert tone="info">Your role in this project cannot approve changes. An approver or admin must decide.</Alert>}
        {err && (
          <Alert tone="danger" title={stale ? 'The proposal changed' : 'Could not record the decision'}>
            {errorMessage(err)}
          </Alert>
        )}
        {canApprove && (
          <>
            <Textarea label="Comment (optional)" value={comment} onChange={(e) => setComment(e.target.value)} rows={2} maxLength={2000} />
            <div className="flex gap-2">
              <Button variant="primary" busy={decide.isPending} onClick={() => decide.mutate({ approvalId: approval.id, decision: 'APPROVE', artifactHash: approval.artifactHash, ...(comment ? { comment } : {}) })}>
                Approve
              </Button>
              <Button variant="danger" disabled={decide.isPending} onClick={() => decide.mutate({ approvalId: approval.id, decision: 'REJECT', artifactHash: approval.artifactHash, ...(comment ? { comment } : {}) })}>
                Reject
              </Button>
            </div>
          </>
        )}
      </div>
    </Card>
  )
}

function PastApproval({ approval }: { approval: Approval }) {
  return (
    <Card>
      <div className="flex flex-wrap items-center justify-between gap-2 px-4 py-3 text-sm">
        <span>
          <strong>{titleCase(approval.kind)}</strong> · <span className="font-mono text-xs">{shortHash(approval.artifactHash)}</span>
        </span>
        <span className="flex items-center gap-2">
          <span className="text-xs text-muted">{approval.decidedAt ? timeAgo(approval.decidedAt) : timeAgo(approval.createdAt)}</span>
          <Badge tone={TONE[approval.status] ?? 'neutral'}>{titleCase(approval.status)}</Badge>
        </span>
      </div>
      {approval.comment && <p className="border-t border-border px-4 py-2 text-xs text-muted">“{approval.comment}”</p>}
    </Card>
  )
}
