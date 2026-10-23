import { useState, type FormEvent } from 'react'
import { Alert, Empty, Spinner } from '../../components/ui/Alert'
import { Badge } from '../../components/ui/Badge'
import { Button } from '../../components/ui/Button'
import { Card, CardHeader } from '../../components/ui/Card'
import { Dialog } from '../../components/ui/Dialog'
import { Input, Select, Textarea } from '../../components/ui/Field'
import { errorMessage } from '../../lib/api/client'
import { titleCase } from '../../lib/format'
import { useAdapterTypes } from '../connections/api'
import { AdminGate } from './AdminGate'
import { useCreateProviderInstance, useProviderInstances, useRemoveProviderInstance, useUpdateProviderInstance, type ModelPolicy, type ProviderInstance } from './api'

type Kind = ProviderInstance['kind']
const KINDS: Array<{ value: Kind; label: string }> = [
  { value: 'AI_PROVIDER', label: 'AI provider' },
  { value: 'GIT_HOST', label: 'Git host' },
  { value: 'TRACKER', label: 'Issue tracker' },
]
const AVAIL: Record<string, { label: string; tone: 'ok' | 'sim' | 'neutral' }> = {
  REAL: { label: 'Adapter available', tone: 'ok' },
  SIMULATED: { label: 'Simulated', tone: 'sim' },
  PLANNED: { label: 'Adapter planned', tone: 'neutral' },
}

export function ProvidersPage() {
  const { data, isPending, error } = useProviderInstances()
  const adapters = useAdapterTypes()
  const update = useUpdateProviderInstance()
  const remove = useRemoveProviderInstance()
  const [editing, setEditing] = useState<ProviderInstance | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  return (
    <div className="space-y-5">
      <div>
        <h1 className="text-lg font-semibold">Providers</h1>
        <p className="text-sm text-muted">
          Code defines which kinds of AI provider, Git host and issue tracker DevForge can talk to. Here you decide which are offered, where they point, and which models people may use — no deploy needed.
        </p>
      </div>
      <AdminGate error={error}>
        {isPending && <Spinner label="Loading providers" />}
        {notice && <Alert tone="info">{notice}</Alert>}
        {(update.error || remove.error) && <Alert tone="danger">{errorMessage(update.error ?? remove.error)}</Alert>}
        {data && data.length === 0 && <Empty title="No providers configured" />}
        <ul className="space-y-3">
          {data?.map((p) => {
            const a = AVAIL[p.availability] ?? { label: p.availability, tone: 'neutral' as const }
            return (
              <li key={p.id}>
                <Card>
                  <div className="flex flex-wrap items-start justify-between gap-3 p-4">
                    <div className="min-w-0">
                      <p className="font-medium">
                        {p.displayName} <span className="font-normal text-muted">· {titleCase(p.kind)} · {p.adapterType}</span>
                      </p>
                      <p className="mt-0.5 break-all font-mono text-xs text-muted">{p.baseUrl ?? 'default address'}</p>
                      <p className="mt-1 text-xs text-muted">
                        Models: {p.modelPolicy.allow.length ? `only ${p.modelPolicy.allow.length} allowed` : 'all known'}
                        {p.modelPolicy.deny.length ? `, ${p.modelPolicy.deny.length} hidden` : ''}
                        {p.modelPolicy.customModels.length ? `, ${p.modelPolicy.customModels.length} custom` : ''}
                      </p>
                    </div>
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone={a.tone}>{a.label}</Badge>
                      <label className="flex items-center gap-1.5 text-sm">
                        <input type="checkbox" checked={p.enabled} onChange={(e) => update.mutate({ id: p.id, body: { enabled: e.target.checked } })} aria-label={`${p.displayName} enabled`} />
                        Enabled
                      </label>
                      <Button onClick={() => setEditing(p)}>Edit</Button>
                      <Button
                        variant="ghost"
                        onClick={() =>
                          remove.mutate(p.id, {
                            onSuccess: (r) => setNotice(r.removed ? `${p.displayName} was removed.` : `${p.displayName} is still used by people's connections, so it was disabled instead of removed.`),
                          })
                        }
                      >
                        Remove
                      </Button>
                    </div>
                  </div>
                </Card>
              </li>
            )
          })}
        </ul>
        {adapters.data && <AddInstance types={adapters.data.map((t) => ({ kind: t.kind, type: t.type, name: t.displayName, defaultBaseUrl: t.defaultBaseUrl }))} />}
      </AdminGate>
      <EditInstance instance={editing} onClose={() => setEditing(null)} />
    </div>
  )
}

