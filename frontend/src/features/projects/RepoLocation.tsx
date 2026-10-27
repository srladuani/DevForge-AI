import { Input, Select } from '../../components/ui/Field'
import type { Schemas } from '../../lib/api/client'

export type RepoSource = 'URL' | 'PATH'

/** What to send to add a repository: its address (cloned on the server with the person's own connection) or a path on the server. */
export function locationBody(source: RepoSource, value: string): Pick<Schemas['AddRepositoryRequest'], 'cloneUrl' | 'path'> {
  return source === 'URL' ? { cloneUrl: value.trim() } : { path: value.trim() }
}

export function RepoLocationInput({ source, value, localPaths, disabled, onSource, onValue }: { source: RepoSource; value: string; localPaths: boolean; disabled?: boolean; onSource: (s: RepoSource) => void; onValue: (v: string) => void }) {
  const mode = localPaths ? source : 'URL'
  return (
    <div className="grid gap-3 sm:grid-cols-[11rem_1fr]">
      {localPaths ? (
        <Select label="Add it by" value={mode} onChange={(e) => onSource(e.target.value === 'PATH' ? 'PATH' : 'URL')} disabled={disabled}>
          <option value="URL">Repository URL</option>
          <option value="PATH">Path on the server</option>
        </Select>
      ) : (
        <div className="text-sm">
          <p className="mb-1 font-medium">Add it by</p>
          <p className="py-2 text-muted">Repository URL</p>
        </div>
      )}
      {mode === 'URL' ? (
        <Input
          label="Repository URL"
          value={value}
          onChange={(e) => onValue(e.target.value)}
          placeholder="https://bitbucket.org/workspace/my-service.git"
          disabled={disabled}
          className="font-mono"
          hint="DevForge clones it with your own connection for that repository (add it under My connections first). Do not put a token in the address."
        />
      ) : (
        <Input label="Path on the server" value={value} onChange={(e) => onValue(e.target.value)} placeholder="/srv/repos/my-service" disabled={disabled} className="font-mono" hint="A checkout that already exists on the machine running DevForge." />
      )}
    </div>
  )
}
