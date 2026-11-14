import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useGrpcUiStore } from '../store/useGrpcUiStore'
import type { MethodSchema, ServiceSchema } from '../types/schema'
import { InvokePanel } from './InvokePanel'

vi.mock('./LazyEditor', () => ({
  LazyEditor: (p: { value: string; onChange: (v: string) => void }) => (
    <textarea data-testid="json-editor" value={p.value} onChange={(e) => p.onChange(e.target.value)} />
  ),
}))
vi.mock('react-resizable-panels', () => ({
  Panel: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  Group: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  Separator: () => <hr />,
}))
vi.mock('./AuthBar', () => ({ AuthBar: () => null }))
vi.mock('./ResponseViewer', () => ({ ResponseViewer: () => null }))
vi.mock('./SchemaDoc', () => ({
  SchemaDoc: ({ schema }: { schema: { fullName: string } }) => <div data-testid="schema-doc">{schema.fullName}</div>,
}))
vi.mock('./MetadataEditor', () => ({ MetadataEditor: () => null }))

const method = (methodType: MethodSchema['methodType']): MethodSchema => ({
  name: 'Do',
  fullServiceName: 't.Svc',
  methodType,
  inputSchema: {
    fullName: 't.Req',
    fields: [
      { name: 'user_name', jsonName: 'userName', type: 'string', label: 'optional', fields: [], enumValues: [], mapEntry: false },
    ],
  },
  outputTypeName: 't.Res',
  outputSchema: {
    fullName: 't.Res',
    fields: [{ name: 'order_id', jsonName: 'orderId', type: 'string', label: 'optional', fields: [], enumValues: [], mapEntry: false }],
  },
})
const service = (m: MethodSchema): ServiceSchema => ({
  name: 't.Svc', target: 'x:1', methods: [m], namespace: 't', configName: 'c',
})

function open(type: MethodSchema['methodType'], mode: 'form' | 'json' = 'form', payload = '{}', formValues: Record<string, unknown> = {}) {
  const m = method(type)
  const s = service(m)
  useGrpcUiStore.setState({
    services: [s], selectedService: s, selectedMethod: m, requestMode: mode,
    payload, extraPayloads: [], formValues, history: [],
  })
  render(<InvokePanel />)
}

describe('InvokePanel request tabs', () => {
  beforeEach(() => localStorage.clear())

  it('GIVEN the form is active WHEN switching to JSON THEN the JSON shows the form values', () => {
    open('UNARY', 'form', '{}', { userName: 'ann' })

    fireEvent.click(screen.getByText('JSON'))

    expect(useGrpcUiStore.getState().requestMode).toBe('json')
    expect(JSON.parse(useGrpcUiStore.getState().payload)).toEqual({ userName: 'ann' })
  })

  it('GIVEN valid JSON WHEN switching to the form THEN the form values are filled with json names', () => {
    open('UNARY', 'json', '{"user_name":"bob","extra":1}')

    fireEvent.click(screen.getByText('Form'))

    const s = useGrpcUiStore.getState()
    expect(s.requestMode).toBe('form')
    expect(s.formValues).toEqual({ userName: 'bob', extra: 1 })
  })

  it('GIVEN invalid JSON WHEN switching to the form THEN stays on JSON and shows the error', () => {
    open('UNARY', 'json', '{broken')

    fireEvent.click(screen.getByText('Form'))

    expect(useGrpcUiStore.getState().requestMode).toBe('json')
    expect(useGrpcUiStore.getState().payload).toBe('{broken')
    expect(screen.getByRole('alert').textContent).toContain('Fix the JSON before switching to the form')
  })

  it('GIVEN a tab choice WHEN made THEN it is remembered in localStorage', () => {
    open('UNARY')

    fireEvent.click(screen.getByText('JSON'))

    expect(localStorage.getItem('grpcui-request-mode')).toBe('json')
  })

  it('GIVEN a client-streaming method WHEN clicking copy of #1 THEN a new message starts as message #1', () => {
    open('CLIENT_STREAMING', 'json', '{"userName":"ann"}')

    fireEvent.click(screen.getByText('+ copy of #1'))

    expect(JSON.parse(useGrpcUiStore.getState().extraPayloads[0])).toEqual({ userName: 'ann' })
  })

  it('GIVEN a unary method THEN there is no message-stream block', () => {
    open('UNARY')

    expect(screen.queryByText('+ copy of #1')).toBeNull()
  })

  it('GIVEN a method WHEN opening the OUTPUT SCHEMA tab THEN the response fields replace the request fields', () => {
    open('UNARY')

    expect(screen.getByTestId('schema-doc').textContent).toBe('t.Req')
    fireEvent.click(screen.getByRole('tab', { name: /output schema/i }))

    expect(screen.getByTestId('schema-doc').textContent).toBe('t.Res')
  })

  it('GIVEN a method WHEN toggling the response output schema THEN it shows beside the response', () => {
    open('UNARY')

    expect(screen.queryByTestId('response-schema')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'output schema' }))

    expect(screen.getByTestId('response-schema').textContent).toBe('t.Res')
  })
})