function AddInstance({ types }: { types: Array<{ kind: Kind; type: string; name: string; defaultBaseUrl: string | undefined }> }) {
  const create = useCreateProviderInstance()
  const [kind, setKind] = useState<Kind>('AI_PROVIDER')
  const options = types.filter((t) => t.kind === kind)
  const [type, setType] = useState('OPENAI_COMPATIBLE')
  const [name, setName] = useState('')
  const [url, setUrl] = useState('')
  const current = options.find((o) => o.type === type) ?? options[0]

  function submit(e: FormEvent) {
    e.preventDefault()
    if (!current) return
    create.mutate({ kind, adapterType: current.type, displayName: name.trim(), ...(url.trim() ? { baseUrl: url.trim() } : {}) }, { onSuccess: () => { setName(''); setUrl('') } })
  }

  return (
    <Card>
      <CardHeader title="Add a provider" subtitle="For example a second OpenAI-compatible gateway, or a self-hosted Git server." />
      <form onSubmit={submit} className="space-y-3 p-4">
        <div className="grid gap-3 sm:grid-cols-2">
          <Select
            label="Kind"
            value={kind}
            onChange={(e) => {
              const k = KINDS.find((x) => x.value === e.target.value)?.value ?? 'AI_PROVIDER'
              setKind(k)
              setType(types.find((t) => t.kind === k)?.type ?? '')
            }}
          >
            {KINDS.map((k) => (
              <option key={k.value} value={k.value}>
                {k.label}
              </option>
            ))}
          </Select>
          <Select label="Adapter type" value={current?.type ?? ''} onChange={(e) => setType(e.target.value)}>
            {options.map((o) => (
              <option key={o.type} value={o.type}>
                {o.name}
              </option>
            ))}
          </Select>
        </div>
        <div className="grid gap-3 sm:grid-cols-2">
          <Input label="Display name" value={name} onChange={(e) => setName(e.target.value)} required maxLength={80} />
          <Input label="Base URL (optional)" value={url} onChange={(e) => setUrl(e.target.value)} placeholder={current?.defaultBaseUrl ?? 'https://…'} className="font-mono" hint="http or https only, no embedded credentials." />
        </div>
        {create.error && <Alert tone="danger" title="Could not add the provider">{errorMessage(create.error)}</Alert>}
        <Button type="submit" variant="primary" busy={create.isPending} disabled={name.trim() === ''}>
          Add provider
        </Button>
      </form>
    </Card>
  )
}

interface CustomRow {
  id: string
  displayName: string
  tier: string
  contextWindow: string
  toolCalling: boolean
}

const lines = (s: string) => s.split('\n').map((x) => x.trim()).filter(Boolean)

function EditInstance({ instance, onClose }: { instance: ProviderInstance | null; onClose: () => void }) {
  return (
    <Dialog open={instance !== null} onClose={onClose} title={instance ? `Edit ${instance.displayName}` : 'Edit'}>
      {instance && <EditForm key={instance.id} instance={instance} onClose={onClose} />}
    </Dialog>
  )
}

