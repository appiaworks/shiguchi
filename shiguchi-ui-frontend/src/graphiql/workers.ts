// Monaco starts its language workers itself unless told how. The default is a `blob:` worker,
// which the console's strict CSP (no `blob:` in script-src) refuses. These imports make Vite
// emit each worker as a real same-origin file, which `default-src 'self'` allows.
import EditorWorker from 'monaco-editor/editor/editor.worker.js?worker'
import JsonWorker from 'monaco-editor/language/json/json.worker.js?worker'
import GraphQLWorker from 'monaco-graphql/esm/graphql.worker.js?worker'

self.MonacoEnvironment = {
  getWorker(_workerId: string, label: string): Worker {
    if (label === 'graphql') return new GraphQLWorker()
    if (label === 'json') return new JsonWorker()
    return new EditorWorker()
  },
}
