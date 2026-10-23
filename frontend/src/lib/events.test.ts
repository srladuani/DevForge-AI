import { describe, expect, it } from 'vitest'
import { describeEvent } from './events'
import type { RunEvent } from './sse'

const ev = (type: string, payload: Record<string, unknown> = {}): RunEvent => ({ seq: 1, type, payload, createdAt: '' })

describe('describeEvent', () => {
  it('turns state changes into plain language', () => {
    expect(describeEvent(ev('STATE_CHANGED', { from: 'INVESTIGATION', to: 'FINDINGS_AVAILABLE' }))).toBe('Investigating → Findings ready')
    expect(describeEvent(ev('STATE_CHANGED', { from: 'CREATED', to: 'INPUT_RETRIEVAL', reason: 'picked up by a worker' }))).toContain('(picked up by a worker)')
  })

  it('labels findings with their trust level', () => {
    expect(describeEvent(ev('FINDING_ADDED', { label: 'HYPOTHESIS', text: 'maybe X' }))).toBe('Finding [Hypothesis]: maybe X')
  })

  it('says when a credential problem paused the run', () => {
    expect(describeEvent(ev('NEEDS_INPUT', { reason: 'CREDENTIAL', problem: 'AUTH_INVALID' }))).toContain('credential needs attention')
    expect(describeEvent(ev('NEEDS_INPUT', { reason: 'CLARIFICATION' }))).toContain('more information')
  })

  it('reports an investigation that stopped early with its reason', () => {
    expect(describeEvent(ev('INVESTIGATION_INCOMPLETE', { reason: 'The token budget was reached.' }))).toBe('Stopped early: The token budget was reached.')
  })

  it('never claims a cost it does not know', () => {
    expect(describeEvent(ev('USAGE', { inputTokens: 10, outputTokens: 5, costKnown: false }))).toContain('cost unknown')
  })

  it('degrades gracefully for event types it has never seen and for malformed payloads', () => {
    expect(describeEvent(ev('SOMETHING_NEW_FROM_A_NEWER_SERVER'))).toBe('something new from a newer server')
    expect(() => describeEvent(ev('STATE_CHANGED', { from: 5, to: null }))).not.toThrow()
  })
})
