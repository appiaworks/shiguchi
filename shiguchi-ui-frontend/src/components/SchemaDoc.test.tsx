import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import { SchemaDoc } from './SchemaDoc'
import { useGrpcUiStore } from '../store/useGrpcUiStore'
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

  it('GIVEN_explicit_optional_scalar_WHEN_rendered_SHOULD_not_show_an_optional_label', () => {
    render(<SchemaDoc schema={schema(field({ name: 'a', jsonName: 'a', hasPresence: true }))} />)

    expect(screen.queryByText('optional')).toBeNull()
  })

  it('GIVEN_required_field_WHEN_rendered_SHOULD_show_required_pill_with_annotation_tooltip', () => {
    render(
      <SchemaDoc
        schema={schema(
          field({ name: 'a', jsonName: 'a', required: true }),
          field({ name: 'b', jsonName: 'b' }),
        )}
      />,
    )

    const pill = screen.getAllByText('required')
    expect(pill).toHaveLength(1)
    expect(pill[0].getAttribute('title')).toContain('Marked required by annotation')
    expect(pill[0].getAttribute('title')).toContain('Proto3 itself has no required fields')
  })

  it('GIVEN_required_only_toggled_WHEN_rendered_SHOULD_hide_fields_that_are_not_required', () => {
    render(
      <SchemaDoc
        schema={schema(
          field({ name: 'a', jsonName: 'a', required: true }),
          field({ name: 'b', jsonName: 'b' }),
        )}
      />,
    )

    fireEvent.click(screen.getByLabelText('Required only'))
    expect(screen.queryByText('b')).toBeNull()
    expect(screen.getByText('a')).toBeTruthy()
  })

  it('GIVEN_no_required_fields_WHEN_rendered_SHOULD_not_offer_required_only', () => {
    render(<SchemaDoc schema={schema(field({ name: 'b', jsonName: 'b' }))} />)

    expect(screen.queryByLabelText('Required only')).toBeNull()
  })

  it('GIVEN_id_field_resolving_a_type_WHEN_chip_clicked_SHOULD_show_the_target_types_fields', () => {
    useGrpcUiStore.setState({
      services: [
        {
          name: 's',
          target: 't',
          namespace: 'n',
          configName: 'c',
          methods: [
            {
              name: 'GetAuthor',
              fullServiceName: 'pkg.S',
              methodType: 'UNARY',
              inputSchema: { fullName: 'pkg.In', fields: [] },
              outputTypeName: 'pkg.Author',
              outputSchema: { fullName: 'pkg.Author', fields: [field({ name: 'pen_name', jsonName: 'penName' })] },
            },
          ],
        },
      ],
    })
    render(<SchemaDoc schema={schema(field({ name: 'author_id', jsonName: 'authorId', resolvesTypes: ['Author'] }))} />)

    expect(screen.queryByText('penName')).toBeNull()
    fireEvent.click(screen.getByText('→ Author'))
    expect(screen.getByText('penName')).toBeTruthy()
    useGrpcUiStore.setState({ services: [] })
  })

  it('GIVEN_id_field_resolving_an_unknown_type_WHEN_chip_clicked_SHOULD_say_it_is_not_exposed', () => {
    render(<SchemaDoc schema={schema(field({ name: 'x_id', jsonName: 'xId', resolvesTypes: ['Ghost'] }))} />)

    fireEvent.click(screen.getByText('→ Ghost'))
    expect(screen.getByText(/Ghost is not part of any schema/)).toBeTruthy()
  })

  it('GIVEN_yaml_one_to_many_relationship_WHEN_rendered_SHOULD_show_list_badge_source_chip_and_group_by_tooltip', () => {
    render(
      <SchemaDoc
        schema={schema(
          field({
            name: 'id',
            jsonName: 'id',
            relationships: [
              { fieldName: 'books', target: 'Book', many: true, groupBy: 'authorId', source: 'yaml' },
            ],
          }),
        )}
      />,
    )

    const badge = screen.getByText('→ Book[]')
    expect(badge.getAttribute('title')).toContain('grouped by authorId')
    expect(screen.getByText('yaml').getAttribute('title')).toContain('gateway YAML')
  })

  it('GIVEN_annotation_relationship_WHEN_rendered_SHOULD_show_single_badge_with_annotation_chip', () => {
    render(
      <SchemaDoc
        schema={schema(
          field({
            name: 'author_id',
            jsonName: 'authorId',
            resolvesTypes: ['Author'],
            relationships: [{ fieldName: 'author', target: 'Author', many: false, groupBy: '', source: 'annotation' }],
          }),
        )}
      />,
    )

    expect(screen.getByText('→ Author')).toBeTruthy()
    expect(screen.queryByText('→ Author[]')).toBeNull()
    expect(screen.getByText('annotation')).toBeTruthy()
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
