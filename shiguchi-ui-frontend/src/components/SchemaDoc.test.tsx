import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
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

  it('GIVEN_required_field_WHEN_rendered_SHOULD_show_red_bang_after_type_with_annotation_tooltip', () => {
    render(
      <SchemaDoc
        schema={schema(
          field({ name: 'a', jsonName: 'a', required: true }),
          field({ name: 'b', jsonName: 'b' }),
        )}
      />,
    )

    const bang = screen.getAllByText('!')
    expect(bang).toHaveLength(1)
    expect(bang[0].className).toContain('text-red-500')
    expect(bang[0].getAttribute('title')).toContain('Marked required by annotation')
    expect(bang[0].getAttribute('title')).toContain('Proto3 itself has no required fields')
  })

  it('GIVEN_repeated_map_oneof_and_well_known_fields_WHEN_rendered_SHOULD_explain_them_in_tooltips', () => {
    render(
      <SchemaDoc
        schema={schema(
          field({ name: 'r', jsonName: 'r', label: 'repeated' }),
          field({ name: 'm', jsonName: 'm', label: 'map', mapEntry: true }),
          field({ name: 'o', jsonName: 'o', oneofName: 'choice' }),
          field({ name: 't', jsonName: 't', type: 'message', wellKnownType: 'TIMESTAMP' }),
        )}
      />,
    )

    expect(screen.getByText('[repeated]').getAttribute('title')).toContain('JSON array')
    expect(screen.getByText('[map]').getAttribute('title')).toContain('JSON object')
    expect(screen.getByText('oneof:choice').getAttribute('title')).toContain('at most one')
    expect(screen.getByText('TIMESTAMP').getAttribute('title')).toContain('RFC 3339')
  })

  it('GIVEN_nested_repeated_field_WHEN_copy_path_clicked_SHOULD_write_dotted_path_with_brackets', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    const id = field({ name: 'id', jsonName: 'id' })
    const items = field({ name: 'items', jsonName: 'items', type: 'message', label: 'repeated', fields: [id] })
    render(<SchemaDoc schema={schema(items)} />)

    fireEvent.click(screen.getByLabelText('Copy path items[].id'))

    expect(writeText).toHaveBeenCalledWith('items[].id')
  })

  it('GIVEN_clipboard_rejects_WHEN_copy_path_clicked_SHOULD_not_throw', async () => {
    const writeText = vi.fn().mockRejectedValue(new Error('denied'))
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    render(<SchemaDoc schema={schema(field({ name: 'a', jsonName: 'a' }))} />)

    fireEvent.click(screen.getByLabelText('Copy path a'))

    await waitFor(() => expect(writeText).toHaveBeenCalled())
  })

  it('GIVEN_nested_message_and_enum_WHEN_expand_all_then_collapse_all_SHOULD_toggle_both', () => {
    const kind = field({
      name: 'kind',
      jsonName: 'kind',
      type: 'enum',
      typeName: 'pkg.Kind',
      enumValues: [{ name: 'KIND_A', number: 1 }],
    })
    const deep = field({ name: 'deep', jsonName: 'deep', type: 'message', typeName: 'pkg.Deep', fields: [kind] })
    const mid = field({ name: 'mid', jsonName: 'mid', type: 'message', typeName: 'pkg.Mid', fields: [deep] })
    const top = field({ name: 'top', jsonName: 'top', type: 'message', typeName: 'pkg.Top', fields: [mid] })
    render(<SchemaDoc schema={schema(top)} />)

    // depth 2 starts closed, so the enum row is not even rendered yet
    expect(screen.queryByText('kind')).toBeNull()
    fireEvent.click(screen.getByText('Expand all'))
    expect(screen.getByText('kind')).toBeTruthy()
    expect(screen.getByText('KIND_A')).toBeTruthy()

    fireEvent.click(screen.getByText('Collapse all'))
    expect(screen.queryByText('KIND_A')).toBeNull()
    expect(screen.queryByText('top')).toBeTruthy()
    expect(screen.queryByText('mid')).toBeNull()
  })

  it('GIVEN_flat_message_WHEN_rendered_SHOULD_not_offer_expand_all', () => {
    render(<SchemaDoc schema={schema(field({ name: 'a', jsonName: 'a' }))} />)

    expect(screen.queryByText('Expand all')).toBeNull()
  })
})
