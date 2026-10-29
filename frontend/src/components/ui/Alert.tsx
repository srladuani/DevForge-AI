import type { ReactNode } from 'react'
import type { Tone } from './Badge'

const tones: Record<Tone, string> = {
  neutral: 'border-border bg-surface-2 text-text',
  ok: 'border-ok/40 bg-ok-bg text-text',
  warn: 'border-warn/40 bg-warn-bg text-text',
  danger: 'border-danger/40 bg-danger-bg text-text',
  info: 'border-info/40 bg-info-bg text-text',
  sim: 'border-sim/40 bg-sim-bg text-text',
}

export function Alert({ tone = 'info', title, children }: { tone?: Tone; title?: string; children?: ReactNode }) {
  const body = (
    <>
      {title && <p className="font-semibold">{title}</p>}
      {children && <div className={title ? 'mt-0.5' : ''}>{children}</div>}
    </>
  )
  const cls = `block rounded-md border px-3 py-2 text-sm ${tones[tone]}`
  return tone === 'danger' ? (
    <div role="alert" className={cls}>
      {body}
    </div>
  ) : (
    <output className={cls}>{body}</output>
  )
}

export function Spinner({ label = 'Loading' }: { label?: string }) {
  return (
    <output className="flex items-center gap-2 p-4 text-sm text-muted">
      <span aria-hidden className="size-4 animate-spin rounded-full border-2 border-current border-t-transparent" />
      {label}…
    </output>
  )
}

export function Empty({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="rounded-lg border border-dashed border-border p-8 text-center">
      <p className="text-sm font-medium">{title}</p>
      {children && <div className="mt-1 text-sm text-muted">{children}</div>}
    </div>
  )
}
