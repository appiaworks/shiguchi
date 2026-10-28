import { LazyEditor } from './LazyEditor'
import { useRef, useState } from 'react'
import { toCurl, toGrpcurl } from '../api/exportCommand'
import { invokeClientStream, invokeStream, invokeUnary } from '../api/grpcUiApi'
import { useKeyboardShortcuts } from '../hooks/useKeyboardShortcuts'
import { useGrpcUiStore } from '../store/useGrpcUiStore'
import type { StreamError } from '../types/schema'
import { AuthBar } from './AuthBar'
import { FormEditor } from './FormEditor'
import { MetadataEditor } from './MetadataEditor'
import { MethodBadge } from './MethodBadge'
import { ResponseViewer } from './ResponseViewer'
import { SchemaDoc } from './SchemaDoc'
import { Panel, Group, Separator } from 'react-resizable-panels'

export function InvokePanel() {
  const {
    selectedService,
    selectedMethod,
    authToken,
    activeProviderId,
    globalAuthToken,
    globalProviderId,
    links,
    payload,
    extraPayloads,
    formValues,
    requestMode,
    metadataEntries,
    result,
    history,
    setPayload,
    setExtraPayloads,
    addExtraPayload,
    removeExtraPayload,
    setFormValues,
    setRequestMode,
    setResult,
    appendChunk,
    addHistoryEntry,
    restoreHistory,
  } = useGrpcUiStore()

  const abortRef = useRef<AbortController | null>(null)
  const [showHistory, setShowHistory] = useState(false)
  const [copied, setCopied] = useState<'curl' | 'grpcurl' | null>(null)

  // Ctrl/Cmd+Enter sends. Registered here rather than on the editor so it works wherever focus is
  // — including the metadata rows and the payload editor, which is where it is usually needed.
  //
  // Above the early return below: a hook after a conditional return is called inconsistently
  // between renders, which is the bug the rules of hooks exist to prevent. handleSend guards on
  // its own for the no-selection case.
  useKeyboardShortcuts({ onSend: () => void handleSend() })

  if (!selectedMethod || !selectedService) {
    return (
      <div className="flex items-center justify-center h-full text-slate-600 text-sm">
        Select a method from the tree
      </div>
    )
  }

  const isStreaming = selectedMethod.methodType === 'SERVER_STREAMING'
  // BIDI is not offered: one HTTP request cannot carry an interleaved conversation, and the
  // gateway does not expose those on REST either.
  const isClientStreaming = selectedMethod.methodType === 'CLIENT_STREAMING'
  const busy = result.status === 'loading' || result.status === 'streaming'


  /** Puts the request on the clipboard as a shell command. */
  async function copyCommand(kind: 'curl' | 'grpcurl') {
    if (!selectedService || !selectedMethod) return
    const request = {
      origin: window.location.origin,
      serviceName: selectedService.name,
      methodName: selectedMethod.name,
      payload,
      metadataEntries,
      target: selectedService.target,
      httpMethod: selectedMethod.httpMethod ?? null,
      httpPath: selectedMethod.httpPath ?? null,
    }
    const command = kind === 'curl' ? toCurl(request) : toGrpcurl(request)
    try {
      await navigator.clipboard.writeText(command)
      setCopied(kind)
      setTimeout(() => setCopied(null), 1500)
    } catch {
      // Clipboard access needs a secure context; a console served over plain HTTP has none.
      window.prompt('Copy the command:', command)
    }
  }
  const authProviders = links?.authProviders ?? []

  // Resolve effective auth: null activeProviderId means "inherit global"
  const isInherit = activeProviderId === null
  const effectiveToken = isInherit ? globalAuthToken : authToken
  const effectiveProviderId = isInherit
    ? globalProviderId
    : activeProviderId === '__manual__' ? null : activeProviderId

  function switchToMode(mode: 'json' | 'form') {
    if (mode === requestMode) return
    if (mode === 'form') {
      try {
        setFormValues(JSON.parse(payload || '{}') as Record<string, unknown>)
      } catch {
        setFormValues({})
      }
    } else {
      const json = JSON.stringify(formValues, null, 2)
      setPayload(json && json !== '{}' ? json : '{\n  \n}')
    }
    setRequestMode(mode)
  }

  function effectivePayload(): string {
    if (requestMode === 'form') return JSON.stringify(formValues) || '{}'
    return payload
  }

  async function handleSend() {
    if (!selectedMethod || !selectedService) return
    if (busy) {
      abortRef.current?.abort()
      setResult({ status: 'idle' })
      return
    }

    const pld = effectivePayload()
    setResult({ status: 'loading', response: null, grpcStatus: null, errorMessage: null, errorDetails: null, durationMs: null, streamChunks: [] })

    if (isStreaming) {
      const ctrl = new AbortController()
      abortRef.current = ctrl
      setResult({ status: 'streaming' })
      let failed = false
      try {
        for await (const event of invokeStream(
          selectedMethod.fullServiceName, selectedMethod.name, pld, effectiveToken, ctrl.signal,
          effectiveProviderId, authProviders, metadataEntries,
        )) {
          if (event.event === 'data') {
            appendChunk(event.data)
          } else if (event.event === 'error') {
            // The backend failed mid-stream: keep the chunks received so far, show the status.
            failed = true
            let err: StreamError = { code: 'INTERNAL', message: event.data }
            try {
              err = JSON.parse(event.data) as StreamError
            } catch {
              // not JSON: shown verbatim
            }
            setResult({
              status: 'error',
              grpcStatus: err.code,
              errorMessage: err.message,
              errorDetails: err.details && err.details.length > 0 ? err.details : null,
            })
            addHistoryEntry({
              id: crypto.randomUUID(),
              serviceName: selectedMethod.fullServiceName,
              methodName: selectedMethod.name,
              configName: selectedService.configName,
              payload: pld,
              metadataEntries: [...metadataEntries],
              timestamp: Date.now(),
              grpcStatus: err.code,
            })
          } else if (event.event === 'done') {
            if (failed) {
              try {
                const meta = JSON.parse(event.data) as { durationMs?: number }
                setResult({ durationMs: meta.durationMs ?? null })
              } catch {
                // duration is cosmetic
              }
              continue
            }
            try {
              const meta = JSON.parse(event.data) as { durationMs?: number }
              const durationMs = meta.durationMs ?? null
              setResult({ status: 'success', grpcStatus: 'OK', durationMs })
              addHistoryEntry({
                id: crypto.randomUUID(),
                serviceName: selectedMethod.fullServiceName,
                methodName: selectedMethod.name,
                configName: selectedService.configName,
                payload: pld,
                metadataEntries: [...metadataEntries],
                timestamp: Date.now(),
                grpcStatus: 'OK',
              })
            } catch {
              setResult({ status: 'success', grpcStatus: 'OK' })
            }
          }
        }
      } catch (e) {
        if ((e as Error).name !== 'AbortError') setResult({ status: 'error', errorMessage: String(e) })
      }
    } else {
      try {
        const resp = isClientStreaming
          ? await invokeClientStream(
              selectedMethod.fullServiceName, selectedMethod.name, [pld, ...extraPayloads], effectiveToken,
              effectiveProviderId, authProviders, metadataEntries,
            )
          : await invokeUnary(
              selectedMethod.fullServiceName, selectedMethod.name, pld, effectiveToken,
              effectiveProviderId, authProviders, metadataEntries,
            )
        setResult({
          status: resp.grpcStatus === 'OK' ? 'success' : 'error',
          response: resp.response,
          grpcStatus: resp.grpcStatus,
          errorMessage: resp.errorMessage,
          errorDetails: resp.errorDetails && resp.errorDetails.length > 0 ? resp.errorDetails : null,
          durationMs: resp.durationMs,
        })
        addHistoryEntry({
          id: crypto.randomUUID(),
          serviceName: selectedMethod.fullServiceName,
          methodName: selectedMethod.name,
          configName: selectedService.configName,
          payload: pld,
          metadataEntries: [...metadataEntries],
          timestamp: Date.now(),
          grpcStatus: resp.grpcStatus,
        })
      } catch (e) {
        setResult({ status: 'error', errorMessage: String(e), grpcStatus: 'INTERNAL' })
      }
    }
  }

  const shortSvc = selectedMethod.fullServiceName.split('.').pop() ?? selectedMethod.fullServiceName
  const relevantHistory = history.filter(
    (h) => h.serviceName === selectedMethod.fullServiceName && h.methodName === selectedMethod.name,
  )

  return (
    <Group orientation="vertical" className="h-full">
      {/* Request panel */}
      <Panel defaultSize={55} minSize={30}>
        <div className="h-full flex flex-col">
          {/* Method header */}
          <div className="flex items-center gap-2 px-3 py-1.5 border-b border-slate-700 shrink-0 bg-slate-900">
            <MethodBadge type={selectedMethod.methodType} />
            <span className="text-slate-400 text-xs">{shortSvc}</span>
            <span className="text-slate-600">/</span>
            <span className="text-white font-semibold text-xs">{selectedMethod.name}</span>
            {selectedMethod.httpMethod && (
              <>
                <span className="ml-2 text-slate-600 text-[10px]">{selectedMethod.httpMethod}</span>
                <span className="text-slate-500 text-[10px]">{selectedMethod.httpPath}</span>
              </>
            )}
            <div className="flex-1" />
            {relevantHistory.length > 0 && (
              <div className="relative">
                <button
                  onClick={() => setShowHistory((v) => !v)}
                  className="text-[10px] text-slate-600 hover:text-slate-300 px-1.5 py-0.5 rounded border border-slate-700"
                >
                  History ({relevantHistory.length})
                </button>
                {showHistory && (
                  <div className="absolute right-0 top-full mt-1 z-50 w-72 bg-slate-900 border border-slate-700 rounded shadow-xl">
                    <div className="px-2 py-1 text-[9px] text-slate-600 uppercase tracking-wider border-b border-slate-800">
                      Recent · {selectedMethod.name}
                    </div>
                    <div className="max-h-64 overflow-y-auto">
                      {relevantHistory.map((entry) => (
                        <button
                          key={entry.id}
                          onClick={() => { restoreHistory(entry); setShowHistory(false) }}
                          className="w-full flex items-center gap-2 px-2 py-1.5 hover:bg-slate-800 text-left border-b border-slate-800 last:border-0"
                        >
                          <div className="flex-1 min-w-0">
                            <div className="text-[10px] text-slate-400 truncate font-mono">
                              {entry.payload.length > 40
                                ? entry.payload.slice(0, 40) + '…'
                                : entry.payload}
                            </div>
                            <div className="text-[9px] text-slate-600">
                              {new Date(entry.timestamp).toLocaleTimeString()}
                              {entry.metadataEntries.length > 0 && ` · ${entry.metadataEntries.length} meta`}
                            </div>
                          </div>
                          {entry.grpcStatus && (
                            <span className={`text-[9px] px-1 rounded shrink-0 ${
                              entry.grpcStatus === 'OK'
                                ? 'bg-emerald-900 text-emerald-400'
                                : 'bg-red-900 text-red-400'
                            }`}>
                              {entry.grpcStatus}
                            </span>
                          )}
                        </button>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            )}
          </div>

          {/* Auth */}
          <AuthBar />

          {/* Metadata */}
          <MetadataEditor />

          {/* Request body + schema side-by-side */}
          <Group orientation="horizontal" className="flex-1 min-h-0">
            <Panel defaultSize={65} minSize={40}>
              <div className="h-full flex flex-col">
                {/* Tab bar */}
                <div className="flex items-center border-b border-slate-700 shrink-0">
                  {(['json', 'form'] as const).map((tab) => (
                    <button
                      key={tab}
                      onClick={() => switchToMode(tab)}
                      className={`px-3 py-1 text-[10px] uppercase tracking-wider transition-colors ${
                        requestMode === tab
                          ? 'text-blue-400 border-b-2 border-blue-500'
                          : 'text-slate-600 hover:text-slate-400'
                      }`}
                    >
                      {tab === 'json' ? 'Request JSON' : 'Form'}
                    </button>
                  ))}
                </div>
                <div className="flex-1 min-h-0">
                  {requestMode === 'json' ? (
                    <LazyEditor
                      height="100%"
                      language="json"
                      value={payload}
                      onChange={(v) => setPayload(v ?? '')}
                      theme="vs-dark"
                      options={{
                        minimap: { enabled: false },
                        fontSize: 12,
                        lineNumbers: 'off',
                        scrollBeyondLastLine: false,
                        wordWrap: 'on',
                        tabSize: 2,
                      }}
                    />
                  ) : (
                    <FormEditor
                      schema={selectedMethod.inputSchema}
                      values={formValues}
                      onChange={setFormValues}
                    />
                  )}
                </div>

                {isClientStreaming && (
                  <div className="border-t border-slate-700 shrink-0 max-h-56 overflow-y-auto">
                    <div className="px-3 py-1 flex items-center justify-between text-[10px] text-slate-600">
                      <span>
                        MESSAGE STREAM ({extraPayloads.length + 1} message
                        {extraPayloads.length === 0 ? '' : 's'})
                      </span>
                      <button
                        type="button"
                        onClick={addExtraPayload}
                        className="px-2 py-0.5 rounded border border-slate-600 text-slate-300 hover:bg-slate-700"
                      >
                        + message
                      </button>
                    </div>
                    {extraPayloads.map((extra, i) => (
                      <div key={i} className="px-3 pb-2">
                        <div className="flex items-center justify-between text-[10px] text-slate-600 pb-0.5">
                          <span>#{i + 2}</span>
                          <button
                            type="button"
                            onClick={() => removeExtraPayload(i)}
                            className="text-slate-500 hover:text-red-400"
                            aria-label={`Remove message ${i + 2}`}
                          >
                            remove
                          </button>
                        </div>
                        <textarea
                          value={extra}
                          onChange={(e) =>
                            setExtraPayloads(extraPayloads.map((p, j) => (j === i ? e.target.value : p)))
                          }
                          spellCheck={false}
                          rows={3}
                          className="w-full bg-slate-900 border border-slate-700 rounded px-2 py-1 font-mono text-xs text-slate-200"
                        />
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </Panel>

            <Separator className="w-px bg-slate-700 hover:bg-blue-600 transition-colors" />

            <Panel defaultSize={35} minSize={20}>
              <div className="h-full flex flex-col">
                <div className="px-3 py-1 text-[10px] text-slate-600 border-b border-slate-700 shrink-0">
                  INPUT SCHEMA
                </div>
                <div className="flex-1 min-h-0">
                  <SchemaDoc schema={selectedMethod.inputSchema} />
                </div>
              </div>
            </Panel>
          </Group>

          {/* Send button */}
          <div className="flex items-center gap-3 px-3 py-2 border-t border-slate-700 shrink-0">
            <button
              onClick={handleSend}
              aria-keyshortcuts="Control+Enter Meta+Enter"
              title={busy ? 'Cancel the in-flight call' : 'Send (Ctrl/Cmd+Enter)'}
              className={`px-4 py-1.5 rounded text-xs font-semibold transition-colors ${
                busy
                  ? 'bg-red-800 hover:bg-red-700 text-red-200'
                  : 'bg-blue-700 hover:bg-blue-600 text-white'
              }`}
            >
              {busy ? 'Cancel' : isStreaming ? 'Stream' : 'Send'}
            </button>
            <span className="text-slate-600 text-[10px]">
              {selectedMethod.methodType.replace('_', ' ')}
            </span>

            <div className="flex-1" />

            {/* Reproduce this call outside the console: curl goes through the gateway, grpcurl
                goes straight to the backend. Neither carries the token. */}
            <button
              onClick={() => void copyCommand('curl')}
              title="Copy a curl command for this request (through the gateway)"
              className="text-[10px] text-slate-500 hover:text-slate-200 px-1.5 py-0.5 rounded border border-slate-700 hover:border-slate-500 transition-colors"
            >
              {copied === 'curl' ? 'Copied ✓' : 'Copy as curl'}
            </button>
            <button
              onClick={() => void copyCommand('grpcurl')}
              title="Copy a grpcurl command for this request (straight to the backend)"
              className="text-[10px] text-slate-500 hover:text-slate-200 px-1.5 py-0.5 rounded border border-slate-700 hover:border-slate-500 transition-colors"
            >
              {copied === 'grpcurl' ? 'Copied ✓' : 'Copy as grpcurl'}
            </button>
          </div>
        </div>
      </Panel>

      <Separator className="h-px bg-slate-700 hover:bg-blue-600 transition-colors" />

      {/* Response panel */}
      <Panel defaultSize={45} minSize={20}>
        <div className="h-full flex flex-col">
          <div className="px-3 py-1 text-[10px] text-slate-600 border-b border-slate-700 shrink-0">
            RESPONSE
          </div>
          <div className="flex-1 min-h-0">
            <ResponseViewer />
          </div>
        </div>
      </Panel>
    </Group>
  )
}
