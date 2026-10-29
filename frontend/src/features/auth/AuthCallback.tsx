import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { Alert, Spinner } from '../../components/ui/Alert'
import { Button } from '../../components/ui/Button'
import { completeProviderSignIn, signInWithProvider } from '../../lib/auth'

/** Where the identity provider sends the browser back to. Finishes the login once, then returns to the page the person was on. */
export function AuthCallback() {
  const navigate = useNavigate()
  const started = useRef(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (started.current) return // the authorization code can be used only once (StrictMode runs effects twice in development)
    started.current = true
    completeProviderSignIn()
      .then((back) => navigate(back, { replace: true }))
      .catch((e: unknown) => setError(e instanceof Error ? e.message : 'Sign-in did not complete'))
  }, [navigate])

  if (error) {
    return (
      <div className="mx-auto mt-24 max-w-md space-y-3">
        <Alert tone="danger" title="Could not sign you in">
          {error}
        </Alert>
        <Button variant="primary" onClick={() => void signInWithProvider('/')}>
          Try again
        </Button>
      </div>
    )
  }
  return <Spinner label="Signing you in" />
}
