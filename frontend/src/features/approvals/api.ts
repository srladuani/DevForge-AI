import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { client, unwrap, type Schemas } from '../../lib/api/client'
import { runKeys } from '../runs/api'

export type Approval = Schemas['ApprovalView']
export type Decision = Schemas['DecisionRequest']['decision']

export function useApprovals(workspaceId: string, runId: string) {
  return useQuery({
    queryKey: ['run', runId, 'approvals'],
    enabled: workspaceId !== '' && runId !== '',
    queryFn: () => unwrap(client.GET('/api/v1/workspaces/{workspaceId}/approvals', { params: { path: { workspaceId }, query: { runId } } })),
  })
}

/** The artifact hash is what the reviewer was shown; the server refuses the decision if it no longer matches. */
export function useDecide(runId: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (v: { approvalId: string; decision: Decision; artifactHash: string; comment?: string }) =>
      unwrap(client.POST('/api/v1/approvals/{id}/decision', { params: { path: { id: v.approvalId } }, body: { decision: v.decision, artifactHash: v.artifactHash, ...(v.comment ? { comment: v.comment } : {}) } })),
    onSettled: () => Promise.all([qc.invalidateQueries({ queryKey: runKeys.one(runId) }), qc.invalidateQueries({ queryKey: runKeys.part(runId, 'publications') })]),
  })
}
