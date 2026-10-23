import { Alert, Empty, Spinner } from '../../components/ui/Alert'
import { Badge, type Tone } from '../../components/ui/Badge'
import { Card, CardHeader } from '../../components/ui/Card'
import { errorMessage } from '../../lib/api/client'
import { shortHash } from '../../lib/format'
import { useChanges, useValidations, type Change, type Validation } from './api'
import { DiffView } from './DiffView'
import { ExportPanel } from './ExportPanel'
import { SimulatedBadge } from './RunBadges'

const VALIDATION: Record<string, { label: string; tone: Tone }> = {
  PASSED: { label: 'Passed', tone: 'ok' },
  FAILED: { label: 'Failed', tone: 'danger' },
  NOT_RUN: { label: 'Not run', tone: 'warn' },
  SIMULATED: { label: 'Not executed (simulated)', tone: 'sim' },
}

export function ChangesTab({ runId, exportable }: { runId: string; exportable: boolean }) {
  const changes = useChanges(runId)
  const validations = useValidations(runId)
  if (changes.isPending || validations.isPending) return <Spinner label="Loading changes" />
  if (changes.error) return <Alert tone="danger" title="Could not load changes">{errorMessage(changes.error)}</Alert>
  if (changes.data.length === 0) return <Empty title="No changes prepared">Patches appear here after an approved fix has been prepared. Nothing is applied to your repositories.</Empty>

  const attempts = [...new Set(changes.data.map((c) => c.attempt))].sort((a, b) => b - a)
  const vals = validations.data ?? []
  return (
    <div className="space-y-6">
      <Alert tone="info">
        These are patches for review. Nothing has been applied to any repository on disk, committed, or pushed.
      </Alert>
      {exportable && <ExportPanel runId={runId} changes={changes.data.filter((c) => c.attempt === attempts[0])} />}
      {attempts.map((attempt, idx) => (
        <section key={attempt} aria-label={`Attempt ${attempt}`}>
          <details open={idx === 0} className="space-y-3">
            <summary className="cursor-pointer text-sm font-semibold">
              Attempt {attempt}
              {idx === 0 ? ' (latest)' : ''}
            </summary>
            <div className="mt-3 space-y-4">
              {changes.data
                .filter((c) => c.attempt === attempt)
                .map((c) => (
                  <ChangeCard key={c.id} change={c} validations={vals.filter((v) => v.codeChangeId === c.id)} />
                ))}
            </div>
          </details>
        </section>
      ))}
    </div>
  )
}

function ChangeCard({ change, validations }: { change: Change; validations: Validation[] }) {
  return (
    <Card>
      <CardHeader
        title={change.repoName}
        subtitle={
          <>
            {change.files.join(', ')} · patch <span className="font-mono">{shortHash(change.patchHash)}</span>
          </>
        }
        actions={change.simulated ? <SimulatedBadge /> : undefined}
      />
      <div className="space-y-3 p-4">
        <DiffView patch={change.patch} />
        <div>
          <h3 className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted">Checks</h3>
          {validations.length === 0 && <p className="text-sm text-muted">No checks recorded.</p>}
          <ul className="space-y-2">
            {validations.map((v) => {
              const s = VALIDATION[v.status] ?? { label: v.status, tone: 'neutral' as Tone }
              return (
                <li key={v.id} className="rounded-md bg-surface-2 p-3 text-sm">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={s.tone}>{s.label}</Badge>
                    {v.command && <code className="font-mono text-xs">{v.command}</code>}
                  </div>
                  <ValidationSummary text={v.summary} />
                </li>
              )
            })}
          </ul>
        </div>
      </div>
    </Card>
  )
}

const OUTPUT_MARK = '\n--- output (end) ---\n'

/** The one-line result first; what the build printed is untrusted program output and stays collapsed. */
function ValidationSummary({ text }: { text: string }) {
  const at = text.indexOf(OUTPUT_MARK)
  const head = at < 0 ? text : text.slice(0, at)
  const output = at < 0 ? '' : text.slice(at + OUTPUT_MARK.length)
  return (
    <>
      <p className="mt-1 text-xs text-muted">{head}</p>
      {output !== '' && (
        <details className="mt-1 text-xs">
          <summary className="cursor-pointer underline">Show the end of the output</summary>
          <pre className="mt-1 max-h-64 overflow-auto whitespace-pre-wrap rounded bg-surface p-2 font-mono">{output}</pre>
        </details>
      )}
    </>
  )
}
