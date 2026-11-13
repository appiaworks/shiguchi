import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import { useGrpcUiStore } from '../store/useGrpcUiStore'
import { ResponseViewer } from './ResponseViewer'

describe('ResponseViewer', () => {
  beforeEach(() => {
    useGrpcUiStore.setState({
      result: { ...useGrpcUiStore.getState().result, status: 'idle', response: null, streamChunks: [], streamTotal: 0 },
    })
  })

  it('GIVEN a stream that completed with no messages WHEN rendered THEN it says 0 messages, stream completed OK', () => {
    useGrpcUiStore.getState().setResult({ status: 'success', grpcStatus: 'OK', durationMs: 12 })

    render(<ResponseViewer />)

    expect(screen.getByText('0 messages, stream completed OK')).toBeTruthy()
  })

  it('GIVEN a stream with two messages WHEN rendered THEN it lists them and not the empty message', () => {
    useGrpcUiStore.getState().setResult({ status: 'success', grpcStatus: 'OK' })
    useGrpcUiStore.getState().appendChunk('{"a":1}')
    useGrpcUiStore.getState().appendChunk('{"a":2}')

    render(<ResponseViewer />)

    expect(screen.queryByText('0 messages, stream completed OK')).toBeNull()
    expect(screen.getByText('2 chunks')).toBeTruthy()
  })
})
