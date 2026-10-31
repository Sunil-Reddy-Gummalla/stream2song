export interface MusicJobResponse {
  jobId: string; status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'EXPIRED';
  title: string; genre: string; mood: string; energy: string; sourceMomentIds: string[];
  progressPercent: number | null; retryable: boolean; retryMode: 'STATUS' | 'GENERATION' | null; attempt: number; reason: string | null;
  createdAt: string; expiresAt: string; audioPath: string | null;
}
export function isTerminalMusicJob(status: MusicJobResponse['status']): boolean {
  return status === 'COMPLETED' || status === 'FAILED' || status === 'EXPIRED';
}
export function isCurrentMusicJobRequest(currentEpoch: number, requestEpoch: number,
  currentLyricsId: string | undefined, requestLyricsId: string): boolean {
  return currentEpoch === requestEpoch && currentLyricsId === requestLyricsId;
}
export function canResumeMusicStatus(job: MusicJobResponse | undefined, hasStatusError: boolean,
  polling: boolean, loading: boolean): boolean {
  return !!job && hasStatusError && !polling && !loading && !isTerminalMusicJob(job.status);
}
