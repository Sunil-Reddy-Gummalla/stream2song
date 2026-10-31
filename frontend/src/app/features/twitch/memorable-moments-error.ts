export const MOMENT_MESSAGES: Record<string, string> = {
  invalid_clip_id: 'This clip could not be selected. Refresh the clip list and try again.',
  twitch_account_not_connected: 'Connect Twitch before finding moments.',
  twitch_clip_not_found: 'This clip is unavailable or does not belong to the connected broadcaster.',
  twitch_clip_metadata_invalid: 'Twitch returned unusable clip timing metadata for this clip.',
  twitch_clip_metadata_unavailable: 'Twitch clip metadata could not be loaded. Try again shortly.',
  twitch_app_token_rejected: 'Twitch rejected the backend app token. Check the backend Twitch configuration.',
  twitch_response_invalid: 'Twitch returned incomplete clip metadata. Try again later.',
  twitch_api_unavailable: 'Twitch clip metadata is temporarily unavailable. Try again shortly.',
  interpretation_provider_not_configured: 'AI interpretation is not configured yet. Set up the backend OpenAI provider to use it.',
  interpretation_provider_rate_limited: 'The interpretation provider is busy. Try again shortly.',
  interpretation_provider_timeout: 'AI interpretation took too long. Your extracted moments are still available; try again.',
  interpretation_provider_failed: 'AI interpretation failed. Your extracted moments are still available; try again shortly.',
  interpretation_capacity_exceeded: 'The interpretation service is busy. Try again shortly.',
  interpretation_output_incomplete: 'AI interpretation was incomplete. Your extracted moments are still available; try again.',
  interpretation_response_too_large: 'AI interpretation returned too much data. Try again later.',
  interpretation_refused: 'The provider could not interpret this evidence. Your extracted moments are still available.',
  interpretation_output_invalid: 'AI interpretation could not be safely validated. Your extracted moments are still available.',
  moment_analysis_expired: 'These moments have expired. Find memorable moments again, then retry interpretation.',
  moment_analysis_stale: 'The transcript changed. Find memorable moments again, then retry interpretation.',
  moment_analysis_empty: 'There are no extracted moments to interpret.',
  moment_analysis_invalid: 'These moments are unavailable. Find memorable moments again.',
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
};

export function memorableMomentsErrorMessage(error: unknown): string {
  if (error && typeof error === 'object') {
    const detail = (error as { error?: { detail?: unknown } }).error?.detail;
    if (typeof detail === 'string') {
      if (detail === 'twitch_rate_limited') return 'Twitch is rate limiting clip requests. Try again shortly.';
      if (MOMENT_MESSAGES[detail]) return MOMENT_MESSAGES[detail];
    }
  }
  return 'Could not transcribe this clip. Please try again.';
}

export function momentInterpretationErrorMessage(error: unknown): string {
  if (error && typeof error === 'object') {
    const detail = (error as { error?: { detail?: unknown } }).error?.detail;
    if (typeof detail === 'string' && MOMENT_MESSAGES[detail]) return MOMENT_MESSAGES[detail];
  }
  return 'Could not interpret these moments. The extracted results are still available; please try again.';
}
