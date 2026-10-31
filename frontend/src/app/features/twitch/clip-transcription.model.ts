export interface ClipTranscriptionSegment {
  startSeconds: number;
  endSeconds: number;
  text: string;
}

export interface ClipTranscriptionResponse {
  clipId: string;
  text: string;
  segments: ClipTranscriptionSegment[];
}
