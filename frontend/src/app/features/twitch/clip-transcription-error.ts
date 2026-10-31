type BackendProblem = { detail?: unknown };
type HttpLikeError = { status?: unknown; error?: BackendProblem };

const SAFE_MESSAGES: Record<string, string> = {
  transcription_provider_not_configured: 'Clip transcription is not configured yet. Set up the backend transcription provider and try again.',
  twitch_clip_permission_denied: 'Twitch clip download permission is missing. Reconnect Twitch and approve clip management access.',
  twitch_clip_authorization_required: 'Twitch authorization is needed for clip media. Reconnect Twitch and try again.',
  twitch_clip_media_unavailable: 'Twitch does not currently have downloadable media for this clip.',
  clip_media_expired: 'The Twitch media link expired. Try again to request a fresh link.',
  unsupported_clip_media_type: 'This clip uses a media format that cannot be transcribed.',
  clip_media_too_large: 'This clip is too large to transcribe. Choose a shorter clip.',
  clip_media_timeout: 'The clip media request timed out. Try again.',
  clip_media_fetch_failed: 'The clip media could not be retrieved. Try again shortly.',
  clip_media_url_rejected: 'Twitch returned a media link that cannot be used safely.',
  transcription_provider_timeout: 'Transcription took too long. Try again.',
  transcription_provider_rate_limited: 'The transcription provider is busy. Try again shortly.',
  transcription_provider_failed: 'Transcription failed. Try again shortly.',
  transcription_result_too_large: 'The transcript result exceeded safe processing limits. Choose a shorter clip.',
};

export function clipTranscriptionErrorMessage(error: unknown): string {
  if (error && typeof error === 'object') {
    const problem = error as HttpLikeError;
    const detail = typeof problem.error?.detail === 'string' ? problem.error.detail : '';
    if (detail && SAFE_MESSAGES[detail]) return SAFE_MESSAGES[detail];
  }
  return 'Could not transcribe this clip. Please try again.';
}
