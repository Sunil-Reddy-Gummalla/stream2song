import test from 'node:test';
import assert from 'node:assert/strict';
import { canStartClipPipeline, ClipPipelineCancelled, ClipPipelineFailure, invalidateRemovedClipSources, runClipPipeline } from './clip-pipeline.ts';
import { ClipAnalysisEpochs } from './clip-analysis-epochs.ts';
import { SpokenPreviewAudioRegistry } from './spoken-preview.ts';

const sample = (overrides = {}) => {
  const calls = [];
  return {
    calls,
    steps: {
      state: {}, momentsHaveEvidence: value => value.items.length > 0,
      onStage: stage => calls.push(`stage:${stage}`), isCurrent: () => true,
      run: {
        transcription: async () => { calls.push('transcription'); return { text: 'hello' }; },
        moments: async () => { calls.push('moments'); return { items: ['m1'] }; },
        interpretation: async () => { calls.push('interpretation'); return { id: 'i1' }; },
        lyrics: async () => { calls.push('lyrics'); return { id: 'l1' }; },
        spokenPreview: async () => { calls.push('spoken_preview'); return { audio: true }; },
      }, ...overrides,
    },
  };
};

test('runs transcription through spoken preview in order', async () => {
  const { steps, calls } = sample();
  const result = await runClipPipeline(steps);
  assert.deepEqual(calls.filter(x => !x.startsWith('stage:')), ['transcription', 'moments', 'interpretation', 'lyrics', 'spoken_preview']);
  assert.equal(result.preview.audio, true);
  assert.equal(result.stoppedForNoEvidence, false);
});

test('stops before paid downstream stages when moment extraction is empty', async () => {
  const { steps, calls } = sample();
  steps.run.moments = async () => { calls.push('moments'); return { items: [] }; };
  const result = await runClipPipeline(steps);
  assert.deepEqual(calls.filter(x => !x.startsWith('stage:')), ['transcription', 'moments']);
  assert.equal(result.stoppedForNoEvidence, true);
});

test('continues through the pipeline when only untimed session chat context is available', async () => {
  const { steps, calls } = sample();
  steps.run.moments = async () => { calls.push('moments'); return { items: [], sessionChatContext: { suppliedCount: 2 } }; };
  steps.momentsHaveEvidence = value => value.items.length > 0 || value.sessionChatContext.suppliedCount > 0;
  const result = await runClipPipeline(steps);
  assert.equal(result.stoppedForNoEvidence, false);
  assert.deepEqual(calls.filter(x => !x.startsWith('stage:')),
    ['transcription', 'moments', 'interpretation', 'lyrics', 'spoken_preview']);
});

test('continues when transcript context is available without scored moments or session chat', async () => {
  const { steps, calls } = sample();
  steps.run.moments = async () => { calls.push('moments'); return { items: [], sessionChatContext: { suppliedCount: 0 }, transcriptContext: { suppliedCount: 6 } }; };
  steps.momentsHaveEvidence = value => value.items.length > 0 || value.sessionChatContext.suppliedCount > 0 || value.transcriptContext.suppliedCount > 0;
  const result = await runClipPipeline(steps);
  assert.equal(result.stoppedForNoEvidence, false);
  assert.deepEqual(calls.filter(x => !x.startsWith('stage:')),
    ['transcription', 'moments', 'interpretation', 'lyrics', 'spoken_preview']);
});

test('continues when scored moments and session chat are empty but clip transcript context is available', async () => {
  const { steps, calls } = sample();
  steps.run.moments = async () => { calls.push('moments'); return { items: [], sessionChatContext: { suppliedCount: 0 }, transcriptContext: { suppliedCount: 6 } }; };
  steps.momentsHaveEvidence = value => value.items.length > 0 || value.sessionChatContext.suppliedCount > 0 || value.transcriptContext.suppliedCount > 0;
  const result = await runClipPipeline(steps);
  assert.equal(result.stoppedForNoEvidence, false);
  assert.deepEqual(calls.filter(x => !x.startsWith('stage:')),
    ['transcription', 'moments', 'interpretation', 'lyrics', 'spoken_preview']);
});

test('stops at the failed stage and wraps it for safe stage-specific handling', async () => {
  const { steps, calls } = sample();
  steps.run.interpretation = async () => { calls.push('interpretation'); throw new Error('private'); };
  await assert.rejects(runClipPipeline(steps), error => error instanceof ClipPipelineFailure && error.stage === 'interpretation');
  assert.deepEqual(calls.filter(x => !x.startsWith('stage:')), ['transcription', 'moments', 'interpretation']);
});

test('resumes from valid successful local snapshots without repeating paid stages', async () => {
  const { steps, calls } = sample({ state: { transcription: { text: 'saved' }, moments: { items: ['m'] }, interpretation: { id: 'i' }, lyrics: { id: 'l' } } });
  await runClipPipeline(steps);
  assert.deepEqual(calls.filter(x => !x.startsWith('stage:')), ['spoken_preview']);
});

test('cancellation rejects stale completions and duplicate starts are guarded by loading state', async () => {
  let current = true;
  const { steps } = sample({ isCurrent: () => current, run: { ...sample().steps.run,
    transcription: async () => { current = false; return { text: 'stale' }; } } });
  await assert.rejects(runClipPipeline(steps), ClipPipelineCancelled);
  assert.equal(canStartClipPipeline(false), true);
  assert.equal(canStartClipPipeline(true), false);
});

test('replacing the clip list cancels removed source work, prevents later paid stages, and revokes only removed audio', async () => {
  const epochs = new ClipAnalysisEpochs();
  const removedEpoch = epochs.next('removed');
  const keptEpoch = epochs.next('kept');
  const revoked = [];
  const audio = new SpokenPreviewAudioRegistry(blob => blob.name, url => revoked.push(url));
  audio.replace('removed', { name: 'removed-audio' });
  audio.replace('kept', { name: 'kept-audio' });
  let cancelPending;
  let paidDownstreamCalls = 0;
  const pending = runClipPipeline({
    state: { transcription: { clipId: 'removed' } }, momentsHaveEvidence: value => value.items.length > 0,
    onStage: () => {}, isCurrent: () => epochs.current('removed') === removedEpoch,
    run: {
      transcription: async () => ({ clipId: 'removed' }),
      moments: () => new Promise((resolve, reject) => { cancelPending = () => reject(new ClipPipelineCancelled()); }),
      interpretation: async () => { paidDownstreamCalls++; return {}; },
      lyrics: async () => { paidDownstreamCalls++; return {}; },
      spokenPreview: async () => { paidDownstreamCalls++; return {}; },
    },
  });
  await Promise.resolve();
  const invalidated = invalidateRemovedClipSources(['removed', 'kept'], ['kept', 'appended'], clipId => {
    epochs.next(clipId);
    if (clipId === 'removed') cancelPending();
    audio.clear(clipId);
  });
  await assert.rejects(pending, ClipPipelineCancelled);
  assert.deepEqual(invalidated, ['removed']);
  assert.equal(paidDownstreamCalls, 0);
  assert.deepEqual(revoked, ['removed-audio']);
  assert.equal(audio.get('kept'), 'kept-audio');
  assert.equal(epochs.current('kept'), keptEpoch);
  assert.equal(epochs.current('appended'), -1);
});
