import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { renderApp } from '../test/render'
import { server } from '../test/server'

describe('Sign in (local development)', () => {
  it('asks who you are first, and says plainly that this is not production sign-in', async () => {
    renderApp('/projects', null)
    expect(await screen.findByText('Local development sign-in')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled()
  })

  it('sends the chosen identity with every API call once signed in, and sign-out removes it', async () => {
    const seen: Array<string | null> = []
    server.use(
      http.get('/api/v1/workspaces', ({ request }) => {
        seen.push(request.headers.get('X-Devforge-User'))
        return HttpResponse.json([])
      }),
    )
    const user = userEvent.setup()
    renderApp('/projects', null)
    await user.type(await screen.findByLabelText('Email'), 'Dev@Example.test')
    await user.click(screen.getByRole('button', { name: 'Continue' }))
    expect(await screen.findByText('No projects yet')).toBeInTheDocument()
    expect(seen).toEqual(['dev@example.test'])
    await user.click(screen.getByRole('button', { name: 'Sign out' }))
    await waitFor(() => expect(screen.getByText('Local development sign-in')).toBeInTheDocument())
  })

  it('treats an unknown address as not found, with a way back', async () => {
    renderApp('/nowhere')
    expect(await screen.findByText('Page not found')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to projects' })).toBeInTheDocument()
  })
})
