import { Suspense, lazy, type ComponentProps } from 'react'

/**
 * Monaco, loaded only when an editor is actually rendered.
 *
 * <p>Monaco is the single largest thing the console ships — most of the bundle, for a component
 * that is not on screen until a method is selected. Importing it statically meant every page load
 * paid for it, including the ones that only read the service list or never got past the auth
 * panel.
 *
 * <p>`React.lazy` moves it into its own chunk that Vite fetches on first use. The fallback keeps
 * the layout from jumping while that happens: it occupies the same space the editor will.
 */
const MonacoEditor = lazy(async () => {
  const [{ default: Editor, loader }, monaco] = await Promise.all([
    import('@monaco-editor/react'),
    import('monaco-editor'),
  ])
  // Use the bundled copy instead of letting the loader fetch Monaco from cdn.jsdelivr.net at
  // runtime, which is what it does by default. A console that holds the gateway's credentials
  // should not pull its editor from a third party — and the CDN version simply does not load in
  // an air-gapped or egress-restricted deployment, which is exactly where an operator console
  // tends to run.
  loader.config({ monaco })
  return { default: Editor }
})

type EditorProps = ComponentProps<typeof MonacoEditor>

/** Same footprint as the editor, so nothing below it moves when Monaco arrives. */
function EditorSkeleton() {
  return (
    <div
      className="h-full w-full bg-slate-900 flex items-center justify-center"
      role="status"
      aria-live="polite"
    >
      <span className="text-xs text-slate-500">Loading editor…</span>
    </div>
  )
}

export function LazyEditor(props: EditorProps) {
  return (
    <Suspense fallback={<EditorSkeleton />}>
      <MonacoEditor {...props} />
    </Suspense>
  )
}
