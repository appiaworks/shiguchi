/**
 * Writes a server stream to a file as it arrives (NDJSON, one message per line), so a long stream
 * never has to fit in browser memory. Uses the File System Access API (Chromium); elsewhere
 * {@link canStreamToFile} is false and the console offers only the keep-last-N cap.
 */
export interface StreamFile {
  write(chunk: string): Promise<void>
  close(): Promise<void>
}

interface SaveFilePicker {
  showSaveFilePicker?: (opts: unknown) => Promise<{
    createWritable(): Promise<{ write(data: string): Promise<void>; close(): Promise<void> }>
  }>
}

export function canStreamToFile(): boolean {
  return typeof (window as unknown as SaveFilePicker).showSaveFilePicker === 'function'
}

/** Compacts a JSON message onto one line; non-JSON text is written as is, newlines flattened. */
export function toNdjsonLine(chunk: string): string {
  try {
    return JSON.stringify(JSON.parse(chunk))
  } catch {
    return chunk.replace(/\r?\n/g, ' ')
  }
}

/** Asks the user for a file (must run inside a click handler). Null when they cancel. */
export async function openStreamFile(suggestedName: string): Promise<StreamFile | null> {
  const picker = (window as unknown as SaveFilePicker).showSaveFilePicker
  if (!picker) return null
  try {
    const handle = await picker.call(window, {
      suggestedName,
      types: [{ description: 'NDJSON', accept: { 'application/x-ndjson': ['.ndjson', '.jsonl'] } }],
    })
    const writable = await handle.createWritable()
    return {
      write: (chunk) => writable.write(toNdjsonLine(chunk) + '\n'),
      close: () => writable.close(),
    }
  } catch (e) {
    if ((e as Error).name === 'AbortError') return null
    throw e
  }
}
