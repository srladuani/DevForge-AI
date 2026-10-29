import { Link } from 'react-router'
import { Alert, Empty, Spinner } from '../../components/ui/Alert'
import { Badge } from '../../components/ui/Badge'
import { Card } from '../../components/ui/Card'
import { errorMessage } from '../../lib/api/client'
import { timeAgo, titleCase } from '../../lib/format'
import { useProjects } from './api'

export function ProjectsPage() {
  const { data, isPending, error } = useProjects()
  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-3">
        <h1 className="text-lg font-semibold">Projects</h1>
        <Link to="/projects/new" className="rounded-md bg-accent px-3 py-1.5 text-sm font-medium text-accent-contrast hover:opacity-90">
          New project
        </Link>
      </div>
      {isPending && <Spinner label="Loading projects" />}
      {error && <Alert tone="danger" title="Could not load projects">{errorMessage(error)}</Alert>}
      {data && data.length === 0 && (
        <Empty title="No projects yet">Create a project, add the repositories it covers, then investigate your first ticket.</Empty>
      )}
      {data && data.length > 0 && (
        <ul className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
          {data.map((p) => (
            <li key={p.id}>
              <Link to={`/projects/${p.id}`} className="block rounded-lg focus-visible:outline-2">
                <Card className="p-4 transition-colors hover:bg-surface-2">
                  <div className="flex items-start justify-between gap-2">
                    <h2 className="truncate font-semibold">{p.name}</h2>
                    <Badge>{titleCase(p.yourRole)}</Badge>
                  </div>
                  <p className="mt-1 text-xs text-muted">
                    {p.kind === 'SUPPORT' ? 'Support / existing project' : titleCase(p.kind)} · created {timeAgo(p.createdAt)}
                  </p>
                </Card>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
