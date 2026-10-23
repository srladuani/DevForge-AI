import { useEffect } from 'react'
import { Link } from 'react-router'
import { Alert } from '../../components/ui/Alert'
import { Select } from '../../components/ui/Field'
import { titleCase } from '../../lib/format'
import type { ModelInfo, ProviderOption } from '../connections/api'

export interface AgentChoice {
  provider: string
  model: string
  fastModel: string
}

function modelLabel(m: ModelInfo): string {
  const bits = [m.displayName, titleCase(m.tier)]
  if (!m.available) bits.push(m.unavailableReason ?? 'unavailable')
  return bits.join(' · ')
}

/** Provider first, then a model of that provider. Providers that cannot be used stay visible with the reason, never silently hidden. */
export function AgentPicker({ providers, value, onChange }: { providers: ProviderOption[]; value: AgentChoice; onChange: (v: AgentChoice) => void }) {
  const selectable = providers.filter((p) => p.selectable)
  const current = providers.find((p) => p.provider === value.provider)

  // Keep the selection valid as the provider list changes: fall back to the first usable provider and its balanced model.
  const first = selectable[0]
  const fallbackProvider = first?.provider
  const fallbackModel = first ? defaultModel(first) : ''
  const needsDefault = !current?.selectable && fallbackProvider !== undefined
  useEffect(() => {
    if (needsDefault && fallbackProvider) onChange({ provider: fallbackProvider, model: fallbackModel, fastModel: '' })
  }, [needsDefault, fallbackProvider, fallbackModel, onChange])

  if (providers.length > 0 && selectable.length === 0) {
    return (
      <Alert tone="warn" title="No AI provider is ready to use">
        Runs use your own AI credentials. Add and verify a key under{' '}
        <Link className="underline" to="/connections">
          My connections
        </Link>
        , then come back.
        <ul className="mt-2 list-disc pl-5 text-xs text-muted">
          {providers.map((p) => (
            <li key={p.instanceId}>
              {p.displayName}: {p.unavailableReason}
            </li>
          ))}
        </ul>
      </Alert>
    )
  }

  const models = current?.models.filter((m) => m.available) ?? []
  const simulated = current?.availability === 'SIMULATED'
  return (
    <div className="space-y-3">
      <div className="grid gap-3 sm:grid-cols-2">
        <Select
          label="AI provider"
          value={value.provider}
          onChange={(e) => {
            const p = providers.find((x) => x.provider === e.target.value)
            if (p) onChange({ provider: p.provider, model: defaultModel(p), fastModel: '' })
          }}
          hint="Uses your own credentials for this provider."
        >
          {providers.map((p) => (
            <option key={p.instanceId} value={p.provider} disabled={!p.selectable}>
              {p.displayName}
              {!p.selectable && ` — ${p.unavailableReason ?? 'unavailable'}`}
            </option>
          ))}
        </Select>
        <Select label="Model" value={value.model} onChange={(e) => onChange({ ...value, model: e.target.value })} hint="Larger models are slower and cost more.">
          {current?.models.map((m) => (
            <option key={m.id} value={m.id} disabled={!m.available}>
              {modelLabel(m)}
            </option>
          ))}
        </Select>
      </div>
      {models.length > 1 && (
        <Select label="Faster model for light steps (optional)" value={value.fastModel} onChange={(e) => onChange({ ...value, fastModel: e.target.value })}>
          <option value="">Use the main model for every step</option>
          {models.map((m) => (
            <option key={m.id} value={m.id}>
              {modelLabel(m)}
            </option>
          ))}
        </Select>
      )}
      {current?.availability === 'REAL' && (
        <Alert tone="info" title="Real model">
          This run calls {current.displayName} with your own key and uses your account's credit. Token usage is shown on the run. For now a real model can investigate only; fix proposals and patches come in a later milestone.
        </Alert>
      )}
      {simulated && (
        <Alert tone="sim" title="Simulation">
          In this build the provider adapter is simulated: no model is called, your key is not sent anywhere, and every analysis sentence is marked [SIMULATED]. Evidence comes from real searches of your repositories.
        </Alert>
      )}
    </div>
  )
}

function defaultModel(p: ProviderOption): string {
  const usable = p.models.filter((m) => m.available)
  return (usable.find((m) => m.tier === 'BALANCED') ?? usable[0])?.id ?? ''
}
