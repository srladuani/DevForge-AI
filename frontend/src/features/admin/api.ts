import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { client, unwrap, type Schemas } from '../../lib/api/client'
import { connectionKeys } from '../connections/api'

export type ProviderInstance = Schemas['ProviderInstanceView']
export type ModelPolicy = Schemas['ModelPolicy']
export type AuditEvent = Schemas['AuditEventView']

const KEY = ['admin', 'providers'] as const

export function useProviderInstances() {
  return useQuery({ queryKey: KEY, queryFn: () => unwrap(client.GET('/api/v1/admin/provider-instances', { params: { query: {} } })), retry: false })
}

function useInvalidate() {
  const qc = useQueryClient()
  return () => Promise.all([qc.invalidateQueries({ queryKey: KEY }), qc.invalidateQueries({ queryKey: ['agent-providers'] }), qc.invalidateQueries({ queryKey: connectionKeys.adapters })])
}

export function useCreateProviderInstance() {
  const done = useInvalidate()
  return useMutation({ mutationFn: (body: Schemas['CreateProviderRequest']) => unwrap(client.POST('/api/v1/admin/provider-instances', { body })), onSuccess: done })
}

export function useUpdateProviderInstance() {
  const done = useInvalidate()
  return useMutation({
    mutationFn: (v: { id: string; body: Schemas['UpdateProviderRequest'] }) => unwrap(client.PATCH('/api/v1/admin/provider-instances/{id}', { params: { path: { id: v.id } }, body: v.body })),
    onSuccess: done,
  })
}

export function useRemoveProviderInstance() {
  const done = useInvalidate()
  return useMutation({ mutationFn: (id: string) => unwrap(client.DELETE('/api/v1/admin/provider-instances/{id}', { params: { path: { id } } })), onSuccess: done })
}

export function useAuditEvents(limit = 100) {
  return useQuery({ queryKey: ['admin', 'audit', limit], queryFn: () => unwrap(client.GET('/api/v1/admin/audit', { params: { query: { limit } } })), retry: false })
}