function EditForm({ instance, onClose }: { instance: ProviderInstance; onClose: () => void }) {
  const update = useUpdateProviderInstance()
  const [name, setName] = useState(instance.displayName)
  const [url, setUrl] = useState(instance.baseUrl ?? '')
  const [allow, setAllow] = useState(instance.modelPolicy.allow.join('\n'))
  const [deny, setDeny] = useState(instance.modelPolicy.deny.join('\n'))
  const [custom, setCustom] = useState<CustomRow[]>(
    instance.modelPolicy.customModels.map((c) => ({ id: c.id, displayName: c.displayName ?? '', tier: c.tier, contextWindow: c.contextWindow?.toString() ?? '', toolCalling: c.toolCalling })),
  )
  const isAi = instance.kind === 'AI_PROVIDER'

  function submit(e: FormEvent) {
    e.preventDefault()
    const modelPolicy: ModelPolicy = {
      allow: lines(allow),
      deny: lines(deny),
      tierOverrides: instance.modelPolicy.tierOverrides,
      customModels: custom.filter((c) => c.id.trim() !== '').map((c) => ({ id: c.id.trim(), tier: c.tier, toolCalling: c.toolCalling, ...(c.displayName.trim() ? { displayName: c.displayName.trim() } : {}), ...(c.contextWindow ? { contextWindow: Number(c.contextWindow) } : {}) })),
    }
    update.mutate({ id: instance.id, body: { displayName: name.trim(), baseUrl: url.trim(), ...(isAi ? { modelPolicy } : {}) } }, { onSuccess: onClose })
  }

  return (
    <form onSubmit={submit} className="space-y-3">
      <Input label="Display name" value={name} onChange={(e) => setName(e.target.value)} required />
      <Input label="Base URL" value={url} onChange={(e) => setUrl(e.target.value)} className="font-mono" placeholder="default address" />
      {isAi && (
        <>
          <div className="grid gap-3 sm:grid-cols-2">
            <Textarea label="Only allow these models" value={allow} onChange={(e) => setAllow(e.target.value)} rows={3} hint="One model id per line. Empty means all known models." className="font-mono" />
            <Textarea label="Never offer these models" value={deny} onChange={(e) => setDeny(e.target.value)} rows={3} hint="One model id per line." className="font-mono" />
          </div>
          <fieldset className="space-y-2">
            <legend className="text-sm font-medium">Custom models</legend>
            <p className="text-xs text-muted">For models the built-in list does not know, for example behind a company gateway. Models without tool calling cannot run agents.</p>
            {custom.map((c, i) => (
              <div key={i} className="grid gap-2 rounded-md border border-border p-2 sm:grid-cols-[1fr_1fr_7rem_6rem_auto]">
                <Input label="Model id" value={c.id} onChange={(e) => setCustom(custom.map((x, j) => (j === i ? { ...x, id: e.target.value } : x)))} className="font-mono" />
                <Input label="Name" value={c.displayName} onChange={(e) => setCustom(custom.map((x, j) => (j === i ? { ...x, displayName: e.target.value } : x)))} />
                <Select label="Tier" value={c.tier} onChange={(e) => setCustom(custom.map((x, j) => (j === i ? { ...x, tier: e.target.value } : x)))}>
                  <option value="STRONG">Strong</option>
                  <option value="BALANCED">Balanced</option>
                  <option value="FAST">Fast</option>
                </Select>
                <Input label="Context" type="number" min={0} value={c.contextWindow} onChange={(e) => setCustom(custom.map((x, j) => (j === i ? { ...x, contextWindow: e.target.value } : x)))} />
                <div className="flex items-end gap-2 pb-1">
                  <label className="flex items-center gap-1 text-xs">
                    <input type="checkbox" checked={c.toolCalling} onChange={(e) => setCustom(custom.map((x, j) => (j === i ? { ...x, toolCalling: e.target.checked } : x)))} /> Tools
                  </label>
                  <Button variant="ghost" onClick={() => setCustom(custom.filter((_, j) => j !== i))} aria-label={`Remove custom model ${i + 1}`}>
                    ✕
                  </Button>
                </div>
              </div>
            ))}
            <Button onClick={() => setCustom([...custom, { id: '', displayName: '', tier: 'BALANCED', contextWindow: '', toolCalling: true }])}>Add a custom model</Button>
          </fieldset>
        </>
      )}
      {update.error && <Alert tone="danger" title="Could not save">{errorMessage(update.error)}</Alert>}
      <div className="flex justify-end gap-2">
        <Button onClick={onClose}>Cancel</Button>
        <Button type="submit" variant="primary" busy={update.isPending}>
          Save
        </Button>
      </div>
    </form>
  )
}
