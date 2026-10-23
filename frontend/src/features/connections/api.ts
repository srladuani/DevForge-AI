import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { client, unwrap, unwrapEmpty, type Schemas } from '../../lib/api/client'

export type Connection = Schemas['ConnectionView']
export type AdapterType = Schemas['AdapterType']
export type ProviderOption = Schemas['ProviderOption']
export type ModelInfo = Schemas['ModelInfo']

export const connectionKeys = {
  all: ['connections'] as const,
  adapters: ['adapter-types'] as const,
  providers: (workspaceId: string | undefined) => ['agent-providers', workspaceId ?? 'personal'] as const,
}

export function useConnections() {
  return useQuery({ queryKey: connectionKeys.all, queryFn: () => unwrap(client.GET('/api/v1/me/connections')) })
}

export function useAdapterTypes() {
  return useQuery({ queryKey: connectionKeys.adapters, queryFn: () => unwrap(client.GET('/api/v1/adapter-types')) })
}

export function useAgentProviders(workspaceId?: string) {
  return useQuery({
    queryKey: connectionKeys.providers(workspaceId),
    queryFn: () => unwrap(client.GET('/api/v1/agent-providers', { params: { query: workspaceId ? { workspaceId } : {} } })),
  })
}

function invalidateAll(qc: ReturnType<typeof useQueryClient>) {
  return Promise.all([qc.invalidateQueries({ queryKey: connectionKeys.all }), qc.invalidateQueries({ queryKey: ['agent-providers'] })])
}

export function useCreateConnection() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: Schemas['CreateConnectionRequest']) => unwrap(client.POST('/api/v1/me/connections', { body })),
    onSuccess: () => invalidateAll(qc),
  })
}

export function useVerifyConnection() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => unwrap(client.POST('/api/v1/me/connections/{id}/verify', { params: { path: { id } } })),
    onSuccess: () => invalidateAll(qc),
  })
}

export function useDeleteConnection() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => unwrapEmpty(client.DELETE('/api/v1/me/connections/{id}', { params: { path: { id } } })),
    onSuccess: () => invalidateAll(qc),
  })
}
