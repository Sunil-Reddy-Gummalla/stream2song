import assert from 'node:assert/strict';
import test from 'node:test';
import { NEVER, of } from 'rxjs';
import { pollMusicJob } from './music-job-polling.ts';
import { canResumeMusicStatus } from './music-job.model.ts';

const job = (status) => ({ jobId: 'local-id', status, progressPercent: null });

test('polling stops on terminal status and emits that final state', async () => {
  let calls = 0;
  const states = [];
  await new Promise((resolve, reject) => pollMusicJob('local-id', () => of(job(++calls === 1 ? 'PROCESSING' : 'COMPLETED')), 2)
    .subscribe({ next: (value) => states.push(value.status), error: reject, complete: resolve }));
  assert.deepEqual(states, ['PROCESSING', 'COMPLETED']);
  assert.equal(calls, 2);
});

test('unsubscribe cancels the timer and exhaustMap prevents overlapping requests', async () => {
  let calls = 0;
  const subscription = pollMusicJob('local-id', () => { calls += 1; return NEVER; }, 5).subscribe();
  await new Promise((resolve) => setTimeout(resolve, 25));
  subscription.unsubscribe();
  const stoppedAt = calls;
  await new Promise((resolve) => setTimeout(resolve, 25));
  assert.equal(stoppedAt, 1);
  assert.equal(calls, stoppedAt);
});

test('a transient polling stop can be resumed for the same job without enabling duplicate or terminal polling', () => {
  const processing = job('PROCESSING');
  assert.equal(canResumeMusicStatus(processing, true, false, false), true);
  assert.equal(canResumeMusicStatus(processing, true, true, false), false);
  assert.equal(canResumeMusicStatus(processing, true, false, true), false);
  assert.equal(canResumeMusicStatus(job('COMPLETED'), true, false, false), false);
  assert.equal(canResumeMusicStatus(job('FAILED'), true, false, false), false);
  assert.equal(canResumeMusicStatus(job('EXPIRED'), true, false, false), false);
  assert.equal(canResumeMusicStatus(processing, false, false, false), false);
});
