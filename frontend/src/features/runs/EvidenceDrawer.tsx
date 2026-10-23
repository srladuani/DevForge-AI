import { Alert } from '../../components/ui/Alert'
import { Badge } from '../../components/ui/Badge'
import { Dialog } from '../../components/ui/Dialog'
import { shortHash, shortSha } from '../../lib/format'
import type { Evidence } from './api'

export function evidenceLabel(e: Evidence): string {
  return `${e.repoName}:${e.path}:${e.startLine === e.endLine ? e.startLine : `${e.startLine}-${e.endLine}`}`
}

/** The cited code exactly as the server re-read it from the pinned commit, or the reason it could not be confirmed. */
export function EvidenceDrawer({ evidence, onClose }: { evidence: Evidence | null; onClose: () => void }) {
  return (
    <Dialog open={evidence !== null} onClose={onClose} title="Evidence" side>
      {evidence && (
        <div className="space-y-3 text-sm">
          <div className="flex flex-wrap items-center gap-2">
            {evidence.verified ? <Badge tone="ok">Confirmed in the repository</Badge> : <Badge tone="danger">Could not be confirmed</Badge>}
            <span className="text-xs text-muted">
              commit <span className="font-mono">{shortSha(evidence.sha)}</span>
            </span>
          </div>
          <p className="break-all font-mono text-xs">{evidenceLabel(evidence)}</p>
          {!evidence.verified && <Alert tone="danger" title="Why">{evidence.problem ?? 'The cited lines could not be read.'}</Alert>}
          {evidence.excerpt && (
            <pre aria-label="Excerpt" className="overflow-x-auto rounded-md border border-border bg-code py-2 font-mono text-xs leading-5">
              {evidence.excerpt.replace(/\n$/, '').split('\n').map((line, i) => (
                <code key={i} className="block min-w-max px-3 whitespace-pre">
                  <span aria-hidden className="mr-3 inline-block w-8 select-none text-right text-muted">
                    {evidence.startLine + i}
                  </span>
                  {line === '' ? ' ' : line}
                </code>
              ))}
            </pre>
          )}
          {evidence.contentHash && (
            <p className="text-xs text-muted">
              Content fingerprint <span className="font-mono">{shortHash(evidence.contentHash)}</span>. If the file changes later, this fingerprint shows the cited text is no longer the same.
            </p>
          )}
        </div>
      )}
    </Dialog>
  )
}
