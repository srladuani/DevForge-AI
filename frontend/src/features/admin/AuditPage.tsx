import { useState } from 'react'
import { Spinner } from '../../components/ui/Alert'
import { Select } from '../../components/ui/Field'
import { AdminGate } from './AdminGate'
import { useAuditEvents } from './api'

export function AuditPage() {
  const [limit, setLimit] = useState(100)
  const { data, isPending, error } = useAuditEvents(limit)
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-lg font-semibold">Audit log</h1>
          <p className="text-sm text-muted">An append-only record of who did what. Secrets are never recorded.</p>
        </div>
        <div className="w-40">
          <Select label="Show latest" value={limit} onChange={(e) => setLimit(Number(e.target.value))}>
            {[50, 100, 500].map((n) => (
              <option key={n} value={n}>
                {n}
              </option>
            ))}
          </Select>
        </div>
      </div>
      <AdminGate error={error}>
        {isPending && <Spinner label="Loading audit log" />}
        {data && (
          <div className="overflow-x-auto rounded-lg border border-border bg-surface">
            <table className="w-full text-left text-sm">
              <caption className="sr-only">Audit events, newest first</caption>
              <thead className="border-b border-border text-xs uppercase tracking-wide text-muted">
                <tr>
                  <th scope="col" className="px-3 py-2">When</th>
                  <th scope="col" className="px-3 py-2">Who</th>
                  <th scope="col" className="px-3 py-2">What</th>
                  <th scope="col" className="px-3 py-2">On</th>
                  <th scope="col" className="px-3 py-2">Details</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {data.map((e) => (
                  <tr key={e.id}>
                    <td className="whitespace-nowrap px-3 py-2 text-xs text-muted">{new Date(e.ts).toLocaleString()}</td>
                    <td className="max-w-40 truncate px-3 py-2 font-mono text-xs" title={e.actor}>{e.actor}</td>
                    <td className="px-3 py-2 font-medium">{e.action}</td>
                    <td className="px-3 py-2 font-mono text-xs">{e.targetType ? `${e.targetType} ${e.targetId?.slice(0, 8) ?? ''}` : ''}</td>
                    <td className="px-3 py-2 text-xs text-muted">{e.details}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </AdminGate>
    </div>
  )
}
