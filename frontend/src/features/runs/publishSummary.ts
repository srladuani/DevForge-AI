/** What an approver sees for a publication: the plan as the server recorded it. Anything unreadable is shown as missing, never guessed. */
export interface PublishSummary {
  branch: string
  title: string
  draft: boolean
  host: string
  repositories: Array<{ repository: string; host: string; remote: string; base: string; baseSource: string; files: string[] }>
}

const HOSTS: Record<string, string> = { GITHUB: 'GitHub', BITBUCKET_CLOUD: 'Bitbucket Cloud' }

export function hostName(type: string): string {
  return HOSTS[type] ?? type
}

export function parsePublishSummary(s: string): PublishSummary | null {
  try {
    const v: unknown = JSON.parse(s)
    if (typeof v !== 'object' || v === null) return null
    const o = v as Record<string, unknown>
    const repos = Array.isArray(o.repositories) ? o.repositories : []
    const strs = (x: unknown) => (Array.isArray(x) ? x.filter((y): y is string => typeof y === 'string') : [])
    return {
      branch: typeof o.branch === 'string' ? o.branch : '',
      title: typeof o.title === 'string' ? o.title : '',
      draft: o.draft === true,
      host: typeof o.host === 'string' ? o.host : '',
      repositories: repos.flatMap((r: unknown) => {
        if (typeof r !== 'object' || r === null) return []
        const x = r as Record<string, unknown>
        return [{ repository: String(x.repository ?? ''), host: String(x.host ?? o.host ?? ''), remote: String(x.remote ?? ''), base: String(x.base ?? ''), baseSource: typeof x.baseSource === 'string' ? x.baseSource : '', files: strs(x.files) }]
      }),
    }
  } catch {
    return null
  }
}

/** Where a target branch came from, in words an approver can check against the project settings. */
export function baseSourceLabel(source: string): string {
  switch (source) {
    case 'REPOSITORY':
      return 'set for this repository'
    case 'PROJECT':
      return 'the project’s target branch'
    case 'HOST_DEFAULT':
      return 'nothing is configured, so the host’s default branch'
    default:
      return ''
  }
}
