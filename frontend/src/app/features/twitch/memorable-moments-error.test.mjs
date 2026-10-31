import assert from 'node:assert/strict';
import test from 'node:test';
import { memorableMomentsErrorMessage, momentInterpretationErrorMessage } from './memorable-moments-error.ts';

test('maps safe moments and shared transcription failures to useful messages', () => {
  assert.match(memorableMomentsErrorMessage({ error: { detail: 'twitch_clip_not_found' } }), /unavailable|does not belong/i);
  assert.match(memorableMomentsErrorMessage({ error: { detail: 'twitch_clip_metadata_invalid' } }), /timing metadata/i);
  assert.match(memorableMomentsErrorMessage({ error: { detail: 'transcription_provider_not_configured' } }), /not configured/i);
  assert.match(memorableMomentsErrorMessage({ error: { detail: 'clip_media_expired' } }), /fresh link/i);
});

test('never displays an unknown raw backend problem detail', () => {
  const message = memorableMomentsErrorMessage({ status: 502, error: { detail: 'secret internal database trace' } });
  assert.equal(message, 'Could not transcribe this clip. Please try again.');
  assert.doesNotMatch(message, /secret|database|trace/i);
});

test('maps interpretation setup and stale-snapshot failures safely', () => {
  assert.match(memorableMomentsErrorMessage({ error: { detail: 'interpretation_provider_not_configured' } }), /OPENAI|interpretation is not configured/i);
  assert.match(memorableMomentsErrorMessage({ error: { detail: 'moment_analysis_expired' } }), /Find memorable moments again/i);
  assert.match(memorableMomentsErrorMessage({ error: { detail: 'interpretation_output_invalid' } }), /still available/i);
});

test('interpretation errors retain extracted-evidence messaging without reflecting backend content', () => {
  assert.match(momentInterpretationErrorMessage({ error: { detail: 'interpretation_provider_timeout' } }), /still available/i);
  assert.match(momentInterpretationErrorMessage({ error: { detail: 'moment_analysis_stale' } }), /Find memorable moments again/i);
  assert.doesNotMatch(momentInterpretationErrorMessage({ error: { detail: 'internal secret trace' } }), /secret|trace/i);
});
