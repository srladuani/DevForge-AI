import { useId, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from 'react'

const control = 'w-full rounded-md border border-border bg-surface px-3 py-1.5 text-sm text-text placeholder:text-muted disabled:opacity-60'

interface FieldProps {
  label: string
  hint?: ReactNode
  error?: string | undefined
}

function Wrap({ id, label, hint, error, children }: FieldProps & { id: string; children: ReactNode }) {
  return (
    <div className="space-y-1">
      <label htmlFor={id} className="block text-sm font-medium">
        {label}
      </label>
      {children}
      {hint && !error && (
        <p id={`${id}-hint`} className="text-xs text-muted">
          {hint}
        </p>
      )}
      {error && (
        <p id={`${id}-error`} role="alert" className="text-xs text-danger">
          {error}
        </p>
      )}
    </div>
  )
}

function describedBy(id: string, hint: unknown, error: unknown): string | undefined {
  return error ? `${id}-error` : hint ? `${id}-hint` : undefined
}

export function Input({ label, hint, error, className = '', ...rest }: FieldProps & InputHTMLAttributes<HTMLInputElement>) {
  const id = useId()
  return (
    <Wrap id={id} label={label} hint={hint} error={error}>
      <input id={id} aria-invalid={error ? true : undefined} aria-describedby={describedBy(id, hint, error)} className={`${control} ${className}`} {...rest} />
    </Wrap>
  )
}

export function Textarea({ label, hint, error, className = '', ...rest }: FieldProps & TextareaHTMLAttributes<HTMLTextAreaElement>) {
  const id = useId()
  return (
    <Wrap id={id} label={label} hint={hint} error={error}>
      <textarea id={id} aria-invalid={error ? true : undefined} aria-describedby={describedBy(id, hint, error)} className={`${control} min-h-24 ${className}`} {...rest} />
    </Wrap>
  )
}

export function Select({ label, hint, error, className = '', children, ...rest }: FieldProps & SelectHTMLAttributes<HTMLSelectElement>) {
  const id = useId()
  return (
    <Wrap id={id} label={label} hint={hint} error={error}>
      <select id={id} aria-invalid={error ? true : undefined} aria-describedby={describedBy(id, hint, error)} className={`${control} ${className}`} {...rest}>
        {children}
      </select>
    </Wrap>
  )
}
