import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { ids, project, projectTypes, repo } from '../../test/fixtures'
import { renderApp } from '../../test/render'
import { server } from '../../test/server'

function baseHandlers() {
  server.use(http.get('/api/v1/project-types', () => HttpResponse.json(projectTypes)), http.get('/api/v1/workspaces', () => HttpResponse.json([])))
}

describe('New project wizard', () => {
  it('offers Support, shows New project as coming soon, and does not let you choose it', async () => {
    baseHandlers()
    renderApp('/projects/new')
    const support = await screen.findByRole('radio', { name: /support \/ existing project/i })
    const fresh = screen.getByRole('radio', { name: /new project/i })
    expect(support).toBeEnabled()
    expect(support).toBeChecked()
    expect(fresh).toBeDisabled()
    expect(screen.getByText('Coming soon')).toBeInTheDocument()
  })

  it('creates the project and adds each repository with the role the user chose', async () => {
    baseHandlers()
    const calls: Array<{ url: string; body: unknown }> = []
    server.use(
      http.post('/api/v1/workspaces', async ({ request }) => {
        calls.push({ url: 'project', body: await request.json() })
        return HttpResponse.json(project({ name: 'Acme' }), { status: 201 })
      }),
      http.post(`/api/v1/workspaces/${ids.project}/repositories`, async ({ request }) => {
        const body = await request.json()
        calls.push({ url: 'repo', body })
        return HttpResponse.json(repo(), { status: 201 })
      }),
      http.get(`/api/v1/workspaces/${ids.project}`, () => HttpResponse.json(project())),
      http.get(`/api/v1/workspaces/${ids.project}/repositories`, () => HttpResponse.json([repo()])),
      http.get(`/api/v1/workspaces/${ids.project}/runs`, () => HttpResponse.json([])),
    )
    const user = userEvent.setup()
    const { router } = renderApp('/projects/new')
    await user.click(await screen.findByRole('button', { name: 'Continue' }))

    await user.type(screen.getByLabelText('Project name'), 'Acme')
    const paths = screen.getAllByLabelText('Path on this machine')
    expect(paths).toHaveLength(2) // a backend and a web frontend row are offered by default
    expect(screen.getAllByLabelText('Role').map((s) => (s as HTMLSelectElement).value)).toEqual(['BACKEND', 'WEB_FRONTEND'])
    await user.type(paths[0]!, '/work/api')
    await user.type(paths[1]!, '/work/admin-portal')
    await user.click(screen.getByRole('button', { name: 'Create project' }))

    await waitFor(() => expect(router.state.location.pathname).toBe(`/projects/${ids.project}`))
    expect(calls).toEqual([
      { url: 'project', body: { name: 'Acme', kind: 'SUPPORT' } },
      { url: 'repo', body: { path: '/work/api', role: 'BACKEND' } },
      { url: 'repo', body: { path: '/work/admin-portal', role: 'WEB_FRONTEND' } },
    ])
  })

  it('keeps the created project when one repository fails, and retries only the failed one', async () => {
    baseHandlers()
    let projectCreates = 0
    const repoPaths: string[] = []
    let failWeb = true
    server.use(
      http.post('/api/v1/workspaces', () => {
        projectCreates++
        return HttpResponse.json(project(), { status: 201 })
      }),
      http.post(`/api/v1/workspaces/${ids.project}/repositories`, async ({ request }) => {
        const body = (await request.json()) as { path: string }
        repoPaths.push(body.path)
        if (body.path === '/work/web' && failWeb) return HttpResponse.json({ code: 'NOT_A_GIT_REPOSITORY', detail: 'Path is not the root of a git repository', status: 422 }, { status: 422 })
        return HttpResponse.json(repo(), { status: 201 })
      }),
    )
    const user = userEvent.setup()
    renderApp('/projects/new')
    await user.click(await screen.findByRole('button', { name: 'Continue' }))
    await user.type(screen.getByLabelText('Project name'), 'Acme')
    const paths = screen.getAllByLabelText('Path on this machine')
    await user.type(paths[0]!, '/work/api')
    await user.type(paths[1]!, '/work/web')
    await user.click(screen.getByRole('button', { name: 'Create project' }))

    expect(await screen.findByText('Path is not the root of a git repository')).toBeInTheDocument()
    expect(screen.getByText('Added')).toBeInTheDocument()
    expect(screen.getByText(/the project was created, but some repositories could not be added/i)).toBeInTheDocument()

    failWeb = false
    server.use(http.get(`/api/v1/workspaces/${ids.project}`, () => HttpResponse.json(project())), http.get(`/api/v1/workspaces/${ids.project}/repositories`, () => HttpResponse.json([])), http.get(`/api/v1/workspaces/${ids.project}/runs`, () => HttpResponse.json([])))
    await user.click(screen.getByRole('button', { name: 'Retry failed repositories' }))
    await waitFor(() => expect(repoPaths).toEqual(['/work/api', '/work/web', '/work/web']))
    expect(projectCreates).toBe(1) // never created a second project
  })

  it('does not enable Create until there is a name and at least one repository path', async () => {
    baseHandlers()
    const user = userEvent.setup()
    renderApp('/projects/new')
    await user.click(await screen.findByRole('button', { name: 'Continue' }))
    const create = screen.getByRole('button', { name: 'Create project' })
    expect(create).toBeDisabled()
    await user.type(screen.getByLabelText('Project name'), 'Acme')
    expect(create).toBeDisabled()
    await user.type(screen.getAllByLabelText('Path on this machine')[0]!, '/work/api')
    expect(create).toBeEnabled()
    await user.click(within(screen.getAllByRole('heading', { name: /repository 2/i })[0]!.parentElement!.parentElement!).getByRole('button', { name: /remove repository 2/i }))
    expect(screen.queryByRole('heading', { name: /repository 2/i })).not.toBeInTheDocument()
  })
})
