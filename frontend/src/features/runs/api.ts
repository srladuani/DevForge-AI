import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { client, unwrap, type Schemas } from '../../lib/api/client'
import { currentUserEmail } from '../../lib/session'
import { followEvents, type RunEvent } from '../../lib/sse'

export type Run = Schemas['RunView']
export type Finding = Schemas['FindingView']
export type Evidence = Schemas['EvidenceView']
export type Artifact = Schemas['ArtifactView']
export type Change = Schemas['ChangeView']
export type Validation = Schemas['ValidationView']
export type Step = Schemas['StepView']
export type RunMode = Run['mode']
export type CreateRun = Schemas['CreateRunRequest']

export const runKeys = {
  list: (ws: string, status?: string) => ['runs', ws, status ?? 'all'] as const,
  one: (id: string) => ['run', id] as const,
  part: (id: string, part: string) => ['run', id, part] as const,
}

export function useRuns(workspaceId: string, status?: Run['status']) {
  return useQuery({
    queryKey: runKeys.list(workspaceId, status),
    queryFn: () => unwrap(client.GET('/api/v1/workspaces/{workspaceId}/runs', { params: { path: { workspaceId }, query: status ? { status } : {} } })),
    refetchInterval: 5000,
  })
}

export function useRun(id: string) {
  return useQuery({ queryKey: runKeys.one(id), queryFn: () => unwrap(client.GET('/api/v1/runs/{id}', { params: { path: { id } } })) })
}

export function useSteps(id: string) {
  return useQuery({ queryKey: runKeys.part(id, 'steps'), queryFn: () => unwrap(client.GET('/api/v1/runs/{id}/steps', { params: { path: { id } } })) })
}

export function useFindings(id: string) {
  return useQuery({ queryKey: runKeys.part(id, 'findings'), queryFn: () => unwrap(client.GET('/api/v1/runs/{id}/findings', { params: { path: { id } } })) })
}

export function useArtifacts(id: string) {
  return useQuery({ queryKey: runKeys.part(id, 'artifacts'), queryFn: () => unwrap(client.GET('/api/v1/runs/{id}/artifacts', { params: { path: { id } } })) })
}

export function useChanges(id: string) {
  return useQuery({ queryKey: runKeys.part(id, 'changes'), queryFn: () => unwrap(client.GET('/api/v1/runs/{id}/changes', { params: { path: { id } } })) })
}

export function useValidations(id: string) {
  return useQuery({ queryKey: runKeys.part(id, 'validations'), queryFn: () => unwrap(client.GET('/api/v1/runs/{id}/validations', { params: { path: { id } } })) })
}

export function useCreateRun(workspaceId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (v: { body: CreateRun; idempotencyKey: string }) =>
      unwrap(client.POST('/api/v1/workspaces/{workspaceId}/runs', { params: { path: { workspaceId }, header: { 'Idempotency-Key': v.idempotencyKey } }, body: v.body })),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runs', workspaceId] }),
  })
}

export type RunAction = 'cancel' | 'resume' | 'answer' | 'request-fix' | 'complete' | 'request-implementation' | 'accept' | 'request-changes'

export function useRunAction(id: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (v: { action: RunAction; body?: Schemas['ActionBody'] }) =>
      unwrap(client.POST('/api/v1/runs/{id}/actions/{action}', { params: { path: { id, action: v.action } }, ...(v.body ? { body: v.body } : {}) })),
    onSuccess: () => qc.invalidateQueries({ queryKey: runKeys.one(id) }),
  })
}

export type StreamStatus = 'connecting' | 'live' | 'reconnecting' | 'closed'

/** Events that change what the screen shows; each refreshes the run's queries (state is always read from the server, never inferred). */
const REFRESHING = new Set(['STATE_CHANGED', 'FINDING_ADDED', 'ARTIFACT_CREATED', 'CHANGE_PREPARED', 'VALIDATION_RESULT', 'APPROVAL_REQUESTED', 'APPROVAL_DECIDED', 'USAGE', 'CANCEL_REQUESTED', 'NEEDS_INPUT'])

/** Follows a run's live event stream and keeps the cached run data fresh. Reconnects and resumes without losing or repeating events. */
export function useRunEvents(runId: string): { events: RunEvent[]; status: StreamStatus } {
  const qc = useQueryClient()
  // State is tagged with the run it belongs to, so switching runs never shows the previous run's events.
  const [state, setState] = useState<{ runId: string; events: RunEvent[]; status: StreamStatus }>({ runId, events: [], status: 'connecting' })
  const pending = useRef(false)

  useEffect(() => {
    const ctl = new AbortController()
    const refresh = () => {
      if (pending.current) return
      pending.current = true
      queueMicrotask(() => {
        pending.current = false
        void qc.invalidateQueries({ queryKey: runKeys.one(runId) })
      })
    }
    void followEvents(
      {
        url: (after) => new URL(`/api/v1/runs/${runId}/events?after=${after}`, window.location.origin).toString(),
        headers: () => {
          const h: Record<string, string> = {}
          const email = currentUserEmail()
          if (email) h['X-Devforge-User'] = email
          return h
        },
        onEvent: (e) => {
          setState((prev) => {
            const base = prev.runId === runId ? prev : { runId, events: [], status: prev.status }
            return base.events.some((p) => p.seq === e.seq) ? base : { ...base, events: [...base.events, e] }
          })
          if (REFRESHING.has(e.type)) refresh()
        },
        onStatus: (status) => setState((prev) => (prev.runId === runId ? { ...prev, status } : { runId, events: [], status })),
      },
      ctl.signal,
    )
    return () => ctl.abort()
  }, [runId, qc])

  return state.runId === runId ? { events: state.events, status: state.status } : { events: [], status: 'connecting' }
}

export type TicketPreview = Schemas['Ticket']

/** Reads a ticket with the caller's own tracker connection so they can see what the run will work from. Changes nothing. */
export function usePreviewTicket(workspaceId: string) {
  return useMutation({
    mutationFn: (key: string) => unwrap(client.GET('/api/v1/workspaces/{workspaceId}/tickets/{key}', { params: { path: { workspaceId, key } } })),
  })
}
