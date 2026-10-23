import { useId, useRef, type KeyboardEvent, type ReactNode } from 'react'

export interface TabDef {
  id: string
  label: string
  count?: number | undefined
}

/** WAI-ARIA tabs: arrow keys move focus, Home/End jump. The caller renders the active panel. */
export function Tabs({ tabs, active, onChange, children, label }: { tabs: TabDef[]; active: string; onChange: (id: string) => void; children: ReactNode; label: string }) {
  const base = useId()
  const refs = useRef<Array<HTMLButtonElement | null>>([])

  function onKey(e: KeyboardEvent, index: number) {
    let next = index
    if (e.key === 'ArrowRight') next = (index + 1) % tabs.length
    else if (e.key === 'ArrowLeft') next = (index - 1 + tabs.length) % tabs.length
    else if (e.key === 'Home') next = 0
    else if (e.key === 'End') next = tabs.length - 1
    else return
    e.preventDefault()
    const target = tabs[next]
    if (target) {
      onChange(target.id)
      refs.current[next]?.focus()
    }
  }

  return (
    <div>
      <div role="tablist" aria-label={label} className="flex gap-1 overflow-x-auto border-b border-border">
        {tabs.map((t, i) => {
          const selected = t.id === active
          return (
            <button
              key={t.id}
              ref={(el) => {
                refs.current[i] = el
              }}
              role="tab"
              id={`${base}-tab-${t.id}`}
              aria-selected={selected}
              aria-controls={`${base}-panel`}
              tabIndex={selected ? 0 : -1}
              onClick={() => onChange(t.id)}
              onKeyDown={(e) => onKey(e, i)}
              className={`-mb-px whitespace-nowrap border-b-2 px-3 py-2 text-sm font-medium ${selected ? 'border-accent text-text' : 'border-transparent text-muted hover:text-text'}`}
            >
              {t.label}
              {t.count !== undefined && <span className="ml-1.5 rounded-full bg-surface-2 px-1.5 text-xs text-muted">{t.count}</span>}
            </button>
          )
        })}
      </div>
      <div role="tabpanel" id={`${base}-panel`} aria-labelledby={`${base}-tab-${active}`} className="pt-4">
        {children}
      </div>
    </div>
  )
}
