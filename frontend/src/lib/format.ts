export function shortSha(sha: string | undefined | null): string {
  return sha ? sha.slice(0, 9) : ''
}

export function shortHash(hash: string): string {
  return hash.replace(/^sha256:/, '').slice(0, 10)
}

export function timeAgo(iso: string | undefined, now: number = Date.now()): string {
  if (!iso) return ''
  const s = Math.max(0, Math.round((now - new Date(iso).getTime()) / 1000))
  if (s < 5) return 'just now'
  if (s < 60) return `${s}s ago`
  if (s < 3600) return `${Math.floor(s / 60)}m ago`
  if (s < 86400) return `${Math.floor(s / 3600)}h ago`
  return `${Math.floor(s / 86400)}d ago`
}

export function titleCase(s: string): string {
  return s.toLowerCase().split('_').map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join(' ')
}

const MODES: Record<string, string> = { INVESTIGATE_ONLY: 'Investigate only', PROPOSE_FIX: 'Investigate and propose a fix', FIX_AND_PATCH: 'Investigate, fix and prepare a patch' }

export function modeLabel(mode: string): string {
  return MODES[mode] ?? titleCase(mode)
}

export function formatTokens(n: number | undefined): string {
  if (n === undefined) return '–'
  return n >= 1000 ? `${(n / 1000).toFixed(1)}k` : String(n)
}
