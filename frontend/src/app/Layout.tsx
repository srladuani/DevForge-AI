import { NavLink, Outlet, Link } from 'react-router'
import { Button } from '../components/ui/Button'
import { SignInDev } from '../features/auth/SignInDev'
import { signOut, useUserEmail } from '../lib/session'

const nav = 'rounded-md px-3 py-1.5 text-sm font-medium text-muted hover:bg-surface-2 hover:text-text aria-[current=page]:bg-surface-2 aria-[current=page]:text-text'

export function Layout() {
  const email = useUserEmail()
  if (!email) return <SignInDev />
  return (
    <div className="min-h-screen md:grid md:grid-cols-[14rem_1fr]">
      <a href="#main" className="sr-only focus:not-sr-only focus:absolute focus:left-2 focus:top-2 focus:z-50 focus:rounded focus:bg-surface focus:px-3 focus:py-2">
        Skip to content
      </a>
      <aside className="flex flex-col gap-4 border-b border-border bg-surface p-3 md:border-b-0 md:border-r">
        <Link to="/projects" className="flex items-center gap-2 px-2 py-1 text-base font-semibold">
          <span aria-hidden className="grid size-6 place-items-center rounded-md bg-accent text-xs font-bold text-accent-contrast">
            D
          </span>
          DevForge AI
        </Link>
        <nav aria-label="Main" className="flex gap-1 md:flex-col">
          <NavLink to="/projects" className={nav}>
            Projects
          </NavLink>
          <NavLink to="/connections" className={nav}>
            My connections
          </NavLink>
          <NavLink to="/admin/providers" className={nav}>
            Providers (admin)
          </NavLink>
          <NavLink to="/admin/audit" className={nav}>
            Audit log (admin)
          </NavLink>
        </nav>
        <div className="mt-auto hidden space-y-2 border-t border-border pt-3 text-xs text-muted md:block">
          <p className="break-all" title="Signed in (local development sign-in)">
            {email}
          </p>
          <Button variant="ghost" onClick={signOut}>
            Sign out
          </Button>
        </div>
      </aside>
      <main id="main" className="min-w-0 p-4 md:p-6">
        <Outlet />
      </main>
    </div>
  )
}

export function NotFound() {
  return (
    <div className="space-y-2">
      <h1 className="text-lg font-semibold">Page not found</h1>
      <p className="text-sm text-muted">
        <Link to="/projects" className="text-accent underline">
          Back to projects
        </Link>
      </p>
    </div>
  )
}
