import { useEffect, useRef, type ReactNode } from 'react'
import { Button } from './Button'

/** Modal built on the native <dialog>: focus trapping, Escape to close and the backdrop come from the browser. */
export function Dialog({ open, onClose, title, children, side = false }: { open: boolean; onClose: () => void; title: string; children: ReactNode; side?: boolean }) {
  const ref = useRef<HTMLDialogElement>(null)

  useEffect(() => {
    const d = ref.current
    if (!d) return
    if (open && !d.open) {
      if (typeof d.showModal === 'function') d.showModal()
      else d.setAttribute('open', '')
    } else if (!open && d.open) {
      if (typeof d.close === 'function') d.close()
      else d.removeAttribute('open')
    }
  }, [open])

  return (
    // Backdrop click is a mouse convenience; Escape (built into <dialog>) is the keyboard equivalent.
    // oxlint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-noninteractive-element-interactions
    <dialog
      ref={ref}
      aria-label={title}
      onClose={onClose}
      onClick={(e) => {
        if (e.target === ref.current) onClose()
      }}
      className={`m-auto max-h-[90vh] w-[min(42rem,calc(100vw-2rem))] rounded-lg border border-border bg-surface p-0 text-text backdrop:bg-black/50 ${side ? 'ml-auto mr-0 h-screen max-h-screen w-[min(34rem,100vw)] rounded-none' : ''}`}
    >
      {open && (
        <div className="flex h-full flex-col">
          <div className="flex items-center justify-between border-b border-border px-4 py-3">
            <h2 className="text-sm font-semibold">{title}</h2>
            <Button variant="ghost" onClick={onClose} aria-label="Close">
              ✕
            </Button>
          </div>
          <div className="overflow-auto p-4">{children}</div>
        </div>
      )}
    </dialog>
  )
}
