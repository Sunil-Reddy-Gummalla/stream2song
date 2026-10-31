import assert from 'node:assert/strict';
import test from 'node:test';
import { clipTranscriptionErrorMessage } from './clip-transcription-error.ts';

test('maps safe backend problem details to actionable messages', () => {
  assert.match(clipTranscriptionErrorMessage({ error: { detail: 'transcription_provider_not_configured' } }), /not configured/i);
  assert.match(clipTranscriptionErrorMessage({ error: { detail: 'twitch_clip_permission_denied' } }), /Reconnect Twitch/i);
  assert.match(clipTranscriptionErrorMessage({ error: { detail: 'clip_media_expired' } }), /fresh link/i);
  assert.match(clipTranscriptionErrorMessage({ error: { detail: 'clip_media_too_large' } }), /too large/i);
});

test('unknown backend errors use a safe retry message without reflecting response content', () => {
  const message = clipTranscriptionErrorMessage({ status: 502, error: { detail: 'secret internal stack trace' } });
  assert.equal(message, 'Could not transcribe this clip. Please try again.');
  assert.doesNotMatch(message, /secret|stack trace/i);
});
