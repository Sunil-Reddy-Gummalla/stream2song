import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../../core/api/api-base-url.token';
import { CreateSongRequest, SongJob } from './song-job.model';

@Injectable({ providedIn: 'root' })
export class SongsApiService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(API_BASE_URL).replace(/\/$/, '');
  private readonly songsUrl = `${this.apiBaseUrl}/api/songs`;

  list(): Observable<SongJob[]> {
    return this.http.get<SongJob[]>(this.songsUrl);
  }

  create(request: CreateSongRequest): Observable<SongJob> {
    return this.http.post<SongJob>(this.songsUrl, request);
  }
}
