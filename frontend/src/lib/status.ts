import type { Tone } from '../components/ui/Badge'

export type RunStatus =
  | 'CREATED' | 'INPUT_RETRIEVAL' | 'AWAITING_INPUT' | 'CONTEXT_GATHERING' | 'INVESTIGATION' | 'FINDINGS_AVAILABLE' | 'PROPOSING_FIX'
  | 'FIX_PROPOSAL_AVAILABLE' | 'AWAITING_APPROVAL' | 'IMPLEMENTATION' | 'VALIDATION' | 'AWAITING_REVIEW' | 'PR_PREPARATION' | 'COMPLETED' | 'FAILED' | 'CANCELLED'

const STATUS: Record<RunStatus, { label: string; tone: Tone; working: boolean }> = {
  CREATED: { label: 'Queued', tone: 'neutral', working: true },
  INPUT_RETRIEVAL: { label: 'Reading input', tone: 'info', working: true },
  AWAITING_INPUT: { label: 'Needs your input', tone: 'warn', working: false },
  CONTEXT_GATHERING: { label: 'Preparing repositories', tone: 'info', working: true },
  INVESTIGATION: { label: 'Investigating', tone: 'info', working: true },
  FINDINGS_AVAILABLE: { label: 'Findings ready', tone: 'ok', working: false },
  PROPOSING_FIX: { label: 'Proposing a fix', tone: 'info', working: true },
  FIX_PROPOSAL_AVAILABLE: { label: 'Proposal ready', tone: 'ok', working: false },
  AWAITING_APPROVAL: { label: 'Awaiting approval', tone: 'warn', working: false },
  IMPLEMENTATION: { label: 'Preparing changes', tone: 'info', working: true },
  VALIDATION: { label: 'Validating', tone: 'info', working: true },
  AWAITING_REVIEW: { label: 'Awaiting review', tone: 'warn', working: false },
  PR_PREPARATION: { label: 'Preparing PR', tone: 'info', working: true },
  COMPLETED: { label: 'Completed', tone: 'ok', working: false },
  FAILED: { label: 'Failed', tone: 'danger', working: false },
  CANCELLED: { label: 'Cancelled', tone: 'neutral', working: false },
}

export function isRunStatus(s: string): s is RunStatus {
  return s in STATUS
}

export function statusInfo(s: string): { label: string; tone: Tone; working: boolean } {
  return isRunStatus(s) ? STATUS[s] : { label: s, tone: 'neutral', working: false }
}

export function isFinal(s: string): boolean {
  return s === 'COMPLETED' || s === 'CANCELLED'
}

const LABELS: Record<string, { label: string; tone: Tone; help: string }> = {
  VERIFIED: { label: 'Verified', tone: 'ok', help: 'Backed by code the server re-read from the pinned commit' },
  SUPPORTED: { label: 'Supported', tone: 'info', help: 'A reasonable inference from verified evidence' },
  HYPOTHESIS: { label: 'Hypothesis', tone: 'warn', help: 'A guess; not confirmed by evidence' },
  UNKNOWN: { label: 'Unknown', tone: 'neutral', help: 'Something that could not be determined' },
}

export function findingLabel(l: string): { label: string; tone: Tone; help: string } {
  return LABELS[l] ?? { label: l, tone: 'neutral', help: '' }
}
