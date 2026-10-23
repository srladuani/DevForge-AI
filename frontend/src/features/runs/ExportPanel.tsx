import { useState } from 'react'
import { Alert, Spinner } from '../../components/ui/Alert'
import { Badge } from '../../components/ui/Badge'
import { Button } from '../../components/ui/Button'
import { Card, CardHeader } from '../../components/ui/Card'
import { errorMessage } from '../../lib/api/client'
import { Link } from 'react-router'
import { ApiError } from '../../lib/api/client'
import { useApprovals } from '../approvals/api'
import { downloadPatch, useExport, usePublications, useRequestPublish, useRetryPublish, type Change, type Publication } from './api'

/**
 * Takes a finished change out of DevForge without any Git host: a patch per repository to apply with `git am`, and a pull-request
 * description to paste. Nothing is pushed or applied for you.
 */
export function ExportPanel({ runId, workspaceId, changes, canPublish }: { runId: string; workspaceId: string; changes: Change[]; canPublish: boolean }) {
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
        <PublishSection runId={runId} workspaceId={workspaceId} canPublish={canPublish} blocked={p.blocked} />
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

/** Pushing a branch and opening a draft pull request on GitHub, with the run owner's own token, after someone approves exactly that plan. */
function PublishSection({ runId, workspaceId, canPublish, blocked }: { runId: string; workspaceId: string; canPublish: boolean; blocked: boolean }) {
  const publications = usePublications(runId, true)
  const approvals = useApprovals(workspaceId, runId)
  const request = useRequestPublish(runId)
  const retry = useRetryPublish(runId)
  const list: Publication[] = publications.data ?? []
  const waiting = (approvals.data ?? []).some((a) => a.kind === 'PR' && a.status === 'PENDING')
  const err = request.error ?? retry.error
  const code = err instanceof ApiError ? err.code : ''

  return (
    <section aria-label="Publish to GitHub" className="space-y-3 rounded-md border border-border p-3">
      <h3 className="font-medium">Publish to GitHub</h3>
      {list.length > 0 ? (
        <ul className="space-y-2">
          {list.map((p) => (
            <li key={p.changeId} className="rounded-md bg-surface-2 p-3">
              <div className="flex flex-wrap items-center gap-2">
                <span className="font-medium">{p.repoName}</span>
                <span className="font-mono text-xs">{p.remote}</span>
                <Badge tone={p.status === 'PR_OPEN' ? 'ok' : p.status === 'FAILED' ? 'danger' : 'warn'}>{p.status === 'PR_OPEN' ? 'Draft pull request open' : p.status === 'PUSHED' ? 'Branch pushed' : p.status === 'FAILED' ? 'Failed' : 'Publishing'}</Badge>
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
      ) : waiting ? (
        <Alert tone="info">Waiting for an approver to approve the publication plan. They can see the branch, repositories and files on the Approvals tab. Nothing has been pushed.</Alert>
      ) : canPublish ? (
        <p className="text-muted">
          A person with approval rights must approve exactly what will be pushed: a new branch and a <strong>draft</strong> pull request, created with <em>your</em> GitHub token. Nothing is merged.
        </p>
      ) : (
        <p className="text-muted">Only the person who started this run can publish it: it uses their own GitHub token.</p>
      )}
      {err && (
        <Alert tone="warn" title="Could not publish">
          {errorMessage(err)}{' '}
          {code === 'NO_USABLE_CONNECTION' && (
            <Link className="underline" to="/connections">
              Open My connections
            </Link>
          )}
        </Alert>
      )}
      {canPublish && list.length === 0 && !waiting && (
        <Button variant="primary" busy={request.isPending} disabled={blocked} onClick={() => request.mutate()}>
          Ask to publish
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
