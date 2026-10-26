import { useState } from 'react'
import { Alert, Spinner } from '../../components/ui/Alert'
import { Badge } from '../../components/ui/Badge'
import { Button } from '../../components/ui/Button'
import { Card, CardHeader } from '../../components/ui/Card'
import { errorMessage } from '../../lib/api/client'
import { Link } from 'react-router'
import { ApiError } from '../../lib/api/client'
import { useApprovals, useDecide } from '../approvals/api'
import { hostName, parsePublishSummary } from './publishSummary'
import { downloadPatch, useExport, usePublications, useRequestPublish, useRetryPublish, type Change, type Publication } from './api'

/**
 * Takes a finished change out of DevForge without any Git host: a patch per repository to apply with `git am`, and a pull-request
 * description to paste. Nothing is pushed or applied for you.
 */
export function ExportPanel({ runId, workspaceId, changes, canPublish, canApprove }: { runId: string; workspaceId: string; changes: Change[]; canPublish: boolean; canApprove: boolean }) {
  const plan = useExport(runId, true)
  const [copied, setCopied] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)

  if (plan.isPending) return <Spinner label="Preparing the export" />
  if (plan.error) return <Alert tone="warn" title="This change cannot be exported">{errorMessage(plan.error)}</Alert>
  const p = plan.data

  async function copy() {
    try {
      await navigator.clipboard.writeText(p.prDescription)
      setCopied(true)
    } catch {
      setFailure('Copying was blocked by the browser; select the text and copy it.')
    }
  }

  return (
    <Card>
      <CardHeader title="Take this change with you" subtitle="No Git host is needed. Nothing is pushed or applied for you." />
      <div className="space-y-4 p-4 text-sm">
        {p.warnings.map((w) => (
          <Alert key={w} tone={p.blocked ? 'danger' : 'warn'}>{w}</Alert>
        ))}
        <p>
          Suggested branch <code className="rounded bg-surface-2 px-1.5 py-0.5 font-mono text-xs">{p.branchName}</code>
        </p>
        <ul className="space-y-3">
          {p.repositories.map((r) => {
            const base = changes.find((c) => c.id === r.changeId)?.baseSha ?? ''
            return (
              <li key={r.changeId} className="rounded-md bg-surface-2 p-3">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-medium">{r.repoName}</span>
                  {r.validationStatus && <Badge tone={r.validationStatus === 'PASSED' ? 'ok' : r.validationStatus === 'FAILED' ? 'danger' : 'warn'}>{r.validationStatus.toLowerCase().replace('_', ' ')}</Badge>}
                  <span className="text-xs text-muted">{r.files.length} file(s)</span>
                  <Button
                    className="ml-auto"
                    disabled={!r.downloadable}
                    onClick={() => void downloadPatch(runId, r.changeId, r.patchFileName).catch((e: unknown) => setFailure(errorMessage(e)))}
                  >
                    Download patch
                  </Button>
                </div>
                {r.downloadable && (
                  <pre className="mt-2 overflow-auto rounded bg-surface p-2 font-mono text-xs">{`cd <your ${r.repoName} checkout>\ngit switch -c ${p.branchName} ${base.slice(0, 12)}\ngit am ${r.patchFileName}`}</pre>
                )}
              </li>
            )
          })}
        </ul>
        {failure && <Alert tone="danger">{failure}</Alert>}
        <PublishSection runId={runId} workspaceId={workspaceId} canPublish={canPublish} canApprove={canApprove} blocked={p.blocked} />
        <details>
          <summary className="cursor-pointer font-medium">Pull request description</summary>
          <textarea readOnly aria-label="Pull request description" value={p.prDescription} rows={14} className="mt-2 w-full rounded-md border border-border bg-surface p-2 font-mono text-xs" />
          <Button className="mt-2" onClick={() => void copy()}>
            {copied ? 'Copied' : 'Copy description'}
          </Button>
        </details>
      </div>
    </Card>
  )
}

/**
 * Pushing a branch and opening a pull request on the Git host, with the run owner's own credential. The owner asks, the plan is shown here in full,
 * and a person with approval rights confirms exactly that plan (the server binds the confirmation to the plan's hash). Nothing is merged.
 */
