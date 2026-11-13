import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { FieldSchema, MessageSchema } from '../types/schema'
import { FormEditor } from './FormEditor'

function field(over: Partial<FieldSchema> & { name: string }): FieldSchema {
  return { jsonName: over.name, type: 'string', label: 'optional', fields: [], enumValues: [], mapEntry: false, ...over }
}

describe('FormEditor', () => {
  it('GIVEN a repeated int64 field WHEN adding a row and typing 34 THEN the row starts empty and emits "34"', () => {
    const onChange = vi.fn()
    const schema: MessageSchema = { fullName: 't.R', fields: [field({ name: 'ids', type: 'int64', label: 'repeated' })] }
    const { rerender } = render(<FormEditor schema={schema} values={{}} onChange={onChange} />)

    fireEvent.click(screen.getByText('+ add'))
    expect(onChange).toHaveBeenLastCalledWith({ ids: [undefined] })

    rerender(<FormEditor schema={schema} values={{ ids: [undefined] }} onChange={onChange} />)
    const input = screen.getByPlaceholderText('int64') as HTMLInputElement
    expect(input.value).toBe('')
    fireEvent.change(input, { target: { value: '34' } })

    expect(onChange).toHaveBeenLastCalledWith({ ids: ['34'] })
  })

  it('GIVEN an int64 field WHEN typing a value above 2^53 THEN emits the exact digits as a string', () => {
    const onChange = vi.fn()
    const schema: MessageSchema = { fullName: 't.R', fields: [field({ name: 'id', type: 'int64' })] }
    render(<FormEditor schema={schema} values={{}} onChange={onChange} />)

    fireEvent.change(screen.getByPlaceholderText('int64'), { target: { value: '9007199254740993' } })

    expect(onChange).toHaveBeenCalledWith({ id: '9007199254740993' })
  })

  it('GIVEN an int32 field WHEN typing THEN emits a number', () => {
    const onChange = vi.fn()
    const schema: MessageSchema = { fullName: 't.R', fields: [field({ name: 'n', type: 'int32' })] }
    render(<FormEditor schema={schema} values={{}} onChange={onChange} />)

    fireEvent.change(screen.getByPlaceholderText('int32'), { target: { value: '5' } })

    expect(onChange).toHaveBeenCalledWith({ n: 5 })
  })

  it('GIVEN a message whose fields were cut off WHEN typing JSON THEN emits the parsed object', () => {
    const onChange = vi.fn()
    const schema: MessageSchema = {
      fullName: 't.R',
      fields: [field({ name: 'deep', type: 'message', typeName: 't.Deep' })],
    }
    render(<FormEditor schema={schema} values={{}} onChange={onChange} />)

    fireEvent.change(screen.getByPlaceholderText('t.Deep as JSON'), { target: { value: '{"a":1}' } })

    expect(onChange).toHaveBeenCalledWith({ deep: { a: 1 } })
  })
})
