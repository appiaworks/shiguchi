import { render, screen, fireEvent } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { SchemaDoc } from './SchemaDoc'
import type { FieldSchema, MessageSchema } from '../types/schema'

function field(over: Partial<FieldSchema>): FieldSchema {
  return {
    name: 'f',
    jsonName: 'f',
    type: 'string',
    label: 'optional',
    fields: [],
    enumValues: [],
    mapEntry: false,
    ...over,
  }
}

function schema(...fields: FieldSchema[]): MessageSchema {
  return { fullName: 'pkg.Msg', fields }
}

describe('SchemaDoc', () => {
  it('GIVEN_enum_field_WHEN_rendered_SHOULD_hide_values_until_toggled', () => {
    const kind = field({
      name: 'kind',
      jsonName: 'kind',
      type: 'enum',
      typeName: 'pkg.Kind',
      enumValues: [{ name: 'KIND_A', number: 1 }],
    })
    render(<SchemaDoc schema={schema(kind)} />)

    expect(screen.queryByText('KIND_A')).toBeNull()
    fireEvent.click(screen.getByText(/enum \(1\)/))
    expect(screen.getByText('KIND_A')).toBeTruthy()
  })

  it('GIVEN_explicit_optional_scalar_WHEN_rendered_SHOULD_show_optional_badge', () => {
    render(
      <SchemaDoc
        schema={schema(
          field({ name: 'a', jsonName: 'a', hasPresence: true }),
          field({ name: 'b', jsonName: 'b', hasPresence: false }),
        )}
      />,
    )

    expect(screen.getAllByText('optional')).toHaveLength(1)
  })
})
