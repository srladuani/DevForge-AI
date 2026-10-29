import { QueryClient } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import { RouterProvider } from 'react-router/dom'
import { createMemoryRouter } from 'react-router'
import { Providers } from '../app/providers'
import { routes } from '../app/routes'
import { signIn, signOut } from '../lib/session'

/** Renders the real application routes at a URL, signed in as the given user, against whatever MSW handlers the test installed. */
export function renderApp(path: string, user: string | null = 'dev@example.test') {
  if (user) signIn(user)
  else signOut()
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 0 }, mutations: { retry: false } } })
  const utils = render(
    <Providers client={client}>
      <RouterProvider router={router} />
    </Providers>,
  )
  return { ...utils, router, client }
}
