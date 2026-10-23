/** One event as the backend sends it: {"seq":5,"type":"STATE_CHANGED","payload":{...},"createdAt":"..."}. */
export interface RunEvent {
  seq: number
  type: string
  payload: Record<string, unknown>
  createdAt: string
}

export interface SseFrame {
  id: string | undefined
  event: string | undefined
  data: string
}

function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v)
}

export function parseRunEvent(data: string): RunEvent | null {
  try {
    const v: unknown = JSON.parse(data)
    if (!isRecord(v) || typeof v.seq !== 'number' || typeof v.type !== 'string') return null
    return { seq: v.seq, type: v.type, payload: isRecord(v.payload) ? v.payload : {}, createdAt: typeof v.createdAt === 'string' ? v.createdAt : '' }
  } catch {
    return null
  }
}

/** Incremental server-sent-events parser. Feed it text chunks; it returns the complete frames seen so far. */
export class SseParser {
  private buffer = ''

  push(chunk: string): SseFrame[] {
    this.buffer += chunk.replace(/\r\n/g, '\n')
    const frames: SseFrame[] = []
    let sep = this.buffer.indexOf('\n\n')
    while (sep >= 0) {
      const raw = this.buffer.slice(0, sep)
      this.buffer = this.buffer.slice(sep + 2)
      const frame = this.parseFrame(raw)
      if (frame) frames.push(frame)
      sep = this.buffer.indexOf('\n\n')
    }
    return frames
  }

  private parseFrame(raw: string): SseFrame | null {
    let id: string | undefined
    let event: string | undefined
    const data: string[] = []
    for (const line of raw.split('\n')) {
      if (line === '' || line.startsWith(':')) continue // blank or keepalive comment
      const colon = line.indexOf(':')
      const field = colon < 0 ? line : line.slice(0, colon)
      const value = colon < 0 ? '' : line.slice(colon + 1).replace(/^ /, '')
      if (field === 'id') id = value
      else if (field === 'event') event = value
      else if (field === 'data') data.push(value)
    }
    return data.length === 0 && id === undefined && event === undefined ? null : { id, event, data: data.join('\n') }
  }
}

export interface StreamOptions {
  url: (afterSeq: number) => string
  headers: () => Record<string, string> | Promise<Record<string, string>>
  onEvent: (e: RunEvent) => void
  onStatus: (s: 'connecting' | 'live' | 'reconnecting' | 'closed') => void
  fetchImpl?: typeof fetch
  /** milliseconds to wait before reconnecting, per consecutive failure */
  backoff?: (failures: number) => number
}

/**
 * Follows a run's event stream until aborted or the server closes it (the server closes when the run is finished). Uses fetch rather than
 * EventSource so identity headers can be sent. On any drop it reconnects and resumes after the last sequence number it saw, so events are
 * neither lost nor repeated.
 */
export async function followEvents(opts: StreamOptions, signal: AbortSignal): Promise<void> {
  const doFetch = opts.fetchImpl ?? fetch
  const backoff = opts.backoff ?? ((n) => Math.min(1000 * 2 ** (n - 1), 15000))
  let last = 0
  let failures = 0
  while (!signal.aborted) {
    opts.onStatus(failures === 0 ? 'connecting' : 'reconnecting')
    try {
      const res = await doFetch(opts.url(last), { headers: { ...(await opts.headers()), Accept: 'text/event-stream' }, signal })
      if (res.status === 401 || res.status === 403 || res.status === 404) {
        opts.onStatus('closed')
        return // not allowed or gone: retrying cannot help
      }
      if (!res.ok || !res.body) throw new Error(`stream failed with HTTP ${res.status}`)
      failures = 0
      opts.onStatus('live')
      const reader = res.body.getReader()
      const decoder = new TextDecoder()
      const parser = new SseParser()
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        for (const frame of parser.push(decoder.decode(value, { stream: true }))) {
          const ev = parseRunEvent(frame.data)
          if (ev && ev.seq > last) {
            last = ev.seq
            opts.onEvent(ev)
          }
        }
      }
      opts.onStatus('closed') // server completed the stream: the run is finished
      return
    } catch (e) {
      if (signal.aborted) return
      failures++
      opts.onStatus('reconnecting')
      await new Promise<void>((resolve) => {
        const t = setTimeout(resolve, backoff(failures))
        signal.addEventListener('abort', () => { clearTimeout(t); resolve() }, { once: true })
      })
      void e
    }
  }
}
