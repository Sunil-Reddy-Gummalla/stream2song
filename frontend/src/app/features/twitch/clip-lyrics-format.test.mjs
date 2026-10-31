import assert from 'node:assert/strict';
import test from 'node:test';
import { clipLyricsErrorMessage, formatClipLyricsForCopy, isCurrentLyricsRequest } from './clip-lyrics-format.ts';

test('copies readable title, section labels, and lyric lines', () => {
  const text = formatClipLyricsForCopy({ title: 'Bright Loop', sections: [
    { label: 'Verse 1', lines: ['First line', 'Second line'] },
    { label: 'Chorus', lines: ['Sing it back'] },
  ] });
  assert.equal(text, 'Bright Loop\n\nVerse 1\nFirst line\nSecond line\n\nChorus\nSing it back');
});

test('maps lyric failures safely and never reflects backend text', () => {
  assert.match(clipLyricsErrorMessage({ error: { detail: 'lyrics_provider_not_configured' } }), /not configured/i);
  assert.match(clipLyricsErrorMessage({ error: { detail: 'interpretation_snapshot_expired' } }), /expired/i);
  const safe = clipLyricsErrorMessage({ error: { detail: 'private stack trace' } });
  assert.doesNotMatch(safe, /private|stack trace/i);
});

test('suppresses an old interpretation error after a newer interpretation succeeds', () => {
  const epoch = 12;
  assert.equal(isCurrentLyricsRequest(epoch, epoch, 'interpretation-I2', 'interpretation-I1'), false);
  assert.equal(isCurrentLyricsRequest(epoch, epoch, 'interpretation-I2', 'interpretation-I2'), true);
  assert.equal(isCurrentLyricsRequest(epoch + 1, epoch, 'interpretation-I1', 'interpretation-I1'), false);
});
