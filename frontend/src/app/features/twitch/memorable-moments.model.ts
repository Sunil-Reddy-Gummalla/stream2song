export interface MemorableMomentsResponse {
  analysisId: string;
  clipId: string;
  source: {
    title: string;
    creatorName: string;
    durationSeconds: number;
    vodId: string | null;
    vodOffsetSeconds: number | null;
  };
  association: 'verified_target_session' | 'different_session' | 'unknown';
  chatCorrelation: 'aligned_coarse_context' | 'unaligned';
  chatTimingBasis: 'eventsub_delivery_timestamp' | null;
  chatCorrelationExplanation: string;
  heuristicOnly: true;
  scoreScale: string;
  message: string | null;
  moments: MemorableMoment[];
  sessionChatContext: {
    availableCount: number;
    analyzedCount: number;
    suppliedCount: number;
    truncatedCount: number;
    association: 'unknown';
    messages: Array<{ id: string; text: string }>;
  };
  transcriptContext: {
    availableCount: number;
    analyzedCount: number;
    suppliedCount: number;
    truncatedCount: number;
    suppliedCharacters: number;
    truncatedCharacters: number;
    association: 'verified_target_session' | 'different_session' | 'unknown';
    sourceType: 'utterances' | 'full_text_fallback' | 'none';
    utterances: Array<{ id: string; text: string; startSeconds: number | null; endSeconds: number | null }>;
  };
}

export interface MemorableMoment {
  id: string;
  description: string;
  startSeconds: number | null;
  endSeconds: number | null;
  score: number;
  transcriptEvidence: Array<{ text: string; startSeconds: number | null; endSeconds: number | null }>;
  chatEvidence: Array<{
    windowStartUtc: string;
    windowEndUtc: string;
    startSeconds: number | null;
    endSeconds: number | null;
    score: number;
    summary: string;
    explanation: string;
    messageCount: number;
    messages: string[];
  }>;
  sourceClipId: string;
  association: 'verified_target_session' | 'different_session' | 'unknown';
  selectionExplanation: string;
  contributors: string[];
}
