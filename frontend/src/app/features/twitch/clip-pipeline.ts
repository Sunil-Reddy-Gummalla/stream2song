export type ClipPipelineStage = 'transcription' | 'moments' | 'interpretation' | 'lyrics' | 'spoken_preview';

export class ClipPipelineFailure extends Error {
  readonly stage: ClipPipelineStage;
  override readonly cause: unknown;
  constructor(stage: ClipPipelineStage, cause: unknown) {
    super(`Clip pipeline failed at ${stage}`);
    this.stage = stage;
    this.cause = cause;
  }
}

export class ClipPipelineCancelled extends Error {
  constructor() { super('Clip pipeline cancelled'); }
}

export interface ClipPipelineState<TTranscript, TMoments, TInterpretation, TLyrics> {
  transcription?: TTranscript;
  moments?: TMoments;
  interpretation?: TInterpretation;
  lyrics?: TLyrics;
}

export interface ClipPipelineSteps<TTranscript, TMoments, TInterpretation, TLyrics, TPreview> {
  state: ClipPipelineState<TTranscript, TMoments, TInterpretation, TLyrics>;
  momentsHaveEvidence: (moments: TMoments) => boolean;
  run: {
    transcription: () => Promise<TTranscript>;
    moments: () => Promise<TMoments>;
    interpretation: (moments: TMoments) => Promise<TInterpretation>;
    lyrics: (moments: TMoments, interpretation: TInterpretation) => Promise<TLyrics>;
    spokenPreview: (lyrics: TLyrics) => Promise<TPreview>;
  };
  onStage: (stage: ClipPipelineStage) => void;
  isCurrent: () => boolean;
}

export async function runClipPipeline<TTranscript, TMoments, TInterpretation, TLyrics, TPreview>(
  steps: ClipPipelineSteps<TTranscript, TMoments, TInterpretation, TLyrics, TPreview>,
): Promise<{ state: ClipPipelineState<TTranscript, TMoments, TInterpretation, TLyrics>; preview: TPreview | null; stoppedForNoEvidence: boolean }> {
  const ensureCurrent = (): void => { if (!steps.isCurrent()) throw new ClipPipelineCancelled(); };
  const invoke = async <T>(stage: ClipPipelineStage, action: () => Promise<T>): Promise<T> => {
    ensureCurrent();
    steps.onStage(stage);
    try {
      const result = await action();
      ensureCurrent();
      return result;
    } catch (error) {
      if (error instanceof ClipPipelineCancelled) throw error;
      ensureCurrent();
      throw new ClipPipelineFailure(stage, error);
    }
  };

  const state = { ...steps.state };
  if (!state.transcription) state.transcription = await invoke('transcription', steps.run.transcription);
  if (!state.moments) state.moments = await invoke('moments', steps.run.moments);
  if (!steps.momentsHaveEvidence(state.moments)) return { state, preview: null, stoppedForNoEvidence: true };
  if (!state.interpretation) state.interpretation = await invoke('interpretation', () => steps.run.interpretation(state.moments!));
  if (!state.lyrics) state.lyrics = await invoke('lyrics', () => steps.run.lyrics(state.moments!, state.interpretation!));
  const preview = await invoke('spoken_preview', () => steps.run.spokenPreview(state.lyrics!));
  return { state, preview, stoppedForNoEvidence: false };
}

export function clipPipelineStageLabel(stage: ClipPipelineStage): string {
  return ({ transcription: 'transcription', moments: 'moment extraction', interpretation: 'AI interpretation',
    lyrics: 'lyrics generation', spoken_preview: 'spoken preview' } as const)[stage];
}

export function canStartClipPipeline(loading: boolean): boolean { return !loading; }

export function invalidateRemovedClipSources(previousClipIds: readonly string[], nextClipIds: readonly string[],
  invalidate: (clipId: string) => void): string[] {
  const next = new Set(nextClipIds);
  const removed: string[] = [];
  for (const clipId of new Set(previousClipIds)) {
    if (!next.has(clipId)) {
      removed.push(clipId);
      invalidate(clipId);
    }
  }
  return removed;
}
