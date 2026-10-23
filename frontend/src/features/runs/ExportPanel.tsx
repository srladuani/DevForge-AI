import { useState } from 'react'
import { Alert, Spinner } from '../../components/ui/Alert'
import { Badge } from '../../components/ui/Badge'
import { Button } from '../../components/ui/Button'
import { Card, CardHeader } from '../../components/ui/Card'
import { errorMessage } from '../../lib/api/client'
import { downloadPatch, useExport, type Change } from './api'

/**
 * Takes a finished change out of DevForge without any Git host: a patch per repository to apply with `git am`, and a pull-request
 * description to paste. Nothing is pushed or applied for you.
 */
export function ExportPanel({ runId, changes }: { runId: string; changes: Change[] }) {
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
