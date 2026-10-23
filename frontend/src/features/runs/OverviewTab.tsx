import { Alert, Spinner } from '../../components/ui/Alert'
import { Badge } from '../../components/ui/Badge'
import { Card, CardHeader } from '../../components/ui/Card'
import { shortSha } from '../../lib/format'
import { roleLabel } from '../projects/api'
import { useArtifacts, type Run } from './api'
import { FindingLabelBadge } from './RunBadges'

function strings(v: unknown): string[] {
  return Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : []
}

function records(v: unknown): Array<Record<string, unknown>> {
  return Array.isArray(v) ? v.filter((x): x is Record<string, unknown> => typeof x === 'object' && x !== null && !Array.isArray(x)) : []
}

export function OverviewTab({ run }: { run: Run }) {
  const artifacts = useArtifacts(run.id)
  const root = artifacts.data?.find((a) => a.type === 'ROOT_CAUSE' && a.current)
  const input = run.inputs[0]
  const norm = input?.normalized
  const flags = strings(norm?.injectionFlags)
  const keywords = strings(norm?.keywords)
  const gaps = records(norm?.gaps)

  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <div className="space-y-4">
        <Card>
          <CardHeader title="What was asked" subtitle={input?.sourceRef ? `Ticket ${input.sourceRef} (text pasted; not fetched from a tracker)` : 'Description'} />
          <div className="space-y-3 p-4 text-sm">
            {run.inputs.map((i, idx) => (
              <div key={i.id}>
                {idx > 0 && <p className="mb-1 text-xs font-medium text-muted">{i.kind === 'CLARIFICATION' ? 'Your answer' : i.kind === 'REVIEW_FEEDBACK' ? 'Reviewer feedback' : i.kind}</p>}
                <p className="whitespace-pre-wrap">{i.text}</p>
              </div>
            ))}
            {flags.length > 0 && (
              <Alert tone="warn" title="This text contains wording that tries to direct an assistant">
                It was treated as plain data and nothing in it was acted on.
                <ul className="mt-1 list-disc pl-5 text-xs">
                  {flags.map((f) => (
                    <li key={f}>{f}</li>
                  ))}
                </ul>
              </Alert>
            )}
            {gaps.some((g) => g.blocking === false) && (
              <p className="text-xs text-muted">Missing detail: {gaps.filter((g) => g.blocking === false).map((g) => String(g.text)).join(' ')}</p>
            )}
            {keywords.length > 0 && (
              <p className="text-xs text-muted">
                Search terms used: <span className="font-mono">{keywords.join(', ')}</span>
              </p>
            )}
          </div>
        </Card>
        <Card>
          <CardHeader title="Repositories examined" subtitle="Each is pinned to the commit it had when the run started." />
          <ul className="divide-y divide-border text-sm">
            {run.repositories.map((r) => (
              <li key={r.repositoryId} className="flex items-center justify-between gap-2 px-4 py-2">
                <span className="flex items-center gap-2">
                  <span className="font-medium">{r.name}</span>
                  <Badge>{roleLabel(r.role)}</Badge>
                </span>
                <span className="font-mono text-xs text-muted">{shortSha(r.baseSha)}</span>
              </li>
            ))}
          </ul>
        </Card>
      </div>

      <Card>
        <CardHeader title="Assessment" subtitle="Leads and gaps, in the same labels used for findings." />
        <div className="space-y-4 p-4 text-sm">
          {artifacts.isPending && <Spinner label="Loading" />}
          {!artifacts.isPending && !root && <p className="text-muted">The assessment appears when the investigation finishes.</p>}
          {root && (
            <>
              <p>{String(root.content.summary ?? '')}</p>
              <div>
                <h3 className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted">Hypotheses</h3>
                {records(root.content.hypotheses).length === 0 && <p className="text-muted">None formed.</p>}
                <ul className="space-y-2">
                  {records(root.content.hypotheses).map((h, i) => (
                    <li key={i} className="rounded-md bg-surface-2 p-3">
                      <div className="mb-1 flex items-center gap-2">
                        <FindingLabelBadge label="HYPOTHESIS" />
                        <span className="text-xs text-muted">confidence: {String(h.confidence ?? '').toLowerCase()}</span>
                      </div>
                      <p>{String(h.text ?? '')}</p>
                      {strings(h.supporting).length > 0 && <p className="mt-1 text-xs text-muted">For: {strings(h.supporting).join('; ')}</p>}
                      {strings(h.contradicting).length > 0 && <p className="text-xs text-muted">Against: {strings(h.contradicting).join('; ')}</p>}
                    </li>
                  ))}
                </ul>
              </div>
              <div>
                <h3 className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted">What is not known</h3>
                <ul className="list-disc space-y-1 pl-5">
                  {strings(root.content.unknowns).map((u) => (
                    <li key={u}>{u}</li>
                  ))}
                </ul>
              </div>
            </>
          )}
        </div>
      </Card>
    </div>
  )
}
