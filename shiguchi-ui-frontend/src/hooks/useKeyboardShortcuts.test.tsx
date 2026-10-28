import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { useKeyboardShortcuts, type Shortcuts } from './useKeyboardShortcuts'

function Harness(props: Shortcuts) {
  useKeyboardShortcuts(props)
  return (
    <div>
      <input aria-label="a text field" />
      <textarea aria-label="an editor" />
    </div>
  )
}

describe('useKeyboardShortcuts', () => {
  it('sends on Ctrl+Enter', async () => {
    const onSend = vi.fn()
    render(<Harness onSend={onSend} />)

    await userEvent.keyboard('{Control>}{Enter}{/Control}')

    expect(onSend).toHaveBeenCalledOnce()
  })

  it('sends on Cmd+Enter, so the shortcut works on a Mac', async () => {
    const onSend = vi.fn()
    render(<Harness onSend={onSend} />)

    await userEvent.keyboard('{Meta>}{Enter}{/Meta}')

    expect(onSend).toHaveBeenCalledOnce()
  })

  it('still sends from inside the request editor, which is where your hands already are', async () => {
    const onSend = vi.fn()
    render(<Harness onSend={onSend} />)
    await userEvent.click(screen.getByLabelText('an editor'))

    await userEvent.keyboard('{Control>}{Enter}{/Control}')

    expect(onSend).toHaveBeenCalledOnce()
  })

  it('does not send on a bare Enter, which has to insert a newline', async () => {
    const onSend = vi.fn()
    render(<Harness onSend={onSend} />)
    await userEvent.click(screen.getByLabelText('an editor'))

    await userEvent.keyboard('{Enter}')

    expect(onSend).not.toHaveBeenCalled()
  })

  it('focuses the filter on Ctrl+K', async () => {
    const onFocusSearch = vi.fn()
    render(<Harness onFocusSearch={onFocusSearch} />)

    await userEvent.keyboard('{Control>}k{/Control}')

    expect(onFocusSearch).toHaveBeenCalledOnce()
  })

  it('closes on Escape', async () => {
    const onEscape = vi.fn()
    render(<Harness onEscape={onEscape} />)

    await userEvent.keyboard('{Escape}')

    expect(onEscape).toHaveBeenCalledOnce()
  })

  it('leaves Escape alone while typing, since a field may want it for itself', async () => {
    const onEscape = vi.fn()
    render(<Harness onEscape={onEscape} />)
    await userEvent.click(screen.getByLabelText('a text field'))

    await userEvent.keyboard('{Escape}')

    expect(onEscape).not.toHaveBeenCalled()
  })

  it('stops listening when unmounted, so a closed panel cannot still be triggered', async () => {
    const onSend = vi.fn()
    const { unmount } = render(<Harness onSend={onSend} />)

    unmount()
    await userEvent.keyboard('{Control>}{Enter}{/Control}')

    expect(onSend).not.toHaveBeenCalled()
  })
})
