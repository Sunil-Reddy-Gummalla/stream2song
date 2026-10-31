import assert from 'node:assert/strict';
import test from 'node:test';
import { musicCreateSafeMessage, musicJobSafeMessage } from './music-job-error.ts';
import { isCurrentMusicJobRequest, isTerminalMusicJob } from './music-job.model.ts';

test('maps music provider and expiry states to safe explanations', () => {
  assert.match(musicJobSafeMessage('music_submission_indeterminate'), /provider dashboard/i);
  assert.match(musicJobSafeMessage('music_provider_failed'), /explicitly retry/i);
  const safe = musicJobSafeMessage('secret lyrics in raw exception');
  assert.doesNotMatch(safe, /secret lyrics/i);
  assert.match(musicCreateSafeMessage({ error: { detail: 'music_provider_not_configured' } }), /not configured/i);
});

test('distinguishes provider credentials, access, billing, input, and permanent rejection', () => {
  assert.match(musicJobSafeMessage('music_provider_auth_failed'), /server administrator.*provider token/i);
  assert.match(musicJobSafeMessage('music_provider_access_denied'), /does not have access/i);
  assert.match(musicJobSafeMessage('music_provider_billing_required'), /billing or available credits/i);
  assert.match(musicJobSafeMessage('music_provider_input_invalid'), /lyrics or style settings/i);
  const permanent = musicJobSafeMessage('music_provider_rejected');
  assert.match(permanent, /check the server provider configuration/i);
  assert.doesNotMatch(permanent, /retry/i);
});

test('terminal job states stop polling and source changes reject stale responses', () => {
  assert.equal(isTerminalMusicJob('PENDING'), false);
  assert.equal(isTerminalMusicJob('PROCESSING'), false);
  assert.equal(isTerminalMusicJob('COMPLETED'), true);
  assert.equal(isTerminalMusicJob('FAILED'), true);
  assert.equal(isTerminalMusicJob('EXPIRED'), true);
  assert.equal(isCurrentMusicJobRequest(4, 4, 'lyrics-2', 'lyrics-1'), false);
  assert.equal(isCurrentMusicJobRequest(5, 4, 'lyrics-1', 'lyrics-1'), false);
  assert.equal(isCurrentMusicJobRequest(4, 4, 'lyrics-1', 'lyrics-1'), true);
});
