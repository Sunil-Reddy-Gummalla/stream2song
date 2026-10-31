export interface MomentInterpretationResponse {
  interpretationId: string;
  analysisId: string;
  clipId: string;
  aiGenerated: true;
  unverifiedInterpretation: true;
  moments: Array<{
    momentId: string;
    summary: string;
    emotionalTone: string;
    humorExcitement: string;
    narrativeSignificance: string;
    startSeconds: number | null;
    endSeconds: number | null;
    association: string;
    evidence: Array<{ id: string; kind: string; excerpt: string; startSeconds: number | null; endSeconds: number | null }>;
  }>;
  sessionContexts: Array<{
    contextId: string;
    summary: string;
    emotionalTone: string;
    humorExcitement: string;
    narrativeSignificance: string;
    association: 'unknown';
    evidence: Array<{ id: string; kind: 'session_chat_context'; excerpt: string; startSeconds: null; endSeconds: null }>;
  }>;
  transcriptContexts: Array<{
    contextId: string;
    summary: string;
    emotionalTone: string;
    humorExcitement: string;
    narrativeSignificance: string;
    startSeconds: null;
    endSeconds: null;
    association: 'verified_target_session' | 'different_session' | 'unknown';
    evidence: Array<{ id: string; kind: 'clip_transcript_context'; excerpt: string; startSeconds: number | null; endSeconds: number | null }>;
  }>;
}
