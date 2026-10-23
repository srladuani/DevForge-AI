import { useState, type FormEvent } from 'react'
import { Alert } from '../../components/ui/Alert'
import { Button } from '../../components/ui/Button'
import { Input } from '../../components/ui/Field'
import { signIn } from '../../lib/session'

/** Local development sign-in. Production uses single sign-on, which replaces this screen without touching the rest of the app. */
export function SignInDev() {
  const [email, setEmail] = useState('')
  const valid = /^[^@\s]{1,64}@[^@\s]{1,255}$/.test(email.trim())

  function submit(e: FormEvent) {
    e.preventDefault()
    if (valid) signIn(email.trim().toLowerCase())
  }

  return (
    <div className="grid min-h-screen place-items-center p-4">
      <form onSubmit={submit} className="w-full max-w-sm space-y-4 rounded-lg border border-border bg-surface p-6">
        <h1 className="text-lg font-semibold">DevForge AI</h1>
        <Alert tone="warn" title="Local development sign-in">
          This build trusts the email you type. It is only available against a developer-machine backend; production uses single sign-on.
        </Alert>
        <Input label="Email" type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="you@example.com" required />
        <Button type="submit" variant="primary" disabled={!valid} className="w-full">
          Continue
        </Button>
      </form>
    </div>
  )
}
