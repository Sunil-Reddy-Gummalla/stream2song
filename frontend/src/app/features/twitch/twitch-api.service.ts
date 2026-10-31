import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable, catchError, from, mergeMap, throwError } from 'rxjs';
import { API_BASE_URL } from '../../core/api/api-base-url.token';
import { TwitchConnectionStatus } from './twitch-connection.model';
import { TwitchStreamSession } from './twitch-stream-session.model';
import { TwitchChatMessage, TwitchChatSummary } from './twitch-chat.model';
import { TwitchClipsResponse } from './twitch-clip.model';
import { ClipTranscriptionResponse } from './clip-transcription.model';
import { MemorableMomentsResponse } from './memorable-moments.model';
import { MomentInterpretationResponse } from './moment-interpretation.model';
import { ClipLyricsRequest, ClipLyricsResponse } from './clip-lyrics.model';
import { MusicJobResponse } from './music-job.model';
import { normalizeSpokenPreviewError } from './spoken-preview';

@Injectable({ providedIn: 'root' })
export class TwitchApiService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(API_BASE_URL).replace(/\/$/, '');

  readonly connectUrl = `${this.apiBaseUrl}/api/twitch/connect`;

  status(): Observable<TwitchConnectionStatus> {
    return this.http.get<TwitchConnectionStatus>(`${this.apiBaseUrl}/api/twitch/status`);
  }

  session(): Observable<TwitchStreamSession> {
    return this.http.get<TwitchStreamSession>(`${this.apiBaseUrl}/api/twitch/session`);
  }

  refreshSession(): Observable<TwitchStreamSession> {
    return this.http.post<TwitchStreamSession>(`${this.apiBaseUrl}/api/twitch/session/refresh`, {});
  }

  chatSummary(): Observable<TwitchChatSummary> {
    return this.http.get<TwitchChatSummary>(`${this.apiBaseUrl}/api/twitch/chat/summary`);
  }

  chatMessages(limit = 20): Observable<TwitchChatMessage[]> {
    return this.http.get<TwitchChatMessage[]>(`${this.apiBaseUrl}/api/twitch/chat/messages`, { params: { limit } });
  }

  clips(limit = 20, after?: string, startedAt?: string, endedAt?: string): Observable<TwitchClipsResponse> {
    let params = new HttpParams().set('limit', limit);
    if (after) params = params.set('after', after);
    if (startedAt) params = params.set('startedAt', startedAt);
    if (endedAt) params = params.set('endedAt', endedAt);
    return this.http.get<TwitchClipsResponse>(`${this.apiBaseUrl}/api/twitch/clips`, { params });
  }

  transcribeClip(clipId: string, refresh = false): Observable<ClipTranscriptionResponse> {
    let params = new HttpParams();
    if (refresh) params = params.set('refresh', true);
    return this.http.post<ClipTranscriptionResponse>(
      `${this.apiBaseUrl}/api/twitch/clips/${encodeURIComponent(clipId)}/transcription`, {}, { params },
    );
  }

  findClipMoments(clipId: string): Observable<MemorableMomentsResponse> {
    return this.http.post<MemorableMomentsResponse>(
      `${this.apiBaseUrl}/api/twitch/clips/${encodeURIComponent(clipId)}/moments`, {},
    );
  }

  interpretClipMoments(clipId: string, analysisId: string): Observable<MomentInterpretationResponse> {
    return this.http.post<MomentInterpretationResponse>(
      `${this.apiBaseUrl}/api/twitch/clips/${encodeURIComponent(clipId)}/moments/interpretation`, { analysisId },
    );
  }

  generateClipLyrics(clipId: string, request: ClipLyricsRequest): Observable<ClipLyricsResponse> {
    return this.http.post<ClipLyricsResponse>(`${this.apiBaseUrl}/api/twitch/clips/${encodeURIComponent(clipId)}/lyrics`, request);
  }

  spokenLyricsPreview(clipId: string, lyricsId: string): Observable<Blob> {
    return this.http.post(`${this.apiBaseUrl}/api/twitch/clips/${encodeURIComponent(clipId)}/lyrics/${encodeURIComponent(lyricsId)}/spoken-preview`,
      {}, { responseType: 'blob' }).pipe(catchError(error =>
        from(normalizeSpokenPreviewError(error)).pipe(mergeMap(safeError => throwError(() => safeError)))));
  }

  createMusicJob(clipId: string, lyricsId: string): Observable<MusicJobResponse> {
    return this.http.post<MusicJobResponse>(`${this.apiBaseUrl}/api/twitch/clips/${encodeURIComponent(clipId)}/music-jobs`, { lyricsId });
  }
  musicJob(jobId: string): Observable<MusicJobResponse> {
    return this.http.get<MusicJobResponse>(`${this.apiBaseUrl}/api/twitch/music-jobs/${encodeURIComponent(jobId)}`);
  }
  retryMusicJob(jobId: string, expectedAttempt: number): Observable<MusicJobResponse> {
    return this.http.post<MusicJobResponse>(`${this.apiBaseUrl}/api/twitch/music-jobs/${encodeURIComponent(jobId)}/retry`, { expectedAttempt });
  }
  musicAudioUrl(jobId: string): string { return `${this.apiBaseUrl}/api/twitch/music-jobs/${encodeURIComponent(jobId)}/audio`; }
}
