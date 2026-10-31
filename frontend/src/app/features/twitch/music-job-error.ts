export function musicJobSafeMessage(reason: string): string {
  const safe: Record<string,string> = {
    music_provider_not_configured: 'Song generation is not configured on the server.',
    music_provider_auth_failed: 'Song generation credentials were rejected. The server administrator should check the provider token.',
    music_provider_access_denied: 'The server provider account does not have access to this music model.',
    music_provider_billing_required: 'The server provider account needs billing or available credits before generating songs.',
    music_provider_input_invalid: 'The provider rejected the submitted lyrics or style settings. Review the lyrics and try a new draft.',
    music_submission_indeterminate: 'The provider may have accepted this paid request, but its status is unknown. Check the provider dashboard before trying again.',
    music_provider_rate_limited: 'The song provider is busy. A retry is available for this rejected request.',
    music_provider_rejected: 'The provider rejected this request. Check the server provider configuration before trying again.',
    music_provider_failed: 'The provider finished this attempt unsuccessfully. You can explicitly retry it.',
    music_status_recovery_required: 'Provider status could not be checked. You can retry status checking without resubmitting the paid generation.',
    music_status_recovery_expired: 'The provider status recovery window expired. Check the provider dashboard; this request will not be resubmitted.',
    music_status_recovery_exhausted: 'Automatic status recovery attempts are exhausted. Check the provider dashboard; do not submit this generation again blindly.',
    music_output_expired: 'The provider audio expired before it could be downloaded. Generate a fresh lyric draft to try again.',
    music_output_too_large: 'The generated audio exceeded the server storage limit.',
    music_job_deadline_exceeded: 'Song generation exceeded its time limit.',
    music_audio_expired: 'This temporary audio file has expired. Generate a fresh lyric draft to try again.',
    lyrics_snapshot_expired: 'These lyrics expired. Generate lyrics again before creating a song.',
    lyrics_snapshot_stale: 'These lyrics are no longer current. Generate lyrics again before creating a song.',
    music_storage_capacity_exceeded: 'Temporary audio storage is full. Try again later.',
    music_job_capacity_exceeded: 'Too many song jobs are active. Try again later.',
  };
  return safe[reason] ?? 'Could not generate this song. Try again.';
}

export function musicCreateSafeMessage(error: unknown): string {
  const detail=(error as {error?:{detail?:unknown}}|null)?.error?.detail;
  return typeof detail==='string'?musicJobSafeMessage(detail):'Could not start song generation. Retrying this request is safe; duplicate requests reuse the same job.';
}
