import { LazyEditor } from './LazyEditor'
import { useGrpcUiStore } from '../store/useGrpcUiStore'

function StatusBadge({ status }: { status: string }) {
  const ok = status === 'OK'
  return (
    <span
      className={`px-2 py-0.5 rounded text-xs font-bold ${
        ok ? 'bg-emerald-900 text-emerald-300' : 'bg-red-900 text-red-300'
      }`}
    >
      {status}
    </span>
  )
}

function prettyJson(raw: string | null): string {
  if (!raw) return ''
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

/** The google.rpc.Status details of a failed call: one block per detail, headed by its @type. */
function ErrorDetails({ details }: { details: unknown[] }) {
  return (
    <div className="border-b border-slate-700 px-3 py-2 space-y-2 shrink-0 max-h-64 overflow-y-auto">
      {details.map((d, i) => {
        const type = typeof d === 'object' && d !== null ? String((d as Record<string, unknown>)['@type'] ?? '') : ''
        return (
          <div key={i}>
            <div className="text-[10px] uppercase tracking-wider text-red-400 mb-0.5">
              {type.replace(/^type\.googleapis\.com\//, '') || `detail ${i + 1}`}
            </div>
            <pre className="text-xs text-slate-300 bg-slate-800 rounded p-1 overflow-x-auto whitespace-pre-wrap break-all">
              {JSON.stringify(d, null, 2)}
            </pre>
          </div>
        )
      })}
    </div>
  )
}

export function ResponseViewer() {
  const { result, responseTab, setResponseTab } = useGrpcUiStore()
  const { status, response, grpcStatus, errorMessage, errorDetails, durationMs, streamChunks } = result

  if (status === 'idle') {
    return (
      <div className="flex items-center justify-center h-full text-slate-600 text-xs">
        Response will appear here
      </div>
    )
  }

  if (status === 'loading') {
    return (
      <div className="flex items-center justify-center h-full text-slate-500 text-xs animate-pulse">
        Sending…
      </div>
    )
  }

  if (status === 'streaming') {
    return (
      <div className="h-full overflow-y-auto p-2">
        <div className="flex items-center gap-2 mb-2">
          <span className="text-slate-400 text-xs">Streaming…</span>
          <span className="text-slate-500 text-xs">{streamChunks.length} chunks</span>
        </div>
        <div className="space-y-1">
          {streamChunks.map((chunk, i) => (
            <pre key={i} className="text-xs text-slate-300 bg-slate-800 rounded p-1 overflow-x-auto">
              {prettyJson(chunk)}
            </pre>
          ))}
        </div>
      </div>
    )
  }

  const prettyDisplay = streamChunks.length > 0
    ? streamChunks.map(prettyJson).join('\n')
    : prettyJson(response)
  const rawDisplay = streamChunks.length > 0
    ? streamChunks.join('\n')
    : (response ?? '')

  return (
    <div className="h-full flex flex-col">
      {/* Status + tab bar */}
      <div className="flex items-center gap-3 px-3 py-1.5 border-b border-slate-700 shrink-0">
        {grpcStatus && <StatusBadge status={grpcStatus} />}
        {durationMs != null && (
          <span className="text-slate-500 text-xs">{durationMs}ms</span>
        )}
        {errorMessage && (
          <span className="text-red-400 text-xs truncate">{errorMessage}</span>
        )}
        {streamChunks.length > 0 && (
          <span className="text-slate-500 text-xs">{streamChunks.length} chunks</span>
        )}
        <div className="flex-1" />
        {(['pretty', 'raw'] as const).map((tab) => (
          <button
            key={tab}
            onClick={() => setResponseTab(tab)}
            className={`px-2 py-0.5 text-[10px] uppercase tracking-wider transition-colors ${
              responseTab === tab
                ? 'text-blue-400 border-b border-blue-500'
                : 'text-slate-600 hover:text-slate-400'
            }`}
          >
            {tab}
          </button>
        ))}
      </div>

      {errorDetails && errorDetails.length > 0 && <ErrorDetails details={errorDetails} />}

      {responseTab === 'raw' ? (
        <div className="flex-1 overflow-y-auto p-2">
          <pre className="text-xs text-slate-300 font-mono whitespace-pre-wrap break-all select-all">
            {rawDisplay}
          </pre>
        </div>
      ) : streamChunks.length > 0 ? (
        <div className="flex-1 overflow-y-auto p-2 space-y-1">
          {streamChunks.map((chunk, i) => (
            <pre key={i} className="text-xs text-slate-300 bg-slate-800 rounded p-1 overflow-x-auto">
              {prettyJson(chunk)}
            </pre>
          ))}
        </div>
      ) : (
        <div className="flex-1 min-h-0">
          <LazyEditor
            height="100%"
            language="json"
            value={prettyDisplay}
            theme="vs-dark"
            options={{
              readOnly: true,
              minimap: { enabled: false },
              fontSize: 12,
              lineNumbers: 'off',
              scrollBeyondLastLine: false,
              wordWrap: 'on',
              folding: true,
            }}
          />
        </div>
      )}
    </div>
  )
}