import type { ClipLyricsResponse } from './clip-lyrics.model';

export function isCurrentLyricsRequest(currentEpoch: number, requestEpoch: number,
  currentInterpretationId: string | undefined, requestInterpretationId: string): boolean {
  return currentEpoch === requestEpoch && currentInterpretationId === requestInterpretationId;
}

export function formatClipLyricsForCopy(lyrics: ClipLyricsResponse): string {
  return [lyrics.title, ...lyrics.sections.flatMap((section) => ['', section.label, ...section.lines])].join('\n');
}

export function clipLyricsErrorMessage(error: unknown): string {
  const detail = (error as { error?: { detail?: unknown } } | null)?.error?.detail;
  if (typeof detail !== 'string') return 'Could not generate lyrics. Try again.';
  const messages: Record<string, string> = {
    lyrics_provider_not_configured: 'Lyrics generation is not configured on the server.',
    interpretation_snapshot_expired: 'This interpretation has expired. Find moments and interpret the clip again.',
    interpretation_snapshot_stale: 'This interpretation is no longer current. Find moments and interpret again.',
    lyrics_provider_rate_limited: 'The lyrics service is busy. Try again shortly.',
    lyrics_provider_timeout: 'Lyrics generation timed out. Try again.',
    lyrics_provider_failed: 'The lyrics service could not complete the request. Try again.',
  };
  return messages[detail] ?? 'Could not generate lyrics. Try again.';
}
