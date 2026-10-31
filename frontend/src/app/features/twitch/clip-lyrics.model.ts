export type LyricsGenre = 'pop' | 'rock' | 'hip_hop' | 'electronic' | 'folk';
export type LyricsMood = 'playful' | 'celebratory' | 'reflective' | 'dramatic';
export type LyricsEnergy = 'low' | 'medium' | 'high';
export interface ClipLyricsRequest { analysisId: string; interpretationId: string; genre: LyricsGenre; mood: LyricsMood; energy: LyricsEnergy; }
export interface ClipLyricsResponse extends ClipLyricsRequest {
  lyricsId: string; clipId: string; aiGenerated: true; unverified: true; association: string;
  chatTimingBasis: string | null; chatCorrelationExplanation: string; title: string;
  sections: Array<{ kind: 'verse' | 'chorus' | 'bridge'; label: string; lines: string[]; sources: Array<{
    momentId: string; startSeconds: number | null; endSeconds: number | null; association: string;
    evidence: Array<{ id: string; kind: string; excerpt: string; startSeconds: number | null; endSeconds: number | null }>;
  }> }>;
}
