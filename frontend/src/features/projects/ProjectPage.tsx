import { Link, useParams, useSearchParams } from 'react-router'
import { Alert, Spinner } from '../../components/ui/Alert'
import { Badge } from '../../components/ui/Badge'
import { Tabs } from '../../components/ui/Tabs'
import { errorMessage } from '../../lib/api/client'
import { titleCase } from '../../lib/format'
import { useProject, useRepos } from './api'
import { ReposTab } from './ReposTab'
import { RunsTab } from './RunsTab'
import { TeamTab } from './TeamTab'

const TABS = ['runs', 'repositories', 'team'] as const
type Tab = (typeof TABS)[number]

function asTab(v: string | null): Tab {
  return TABS.find((t) => t === v) ?? 'runs'
}

export function ProjectPage() {
  const { projectId = '' } = useParams()
  const [params, setParams] = useSearchParams()
  const project = useProject(projectId)
  const repos = useRepos(projectId)
  const tab = asTab(params.get('tab'))

  if (project.isPending) return <Spinner label="Loading project" />
  if (project.error) return <Alert tone="danger" title="Could not open this project">{errorMessage(project.error)}</Alert>

  const p = project.data
  const canRun = p.yourRole !== 'VIEWER'
  const canManage = p.yourRole === 'ADMIN'
  const noRepos = repos.data !== undefined && repos.data.length === 0

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-lg font-semibold">{p.name}</h1>
          <p className="mt-0.5 flex items-center gap-2 text-sm text-muted">
            {p.kind === 'SUPPORT' ? 'Support / existing project' : titleCase(p.kind)} <Badge>{titleCase(p.yourRole)}</Badge>
          </p>
        </div>
        {canRun && !noRepos ? (
          <Link to={`/projects/${p.id}/runs/new`} className="rounded-md bg-accent px-3 py-1.5 text-sm font-medium text-accent-contrast hover:opacity-90">
            New run
          </Link>
        ) : null}
      </div>
      {noRepos && <Alert tone="warn" title="This project has no repositories yet">Add at least one repository before starting a run.</Alert>}
      <Tabs
        label="Project sections"
        active={tab}
        onChange={(id) => setParams({ tab: id }, { replace: true })}
        tabs={[
          { id: 'runs', label: 'Runs' },
          { id: 'repositories', label: 'Repositories', count: repos.data?.length },
          { id: 'team', label: 'People & policy' },
        ]}
      >
        {tab === 'runs' && <RunsTab projectId={p.id} canRun={canRun && !noRepos} />}
        {tab === 'repositories' && <ReposTab projectId={p.id} canManage={canManage} />}
        {tab === 'team' && <TeamTab project={p} />}
      </Tabs>
    </div>
  )
}
