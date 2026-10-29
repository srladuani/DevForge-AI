import { Badge } from '../../components/ui/Badge'
import { Empty } from '../../components/ui/Alert'
import { describeEvent } from '../../lib/events'
import { timeAgo } from '../../lib/format'
import type { RunEvent } from '../../lib/sse'
import type { StreamStatus } from './api'

const NOISY = new Set(['STEP_STARTED'])

export function TimelineTab({ events, stream }: { events: RunEvent[]; stream: StreamStatus }) {
  const shown = events.filter((e) => !NOISY.has(e.type))
  return (
    <div className="space-y-3">
      <div className="flex items-center gap-2 text-xs text-muted" aria-live="polite">
        {stream === 'live' && <Badge tone="ok">Live</Badge>}
        {stream === 'connecting' && <Badge>Connecting…</Badge>}
        {stream === 'reconnecting' && <Badge tone="warn">Reconnecting…</Badge>}
        {stream === 'closed' && <Badge>Stream ended</Badge>}
        <span>{events.length} event{events.length === 1 ? '' : 's'}</span>
      </div>
      {shown.length === 0 ? (
        <Empty title="No events yet">Progress appears here as it happens.</Empty>
      ) : (
        <ol className="space-y-1 border-l border-border pl-4">
          {shown.map((e) => (
            <li key={e.seq} className="relative text-sm">
              <span aria-hidden className="absolute -left-[1.3rem] top-2 size-2 rounded-full bg-border" />
              <span className="mr-2 font-mono text-xs text-muted">#{e.seq}</span>
              {describeEvent(e)}
              <span className="ml-2 text-xs text-muted">{timeAgo(e.createdAt)}</span>
            </li>
          ))}
        </ol>
      )}
    </div>
  )
}
