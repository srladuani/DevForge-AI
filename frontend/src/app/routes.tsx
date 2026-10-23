import { Navigate, type RouteObject } from 'react-router'
import { AuditPage } from '../features/admin/AuditPage'
import { ProvidersPage } from '../features/admin/ProvidersPage'
import { ConnectionsPage } from '../features/connections/ConnectionsPage'
import { NewProjectPage } from '../features/projects/NewProjectPage'
import { ProjectPage } from '../features/projects/ProjectPage'
import { ProjectsPage } from '../features/projects/ProjectsPage'
import { NewRunPage } from '../features/runs/NewRunPage'
import { RunPage } from '../features/runs/RunPage'
import { Layout, NotFound } from './Layout'

export const routes: RouteObject[] = [
  {
    element: <Layout />,
    children: [
      { index: true, element: <Navigate to="/projects" replace /> },
      { path: 'projects', element: <ProjectsPage /> },
      { path: 'projects/new', element: <NewProjectPage /> },
      { path: 'projects/:projectId', element: <ProjectPage /> },
      { path: 'projects/:projectId/runs/new', element: <NewRunPage /> },
      { path: 'runs/:runId', element: <RunPage /> },
      { path: 'connections', element: <ConnectionsPage /> },
      { path: 'admin/providers', element: <ProvidersPage /> },
      { path: 'admin/audit', element: <AuditPage /> },
      { path: '*', element: <NotFound /> },
    ],
  },
]
