import { useState } from 'react'
import { Alert, Empty, Spinner } from '../../components/ui/Alert'
import { findingLabel } from '../../lib/status'
import { errorMessage } from '../../lib/api/client'
import { useFindings, type Evidence } from './api'
import { EvidenceDrawer, evidenceLabel } from './EvidenceDrawer'
import { FindingLabelBadge } from './RunBadges'

const ORDER = ['VERIFIED', 'SUPPORTED', 'HYPOTHESIS', 'UNKNOWN']

export function FindingsTab({ runId }: { runId: string }) {
  const { data, isPending, error } = useFindings(runId)
  const [open, setOpen] = useState<Evidence | null>(null)

  if (isPending) return <Spinner label="Loading findings" />
  if (error) return <Alert tone="danger" title="Could not load findings">{errorMessage(error)}</Alert>
  if (data.length === 0) return <Empty title="No findings yet">Findings appear here as the investigation produces them.</Empty>

  const sorted = [...data].sort((a, b) => ORDER.indexOf(a.label) - ORDER.indexOf(b.label))
  return (
    <div className="space-y-3">
      <p className="text-xs text-muted">
        Every claim is labelled. <strong>Verified</strong> means the server re-read the cited code from the pinned commit; anything it could not confirm is downgraded and says why.
      </p>
      <ul className="space-y-3">
        {sorted.map((f) => (
          <li key={f.id} className="rounded-lg border border-border bg-surface p-4">
            <div className="flex flex-wrap items-center gap-2">
              <FindingLabelBadge label={f.label} />
              <span className="text-xs text-muted">confidence: {f.confidence.toLowerCase()}</span>
            </div>
            <p className="mt-2 text-sm">{f.text}</p>
            {f.downgradeReason && (
              <p className="mt-2 text-xs text-warn">
                Claimed as {findingLabel(f.requestedLabel).label.toLowerCase()}, downgraded: {f.downgradeReason}.
              </p>
            )}
            {f.evidence.length > 0 && (
              <ul className="mt-3 flex flex-wrap gap-2">
                {f.evidence.map((e) => (
                  <li key={e.id}>
                    <button
                      type="button"
                      onClick={() => setOpen(e)}
                      className="max-w-full truncate rounded-md border border-border bg-surface-2 px-2 py-1 text-left font-mono text-xs hover:border-accent"
                      title={e.verified ? 'Confirmed in the repository' : (e.problem ?? 'Not confirmed')}
                    >
                      <span aria-hidden>{e.verified ? '✓ ' : '! '}</span>
                      <span className="sr-only">{e.verified ? 'Confirmed:' : 'Not confirmed:'}</span>{' '}
                      {evidenceLabel(e)}
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </li>
        ))}
      </ul>
      <EvidenceDrawer evidence={open} onClose={() => setOpen(null)} />
    </div>
  )
}
