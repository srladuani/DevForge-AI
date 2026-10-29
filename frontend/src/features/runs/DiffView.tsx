/** Read-only unified diff. The +/- characters stay in the text, so meaning never depends on colour alone. */
export function DiffView({ patch }: { patch: string }) {
  const lines = patch.split('\n')
  if (lines.at(-1) === '') lines.pop()
  return (
    <pre aria-label="Unified diff" className="overflow-x-auto rounded-md border border-border bg-code py-2 font-mono text-xs leading-5">
      {lines.map((l, i) => {
        const cls = l.startsWith('+++') || l.startsWith('---') ? 'text-muted' : l.startsWith('@@') ? 'text-info' : l.startsWith('+') ? 'bg-add' : l.startsWith('-') ? 'bg-del' : ''
        return (
          <code key={i} className={`block min-w-max px-3 whitespace-pre ${cls}`}>
            {l === '' ? ' ' : l}
          </code>
        )
      })}
    </pre>
  )
}
