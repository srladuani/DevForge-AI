import type { RunEvent } from './sse'
import { findingLabel, statusInfo } from './status'

function str(v: unknown): string {
  return typeof v === 'string' ? v : typeof v === 'number' || typeof v === 'boolean' ? String(v) : ''
}

/** One human sentence per event. Unknown event types fall back to their name, so a newer server never breaks an older screen. */
export function describeEvent(e: RunEvent): string {
  const p = e.payload
  switch (e.type) {
    case 'RUN_CREATED':
      return `Run created: ${str(p.mode).toLowerCase().replace(/_/g, ' ')}, ${str(p.repositories)} repositor${p.repositories === 1 ? 'y' : 'ies'}, ${str(p.provider)} / ${str(p.model)}${p.simulated === true ? ' (simulated)' : ''}`
    case 'STATE_CHANGED': {
      const reason = str(p.reason)
      return `${statusInfo(str(p.from)).label} → ${statusInfo(str(p.to)).label}${reason ? ` (${reason})` : ''}`
    }
    case 'STEP_STARTED':
      return `Started ${str(p.step).toLowerCase().replace(/_/g, ' ')} (attempt ${str(p.attempt)})`
    case 'STEP_FINISHED':
      return `Finished ${str(p.step).toLowerCase().replace(/_/g, ' ')}: ${str(p.status).toLowerCase()}${p.detail ? ` — ${str(p.detail)}` : ''}`
    case 'INPUT_NORMALIZED':
      return 'Read and summarised the input'
    case 'INJECTION_FLAGGED':
      return 'Warning: the input contains wording aimed at an assistant; it was treated as data'
    case 'NEEDS_INPUT':
      return p.reason === 'CREDENTIAL' ? `Paused: your AI credential needs attention (${str(p.problem).toLowerCase().replace(/_/g, ' ')})` : 'Paused: more information is needed'
    case 'REPO_CONTEXT':
      return `Prepared ${str(p.repository)} (${str(p.role).toLowerCase().replace(/_/g, ' ')}): ${str(p.files)} files at ${str(p.sha)}`
    case 'TOOL_CALL':
      return `${str(p.tool)}(${str(p.arguments)}) → ${str(p.result)}`
    case 'FINDING_ADDED':
      return `Finding [${findingLabel(str(p.label)).label}]: ${str(p.text)}`
    case 'ARTIFACT_CREATED':
      return `Produced ${str(p.type).toLowerCase().replace(/_/g, ' ')}`
    case 'USAGE':
      return `Used ${str(p.inputTokens)} input and ${str(p.outputTokens)} output tokens${p.costKnown === false ? ' (cost unknown)' : ''}`
    case 'INVESTIGATION_INCOMPLETE':
      return `Stopped early: ${str(p.reason)}`
    case 'RETRYING':
      return `Temporary problem (${str(p.reason).toLowerCase().replace(/_/g, ' ')}); retrying after attempt ${str(p.attempt)}`
    case 'APPROVAL_REQUESTED':
      return 'Approval requested'
    case 'APPROVAL_DECIDED':
      return p.approved === true ? 'Approved' : 'Rejected'
    case 'CHANGE_PREPARED':
      return `Prepared a patch for ${str(p.repository)}${p.simulated === true ? ' (simulated)' : ''}`
    case 'VALIDATION_RESULT':
      return `Check on ${str(p.repository)}: ${str(p.status).toLowerCase()}`
    case 'CANCEL_REQUESTED':
      return `Cancellation requested by ${str(p.by)}`
    default:
      return e.type.toLowerCase().replace(/_/g, ' ')
  }
}
