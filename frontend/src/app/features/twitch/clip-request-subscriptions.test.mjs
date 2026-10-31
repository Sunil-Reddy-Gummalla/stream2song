import assert from 'node:assert/strict';
import test from 'node:test';
import { Subscription } from 'rxjs';
import { ClipRequestSubscriptions } from './clip-request-subscriptions.ts';
import { invalidateRemovedClipSources } from './clip-pipeline.ts';

test('removed source cancellation unsubscribes its manual HTTP request and stale finalize cannot clear newer loading', () => {
  const requests = new ClipRequestSubscriptions();
  let cancelled = 0;
  let staleFinalizeClears = 0;
  const oldRequest = new Subscription(() => { cancelled++; });
  requests.track('removed', 'transcription', oldRequest);
  const invalidated = invalidateRemovedClipSources(['removed', 'kept'], ['kept', 'appended'], clipId => {
    requests.cancelClip(clipId, () => {});
  });
  assert.deepEqual(invalidated, ['removed']);
  assert.equal(cancelled, 1);
  assert.equal(requests.finish('removed', 'transcription', oldRequest), false);
  if (requests.finish('removed', 'transcription', oldRequest)) staleFinalizeClears++;
  const replacement = new Subscription();
  requests.track('removed', 'transcription', replacement);
  assert.equal(staleFinalizeClears, 0);
  assert.equal(requests.finish('removed', 'transcription', replacement), true);
  replacement.unsubscribe();
});

test('account cleanup cancels all tracked manual clip requests', () => {
  const requests = new ClipRequestSubscriptions();
  const cancelled = [];
  requests.track('clip-a', 'moments', new Subscription(() => cancelled.push('clip-a')));
  requests.track('clip-b', 'lyrics', new Subscription(() => cancelled.push('clip-b')));
  requests.cancelAll(clipId => cancelled.push(`clear:${clipId}`));
  assert.deepEqual(cancelled, ['clip-a', 'clear:clip-a', 'clip-b', 'clear:clip-b']);
});
