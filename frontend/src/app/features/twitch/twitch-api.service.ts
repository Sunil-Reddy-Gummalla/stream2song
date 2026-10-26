import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../../core/api/api-base-url.token';
import { TwitchConnectionStatus } from './twitch-connection.model';
import { TwitchStreamSession } from './twitch-stream-session.model';
import { TwitchChatMessage, TwitchChatSummary } from './twitch-chat.model';

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
}
