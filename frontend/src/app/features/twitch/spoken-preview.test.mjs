import assert from 'node:assert/strict';
import test from 'node:test';
import { SpokenPreviewAudioRegistry, canGenerateSpokenPreview, isCurrentSpokenPreview, normalizeSpokenPreviewError, spokenPreviewSafeMessage } from './spoken-preview.ts';

test('object URL registry replaces and revokes prior audio and clears all source-owned URLs', () => {
  const revoked=[];let next=0;
  const registry=new SpokenPreviewAudioRegistry(()=>`blob:test-${++next}`,url=>revoked.push(url));
  assert.equal(registry.replace('clip-1',new Blob(['first'])),'blob:test-1');
  assert.equal(registry.replace('clip-1',new Blob(['second'])),'blob:test-2');
  assert.deepEqual(revoked,['blob:test-1']);
  registry.replace('clip-2',new Blob(['third']));
  registry.clearAll();
  assert.deepEqual(revoked,['blob:test-1','blob:test-2','blob:test-3']);
  assert.equal(registry.get('clip-1'),undefined);
});

test('duplicate guard and epochs suppress spoken previews for stale lyric sources',()=>{
  const lyrics={lyricsId:'lyrics-1'};
  assert.equal(canGenerateSpokenPreview(lyrics,false),true);
  assert.equal(canGenerateSpokenPreview(lyrics,true),false);
  assert.equal(canGenerateSpokenPreview(undefined,false),false);
  assert.equal(isCurrentSpokenPreview(8,8,'lyrics-1','lyrics-1'),true);
  assert.equal(isCurrentSpokenPreview(9,8,'lyrics-1','lyrics-1'),false);
  assert.equal(isCurrentSpokenPreview(8,8,'lyrics-2','lyrics-1'),false);
});

test('spoken preview errors are mapped safely without reflecting provider text',()=>{
  assert.match(spokenPreviewSafeMessage({error:{detail:'spoken_preview_not_configured'}}),/OPENAI_API_KEY/);
  assert.match(spokenPreviewSafeMessage({error:{detail:'spoken_preview_lyrics_stale'}}),/Generate the lyrics again/);
  assert.doesNotMatch(spokenPreviewSafeMessage({error:{detail:'secret provider body'}}),/secret provider body/);
  assert.match(spokenPreviewSafeMessage(new Error('private')),/Try again/);
});

test('normalizes bounded audio Blob problem responses and discards raw fields and oversized bodies',async()=>{
  const normalized=await normalizeSpokenPreviewError({error:new Blob([JSON.stringify({detail:'spoken_preview_lyrics_stale',instance:'/secret/path'})])});
  assert.deepEqual(normalized,{error:{detail:'spoken_preview_lyrics_stale'}});
  assert.match(spokenPreviewSafeMessage(normalized),/Generate the lyrics again/);
  const oversized=await normalizeSpokenPreviewError({error:new Blob(['x'.repeat(16*1024+1)])});
  assert.deepEqual(oversized,{error:{detail:'spoken_preview_provider_failed'}});
});
