import assert from 'node:assert/strict';
import test from 'node:test';
import { ClipAnalysisEpochs } from './clip-analysis-epochs.ts';

test('new requests and account changes suppress stale clip results', () => {
  const epochs = new ClipAnalysisEpochs();
  const first = epochs.next('clip-a');
  const second = epochs.next('clip-a');
  assert.notEqual(first, second);
  assert.equal(epochs.current('clip-a'), second);
  epochs.invalidateAll();
  assert.notEqual(epochs.current('clip-a'), second);
});

test('bounded key eviction cannot reuse an old in-flight request epoch', () => {
  const epochs = new ClipAnalysisEpochs();
  const stale = epochs.next('old-clip');
  for (let i = 0; i < 101; i++) epochs.next('clip-' + i);
  assert.notEqual(epochs.current('old-clip'), stale);
  assert.notEqual(epochs.next('old-clip'), stale);
});
