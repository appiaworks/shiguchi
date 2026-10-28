import { useEffect } from 'react'

export interface Shortcuts {
  /** Ctrl/Cmd+Enter — send the current request. */
  onSend?: () => void
  /** Ctrl/Cmd+K — focus the service filter. */
  onFocusSearch?: () => void
  /** Escape — close whatever is open. */
  onEscape?: () => void
}

/** True when the event came from somewhere the user is typing. */
function isTypingTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  if (target.isContentEditable) return true
  const tag = target.tagName
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT'
}

/**
 * The three shortcuts worth having in a request console.
 *
 * <p>Ctrl/Cmd+Enter to send is the one that matters: the request editor is a code editor, so
 * Enter has to insert a newline, and reaching for the mouse on every invocation is most of the
 * friction in using the console at all. It works from inside the editor by design — that is
 * where your hands are.
 *
 * <p>Ctrl/Cmd+K focuses the filter, and Escape closes dialogs, both of which are what those keys
 * do in every other tool. Deliberately no single-letter shortcuts: they would fire while typing
 * a payload, and guessing whether a keystroke was meant as text is how shortcuts become
 * infuriating.
 */
export function useKeyboardShortcuts({ onSend, onFocusSearch, onEscape }: Shortcuts) {
  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      const mod = event.ctrlKey || event.metaKey

      if (mod && event.key === 'Enter') {
        // Allowed from inside the editor: that is the point.
        event.preventDefault()
        onSend?.()
        return
      }

      if (mod && event.key.toLowerCase() === 'k') {
        event.preventDefault()
        onFocusSearch?.()
        return
      }

      if (event.key === 'Escape') {
        // Not while typing: Escape in a text field should do whatever the field expects.
        if (isTypingTarget(event.target) && !(event.target as HTMLElement).dataset.escapeCloses) {
          return
        }
        onEscape?.()
      }
    }

    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [onSend, onFocusSearch, onEscape])
}
