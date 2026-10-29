import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { createBrowserRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import './index.css'
import { Providers } from './app/providers'
import { routes } from './app/routes'
import { configureAuth, loadAuthConfig } from './lib/auth'

const root = document.getElementById('root')
if (!root) throw new Error('Missing #root element')

// How people sign in is decided by the server, so ask before showing anything.
void loadAuthConfig().then((config) => {
  configureAuth(config)
  const router = createBrowserRouter(routes)
  createRoot(root).render(
    <StrictMode>
      <Providers>
        <RouterProvider router={router} />
      </Providers>
    </StrictMode>,
  )
})
