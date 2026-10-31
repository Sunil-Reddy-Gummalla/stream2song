import type { ClipLyricsResponse } from './clip-lyrics.model';

export class SpokenPreviewAudioRegistry {
  private readonly values = new Map<string, string>();
  private readonly create: (blob: Blob) => string;
  private readonly revoke: (url: string) => void;
  constructor(create: (blob: Blob) => string = (blob) => URL.createObjectURL(blob),
    revoke: (url: string) => void = (url) => URL.revokeObjectURL(url)) {
    this.create = create;
    this.revoke = revoke;
  }
  get(clipId: string): string | undefined { return this.values.get(clipId); }
  replace(clipId: string, blob: Blob): string {
    const next = this.create(blob);
    const previous = this.values.get(clipId);
    this.values.set(clipId, next);
    if (previous) this.revoke(previous);
    return next;
  }
  clear(clipId: string): void {
    const previous = this.values.get(clipId);
    this.values.delete(clipId);
    if (previous) this.revoke(previous);
  }
  clearAll(): void { for (const clipId of this.values.keys()) this.clear(clipId); }
}

export function canGenerateSpokenPreview(lyrics: ClipLyricsResponse | undefined, loading: boolean): boolean {
  return !!lyrics?.lyricsId && !loading;
}

export function isCurrentSpokenPreview(currentEpoch: number, requestEpoch: number,
  currentLyricsId: string | undefined, requestLyricsId: string): boolean {
  return currentEpoch === requestEpoch && currentLyricsId === requestLyricsId;
}

export function spokenPreviewSafeMessage(error: unknown): string {
  const detail = (error as { error?: { detail?: unknown } } | null)?.error?.detail;
  if (typeof detail !== 'string') return 'Could not create spoken preview. Try again.';
  const safe: Record<string, string> = {
    spoken_preview_not_configured: 'Spoken preview is not configured on the server. The administrator should configure OPENAI_API_KEY.',
    spoken_preview_lyrics_stale: 'These lyrics are no longer current. Generate the lyrics again before previewing them.',
    spoken_preview_input_invalid: 'These lyrics are too long for a spoken preview. Generate a shorter draft.',
    spoken_preview_capacity_exceeded: 'Spoken preview is busy. Try again in a moment.',
    spoken_preview_timeout: 'Spoken preview timed out. You can try again.',
    spoken_preview_provider_failed: 'OpenAI could not complete the spoken preview. You can try again.',
    spoken_preview_protocol_invalid: 'The spoken preview response was invalid. You can try again.',
    spoken_preview_incomplete: 'The spoken preview did not finish. You can try again.',
    spoken_preview_audio_too_large: 'The spoken preview exceeded the audio size limit.',
    spoken_preview_audio_invalid: 'The spoken preview audio was invalid. You can try again.',
    spoken_preview_provider_error: 'OpenAI could not complete the spoken preview. You can try again.',
  };
  return safe[detail] ?? 'Could not create spoken preview. Try again.';
}

const SPOKEN_PREVIEW_PROBLEM_CODES = new Set([
  'spoken_preview_not_configured', 'spoken_preview_lyrics_stale', 'spoken_preview_input_invalid',
  'spoken_preview_capacity_exceeded', 'spoken_preview_timeout', 'spoken_preview_provider_failed',
  'spoken_preview_protocol_invalid', 'spoken_preview_incomplete', 'spoken_preview_audio_too_large',
  'spoken_preview_audio_invalid', 'spoken_preview_provider_error',
]);

export async function normalizeSpokenPreviewError(error: unknown): Promise<unknown> {
  const body = (error as { error?: unknown } | null)?.error;
  if (typeof Blob === 'undefined' || !(body instanceof Blob)) return error;
  if (body.size > 16 * 1024) return { error: { detail: 'spoken_preview_provider_failed' } };
  try {
    const parsed: unknown = JSON.parse(await body.text());
    const detail = (parsed as { detail?: unknown } | null)?.detail;
    return { error: { detail: typeof detail === 'string' && SPOKEN_PREVIEW_PROBLEM_CODES.has(detail)
      ? detail : 'spoken_preview_provider_failed' } };
  } catch {
    return { error: { detail: 'spoken_preview_provider_failed' } };
  }
}
