import { Badge } from '../../components/ui/Badge'
import { findingLabel, statusInfo } from '../../lib/status'

export function RunStatusBadge({ status }: { status: string }) {
  const s = statusInfo(status)
  return (
    <Badge tone={s.tone}>
      {s.working && <span aria-hidden className="mr-1.5 inline-block size-2 animate-pulse rounded-full bg-current" />}
      {s.label}
    </Badge>
  )
}

export function SimulatedBadge() {
  return (
    <Badge tone="sim" title="Produced by the built-in simulation, not by an AI model">
      Simulated
    </Badge>
  )
}

export function FindingLabelBadge({ label }: { label: string }) {
  const l = findingLabel(label)
  return (
    <Badge tone={l.tone} title={l.help}>
      {l.label}
    </Badge>
  )
}
