import type { MethodType } from '../types/schema'

const CONFIG: Record<MethodType, { label: string; className: string }> = {
  UNARY: { label: 'U', className: 'bg-blue-900 text-blue-300 border-blue-700' },
  SERVER_STREAMING: { label: 'SS', className: 'bg-purple-900 text-purple-300 border-purple-700' },
  CLIENT_STREAMING: { label: 'CS', className: 'bg-orange-900 text-orange-300 border-orange-700' },
  BIDI_STREAMING: { label: 'BD', className: 'bg-rose-900 text-rose-300 border-rose-700' },
}

export function MethodBadge({ type }: { type: MethodType }) {
  const { label, className } = CONFIG[type]
  return (
    <span
      className={`inline-flex items-center justify-center w-6 h-4 text-[9px] font-bold border rounded shrink-0 ${className}`}
      title={type.replace('_', ' ')}
    >
      {label}
    </span>
  )
}