function PublishSection({ runId, workspaceId, canPublish, canApprove, blocked }: { runId: string; workspaceId: string; canPublish: boolean; canApprove: boolean; blocked: boolean }) {
  const publications = usePublications(runId, true)
  const approvals = useApprovals(workspaceId, runId)
  const request = useRequestPublish(runId)
  const retry = useRetryPublish(runId)
  const decide = useDecide(runId)
  const list: Publication[] = publications.data ?? []
  const pending = (approvals.data ?? []).find((a) => a.kind === 'PR' && a.status === 'PENDING')
  const plan = pending ? parsePublishSummary(pending.summary) : null
  const err = request.error ?? retry.error
  const code = err instanceof ApiError ? err.code : ''

  return (
    <section aria-label="Create a pull request" className="space-y-3 rounded-md border border-border p-3">
      <h3 className="font-medium">Create a pull request</h3>
      {list.length > 0 ? (
        <ul className="space-y-2">
          {list.map((p) => (
            <li key={p.changeId} className="rounded-md bg-surface-2 p-3">
              <div className="flex flex-wrap items-center gap-2">
                <span className="font-medium">{p.repoName}</span>
                <span className="font-mono text-xs">{p.remote}</span>
                <Badge tone={p.status === 'PR_OPEN' ? 'ok' : p.status === 'FAILED' ? 'danger' : 'warn'}>{p.status === 'PR_OPEN' ? 'Pull request open' : p.status === 'PUSHED' ? 'Branch pushed' : p.status === 'FAILED' ? 'Failed' : 'Publishing'}</Badge>
                {p.prUrl && (
                  <a className="underline" href={p.prUrl} target="_blank" rel="noreferrer">
                    Pull request #{p.prNumber}
                  </a>
                )}
              </div>
              {p.failureMessage && <p className="mt-1 text-xs text-danger">{p.failureMessage}</p>}
            </li>
          ))}
        </ul>
      ) : pending && plan ? (
        <div className="space-y-3 rounded-md border border-warn bg-surface-2 p-3">
          <p>
            This will <strong>push a new branch</strong> <code className="font-mono text-xs">{plan.branch}</code> and open a <strong>{plan.draft ? 'draft ' : ''}pull request</strong>, using the run owner&apos;s own credential. Nothing is merged.
          </p>
          <p>
            <span className="text-muted">Title:</span> {plan.title}
          </p>
          <ul className="space-y-2">
            {plan.repositories.map((r) => (
              <li key={r.repository}>
                <strong>{r.repository}</strong> → {hostName(r.host)} <span className="font-mono text-xs">{r.remote}</span> (into <span className="font-mono text-xs">{r.base}</span>)
                <ul className="mt-1 list-disc pl-5 font-mono text-xs">{r.files.map((f) => <li key={f}>{f}</li>)}</ul>
              </li>
            ))}
          </ul>
          {decide.error && <Alert tone="danger" title="Could not record the decision">{errorMessage(decide.error)}</Alert>}
          {canApprove ? (
            <div className="flex gap-2">
              <Button variant="primary" busy={decide.isPending} onClick={() => decide.mutate({ approvalId: pending.id, decision: 'APPROVE', artifactHash: pending.artifactHash })}>
                Confirm: push and create the pull request
              </Button>
              <Button variant="danger" disabled={decide.isPending} onClick={() => decide.mutate({ approvalId: pending.id, decision: 'REJECT', artifactHash: pending.artifactHash })}>
                Cancel
              </Button>
            </div>
          ) : (
            <Alert tone="info">An approver or admin of this project has to confirm this plan. Nothing has been pushed.</Alert>
          )}
        </div>
      ) : canPublish ? (
        <p className="text-muted">
          You will see exactly what will be pushed, and confirm it, before anything leaves DevForge. It creates a new branch and a pull request with <em>your</em> credential. Nothing is merged.
        </p>
      ) : (
        <p className="text-muted">Only the person who started this run can create its pull request: it uses their own credential.</p>
      )}
      {err && (
        <Alert tone="warn" title="Could not create the pull request">
          {errorMessage(err)}{' '}
          {code === 'NO_USABLE_CONNECTION' && (
            <Link className="underline" to="/connections">
              Open My connections
            </Link>
          )}
        </Alert>
      )}
      {canPublish && list.length === 0 && !pending && (
        <Button variant="primary" busy={request.isPending} disabled={blocked} onClick={() => request.mutate()}>
          Create pull request…
        </Button>
      )}
      {canPublish && list.some((p) => p.status === 'FAILED') && (
        <Button busy={retry.isPending} onClick={() => retry.mutate()}>
          Try again
        </Button>
      )}
    </section>
  )
}
