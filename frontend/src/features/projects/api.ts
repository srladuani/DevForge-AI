import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { client, unwrap, unwrapEmpty, type Schemas } from '../../lib/api/client'

export type Project = Schemas['WorkspaceView']
export type ProjectRepo = Schemas['WorkspaceRepoView']
export type ProjectType = Schemas['ProjectTypeDefinition']
export type Member = Schemas['Member']
export type RepoRole = Schemas['AddRepositoryRequest']['role']
export type ProjectRole = Schemas['SetMemberRequest']['role']
export type Policy = Schemas['WorkspacePolicy']
export type TrackerSetting = Schemas['TrackerView']

export const projectKeys = {
  all: ['projects'] as const,
  types: ['project-types'] as const,
  one: (id: string) => ['project', id] as const,
  repos: (id: string) => ['project', id, 'repos'] as const,
  members: (id: string) => ['project', id, 'members'] as const,
  tracker: (id: string) => ['project', id, 'tracker'] as const,
  publishing: (id: string) => ['project', id, 'publishing'] as const,
}

export function useProjectTypes() {
  return useQuery({ queryKey: projectKeys.types, queryFn: () => unwrap(client.GET('/api/v1/project-types')) })
}

export function useProjects() {
  return useQuery({ queryKey: projectKeys.all, queryFn: () => unwrap(client.GET('/api/v1/workspaces')) })
}

export function useProject(id: string) {
  return useQuery({ queryKey: projectKeys.one(id), enabled: id !== '', queryFn: () => unwrap(client.GET('/api/v1/workspaces/{id}', { params: { path: { id } } })) })
}

export function useRepos(id: string) {
  return useQuery({ queryKey: projectKeys.repos(id), queryFn: () => unwrap(client.GET('/api/v1/workspaces/{id}/repositories', { params: { path: { id } } })) })
}

/** How repositories can be added on this server (a URL always; a server path only when the administrator allows it). Assumes paths are allowed until the server says otherwise, since the API enforces it anyway. */
export function useRepositorySources() {
  return useQuery({ queryKey: ['repository-sources'] as const, queryFn: () => unwrap(client.GET('/api/v1/repository-sources')), staleTime: Infinity })
}

export function useMembers(id: string) {
  return useQuery({ queryKey: projectKeys.members(id), queryFn: () => unwrap(client.GET('/api/v1/workspaces/{id}/members', { params: { path: { id } } })) })
}

export function useCreateProject() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: Schemas['CreateWorkspaceRequest']) => unwrap(client.POST('/api/v1/workspaces', { body })),
    onSuccess: () => qc.invalidateQueries({ queryKey: projectKeys.all }),
  })
}

export function useUpdateProject(id: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: Schemas['UpdateWorkspaceRequest']) => unwrap(client.PATCH('/api/v1/workspaces/{id}', { params: { path: { id } }, body })),
    onSuccess: () => qc.invalidateQueries({ queryKey: projectKeys.one(id) }),
  })
}

/** Plain function (not a hook) so a wizard can add repositories to a project it has only just created. */
export function addRepository(id: string, body: Schemas['AddRepositoryRequest']) {
  return unwrap(client.POST('/api/v1/workspaces/{id}/repositories', { params: { path: { id } }, body }))
}

export function useAddRepo(id: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: Schemas['AddRepositoryRequest']) => addRepository(id, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: projectKeys.repos(id) }),
  })
}

export function useUpdateRepo(id: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (v: { repoId: string; body: Schemas['UpdateRepositoryRequest'] }) =>
      unwrap(client.PATCH('/api/v1/workspaces/{id}/repositories/{repoId}', { params: { path: { id, repoId: v.repoId } }, body: v.body })),
    onSuccess: () => qc.invalidateQueries({ queryKey: projectKeys.repos(id) }),
  })
}

export function useRemoveRepo(id: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (repoId: string) => unwrapEmpty(client.DELETE('/api/v1/workspaces/{id}/repositories/{repoId}', { params: { path: { id, repoId } } })),
    onSuccess: () => qc.invalidateQueries({ queryKey: projectKeys.repos(id) }),
  })
}

export function useRefreshRepo(id: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (repoId: string) => unwrap(client.POST('/api/v1/workspaces/{id}/repositories/{repoId}/refresh', { params: { path: { id, repoId } } })),
    onSuccess: () => qc.invalidateQueries({ queryKey: projectKeys.repos(id) }),
  })
}

export function useSetMember(id: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: Schemas['SetMemberRequest']) => unwrap(client.PUT('/api/v1/workspaces/{id}/members', { params: { path: { id } }, body })),
    onSuccess: () => qc.invalidateQueries({ queryKey: projectKeys.members(id) }),
  })
}

export function useRemoveMember(id: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (userId: string) => unwrapEmpty(client.DELETE('/api/v1/workspaces/{id}/members/{userId}', { params: { path: { id, userId } } })),
    onSuccess: () => qc.invalidateQueries({ queryKey: projectKeys.members(id) }),
  })
}

export const REPO_ROLES: ReadonlyArray<{ value: RepoRole; label: string }> = [
  { value: 'BACKEND', label: 'Backend' },
  { value: 'WEB_FRONTEND', label: 'Web frontend' },
  { value: 'MOBILE', label: 'Mobile app' },
  { value: 'LIBRARY', label: 'Library' },
  { value: 'OTHER', label: 'Other' },
]

export function roleLabel(role: string): string {
  return REPO_ROLES.find((r) => r.value === role)?.label ?? role
}

export function useTracker(id: string) {
  return useQuery({ queryKey: projectKeys.tracker(id), enabled: id !== '', queryFn: () => unwrap(client.GET('/api/v1/workspaces/{id}/tracker', { params: { path: { id } } })) })
}

export function useSetTracker(id: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: Schemas['SetTrackerRequest']) => unwrap(client.PUT('/api/v1/workspaces/{id}/tracker', { params: { path: { id } }, body })),
    onSuccess: () => qc.invalidateQueries({ queryKey: projectKeys.tracker(id) }),
  })
}

export function useClearTracker(id: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => unwrapEmpty(client.DELETE('/api/v1/workspaces/{id}/tracker', { params: { path: { id } } })),
    onSuccess: () => qc.invalidateQueries({ queryKey: projectKeys.tracker(id) }),
  })
}

export type PublishSettings = Schemas['PublishSettings']

export function usePublishSettings(id: string) {
  return useQuery({ queryKey: projectKeys.publishing(id), enabled: id !== '', queryFn: () => unwrap(client.GET('/api/v1/workspaces/{id}/publishing', { params: { path: { id } } })) })
}

export function useSetPublishSettings(id: string) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: Schemas['PublishSettingsRequest']) => unwrap(client.PUT('/api/v1/workspaces/{id}/publishing', { params: { path: { id } }, body })),
    onSuccess: () => qc.invalidateQueries({ queryKey: projectKeys.publishing(id) }),
  })
}
