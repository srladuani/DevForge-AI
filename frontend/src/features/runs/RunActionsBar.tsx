import { useState } from 'react'
import { Link } from 'react-router'
import { Alert } from '../../components/ui/Alert'
import { Button } from '../../components/ui/Button'
import { Dialog } from '../../components/ui/Dialog'
import { Select, Textarea } from '../../components/ui/Field'
import { ApiError, errorMessage } from '../../lib/api/client'
import type { RunEvent } from '../../lib/sse'
import { useRunAction, type Run, type RunAction, type RunMode } from './api'

function questionsFrom(events: RunEvent[]): string[] {
  const last = [...events].reverse().find((e) => e.type === 'NEEDS_INPUT' && e.payload.reason === 'CLARIFICATION')
  const q = last?.payload.questions
  return Array.isArray(q) ? q.filter((x): x is string => typeof x === 'string') : []
}

/** Shows exactly the actions the server says this person can take right now. The server re-checks every one. */
export function RunActionsBar({ run, events }: { run: Run; events: RunEvent[] }) {
  const act = useRunAction(run.id)
  const [fixMode, setFixMode] = useState<RunMode>('PROPOSE_FIX')
  const [answer, setAnswer] = useState('')
  const [feedback, setFeedback] = useState('')
  const [confirmCancel, setConfirmCancel] = useState(false)
  const can = (a: RunAction) => run.nextActions.includes(a)

  function go(action: RunAction, body?: { text?: string; mode?: RunMode }) {
    act.mutate(body ? { action, body } : { action })
  }

  const err = act.error
  const needsKey = err instanceof ApiError && err.code === 'NO_USABLE_CONNECTION'
  const questions = run.status === 'AWAITING_INPUT' && run.awaitReason === 'CLARIFICATION' ? questionsFrom(events) : []

  return (
    <div className="space-y-3">
      {run.status === 'AWAITING_INPUT' && run.awaitReason === 'CLARIFICATION' && (
        <Alert tone="warn" title="More information is needed to continue">
          {questions.length > 0 && (
            <ul className="mt-1 list-disc pl-5">
              {questions.map((q) => (
                <li key={q}>{q}</li>
              ))}
            </ul>
          )}
          {can('answer') ? (
            <form
              className="mt-3 space-y-2"
              onSubmit={(e) => {
                e.preventDefault()
                go('answer', { text: answer })
                setAnswer('')
              }}
            >
              <Textarea label="Your answer" value={answer} onChange={(e) => setAnswer(e.target.value)} rows={3} required />
              <Button type="submit" variant="primary" busy={act.isPending} disabled={answer.trim() === ''}>
                Send answer
              </Button>
            </form>
          ) : (
            <p className="mt-2 text-xs">You can view this run, but cannot answer.</p>
          )}
        </Alert>
      )}

      {run.status === 'AWAITING_INPUT' && run.awaitReason === 'CREDENTIAL' && (
        <Alert tone="warn" title="Your AI credential needs attention">
          The provider rejected the key or reports the quota is used up. Fix it under{' '}
          <Link className="underline" to="/connections">
            My connections
          </Link>
          {', then resume. The run never switches to a different key by itself.'}
        </Alert>
      )}

      {run.status === 'FAILED' && run.failure && (
        <Alert tone="danger" title={`The run failed in ${run.failure.step.toLowerCase().replace(/_/g, ' ')}`}>
          {run.failure.message} <span className="font-mono text-xs">({run.failure.code})</span> {run.failure.retryable ? 'This is retryable.' : 'This cannot be retried; start a new run.'}
        </Alert>
      )}

      {run.cancelRequested && run.status !== 'CANCELLED' && <Alert tone="info">Cancellation requested; stopping at the next safe point.</Alert>}

      <div className="flex flex-wrap items-end gap-2">
        {can('request-fix') && (
          <div className="flex items-end gap-2">
            <div className="w-64">
              <Select label="Next step" value={fixMode} onChange={(e) => setFixMode(e.target.value === 'FIX_AND_PATCH' ? 'FIX_AND_PATCH' : 'PROPOSE_FIX')}>
                <option value="PROPOSE_FIX">Propose a fix for review</option>
                <option value="FIX_AND_PATCH">Propose, then prepare a patch after approval</option>
              </Select>
            </div>
            <Button variant="primary" busy={act.isPending} onClick={() => go('request-fix', { mode: fixMode })}>
              Request fix
            </Button>
          </div>
        )}
        {can('request-implementation') && (
          <Button variant="primary" busy={act.isPending} onClick={() => go('request-implementation')}>
            Request approval to prepare patches
          </Button>
        )}
        {can('accept') && (
          <Button variant="primary" busy={act.isPending} onClick={() => go('accept')}>
            Accept changes
          </Button>
        )}
        {can('resume') && (
          <Button variant="primary" busy={act.isPending} onClick={() => go('resume')}>
            Resume
          </Button>
        )}
        {can('complete') && (
          <Button busy={act.isPending} onClick={() => go('complete')}>
            Finish without changes
          </Button>
        )}
        {can('cancel') && (
          <Button variant="ghost" onClick={() => setConfirmCancel(true)}>
            Cancel run
          </Button>
        )}
      </div>

      {can('request-changes') && (
        <form
          className="max-w-xl space-y-2"
          onSubmit={(e) => {
            e.preventDefault()
            go('request-changes', { text: feedback })
            setFeedback('')
          }}
        >
          <Textarea label="Ask for changes" value={feedback} onChange={(e) => setFeedback(e.target.value)} rows={2} hint="Prepares a new patch attempt taking your feedback into account." />
          <Button type="submit" busy={act.isPending} disabled={feedback.trim() === ''}>
            Request changes
          </Button>
        </form>
      )}

      {err && (
        <Alert tone="danger" title="That did not work">
          {errorMessage(err)}
          {needsKey && (
            <>
              {' '}
              <Link className="underline" to="/connections">
                Open My connections
              </Link>
            </>
          )}
        </Alert>
      )}

      <Dialog open={confirmCancel} onClose={() => setConfirmCancel(false)} title="Cancel this run?">
        <p className="text-sm">The run stops and cannot be restarted. Findings gathered so far stay visible. Any pending approval is voided.</p>
        <div className="mt-4 flex justify-end gap-2">
          <Button onClick={() => setConfirmCancel(false)}>Keep running</Button>
          <Button
            variant="danger"
            busy={act.isPending}
            onClick={() => {
              go('cancel')
              setConfirmCancel(false)
            }}
          >
            Cancel run
          </Button>
        </div>
      </Dialog>
    </div>
  )
}
