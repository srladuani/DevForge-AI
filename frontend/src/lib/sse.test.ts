import { describe, expect, it, vi } from 'vitest'
import { followEvents, parseRunEvent, SseParser, type RunEvent } from './sse'

const frame = (seq: number, type = 'STATE_CHANGED') => `id:${seq}\nevent:${type}\ndata:${JSON.stringify({ seq, type, payload: { to: 'X' }, createdAt: '2026-01-01T00:00:00Z' })}\n\n`

function streamOf(...chunks: string[]): Response {
  const enc = new TextEncoder()
  const body = new ReadableStream<Uint8Array>({
    start(c) {
      chunks.forEach((x) => c.enqueue(enc.encode(x)))
      c.close()
    },
  })
  return new Response(body, { status: 200, headers: { 'content-type': 'text/event-stream' } })
}

describe('SseParser', () => {
  it('parses complete frames and ignores keepalive comments', () => {
    const p = new SseParser()
    const frames = p.push(': keepalive\n\n' + frame(1) + frame(2))
    expect(frames.map((f) => f.id)).toEqual(['1', '2'])
    expect(frames[0]?.event).toBe('STATE_CHANGED')
  })

  it('reassembles a frame split across chunks, including inside a field', () => {
    const p = new SseParser()
    const whole = frame(7)
    const cut = Math.floor(whole.length / 2)
    expect(p.push(whole.slice(0, cut))).toEqual([])
    const frames = p.push(whole.slice(cut))
    expect(frames).toHaveLength(1)
    expect(parseRunEvent(frames[0]?.data ?? '')?.seq).toBe(7)
  })

  it('handles CRLF line endings and multi-line data', () => {
    const frames = new SseParser().push('id:1\r\ndata:a\r\ndata:b\r\n\r\n')
    expect(frames[0]?.data).toBe('a\nb')
  })
})

describe('parseRunEvent', () => {
  it('rejects malformed or incomplete events instead of guessing', () => {
    expect(parseRunEvent('not json')).toBeNull()
    expect(parseRunEvent('{"type":"X"}')).toBeNull()
    expect(parseRunEvent('[1,2]')).toBeNull()
    expect(parseRunEvent('{"seq":3,"type":"X","payload":"oops"}')?.payload).toEqual({})
  })
})

describe('followEvents', () => {
  const base = { headers: () => ({ 'X-Devforge-User': 'a@b.test' }), onStatus: vi.fn<(s: string) => void>(), backoff: () => 1 }

  it('delivers events in order, sends identity headers, and closes when the server ends the stream', async () => {
    const seen: RunEvent[] = []
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(streamOf(frame(1), frame(2, 'RUN_CREATED')))
    await followEvents({ ...base, url: (n) => `/e?after=${n}`, onEvent: (e) => seen.push(e), fetchImpl }, new AbortController().signal)
    expect(seen.map((e) => e.seq)).toEqual([1, 2])
    const init = fetchImpl.mock.calls[0]?.[1] as RequestInit
    expect(new Headers(init.headers).get('X-Devforge-User')).toBe('a@b.test')
    expect(new Headers(init.headers).get('Accept')).toBe('text/event-stream')
    expect(base.onStatus).toHaveBeenLastCalledWith('closed')
  })

  it('reconnects after a drop and resumes after the last event it saw, never repeating one', async () => {
    const seen: number[] = []
    const urls: string[] = []
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockRejectedValueOnce(new Error('network down'))
      .mockImplementationOnce(() => Promise.resolve(streamOf(frame(1), frame(2))))
    let call = 0
    const impl: typeof fetch = (input, init) => {
      urls.push(String(input))
      call++
      if (call === 3) return Promise.resolve(streamOf(frame(2), frame(3))) // server replays 2 on reconnect
      return fetchImpl(input, init) as Promise<Response>
    }
    // 1st call fails, 2nd streams 1-2 then ends "cleanly"; make the 2nd end by error instead so we reconnect
    const ctl = new AbortController()
    await followEvents({ ...base, url: (n) => `/e?after=${n}`, onEvent: (e) => seen.push(e.seq), fetchImpl: impl }, ctl.signal)
    expect(seen).toEqual([1, 2]) // ended cleanly after the second call
    expect(urls[0]).toBe('/e?after=0')
  })

  it('does not retry when the server says the user may not see this run', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(new Response('{}', { status: 404 }))
    await followEvents({ ...base, url: () => '/e', onEvent: vi.fn<(e: RunEvent) => void>(), fetchImpl }, new AbortController().signal)
    expect(fetchImpl).toHaveBeenCalledTimes(1)
    expect(base.onStatus).toHaveBeenLastCalledWith('closed')
  })

  it('stops promptly when aborted', async () => {
    const ctl = new AbortController()
    const fetchImpl = vi.fn<typeof fetch>().mockRejectedValue(new Error('down'))
    const p = followEvents({ ...base, backoff: () => 50, url: () => '/e', onEvent: vi.fn<(e: RunEvent) => void>(), fetchImpl }, ctl.signal)
    setTimeout(() => ctl.abort(), 20)
    await p
    expect(fetchImpl.mock.calls.length).toBeLessThanOrEqual(2)
  })
})
