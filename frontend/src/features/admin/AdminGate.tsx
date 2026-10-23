import type { ReactNode } from 'react'
import { Alert } from '../../components/ui/Alert'
import { ApiError, errorMessage } from '../../lib/api/client'

/** Shows a plain explanation when the caller is not an admin, instead of an error box. */
export function AdminGate({ error, children }: { error: unknown; children: ReactNode }) {
  if (error instanceof ApiError && error.code === 'ADMIN_REQUIRED') {
    return <Alert tone="warn" title="Admins only">This page is for platform administrators.</Alert>
  }
  if (error) return <Alert tone="danger" title="Could not load this page">{errorMessage(error)}</Alert>
  return <>{children}</>
}
