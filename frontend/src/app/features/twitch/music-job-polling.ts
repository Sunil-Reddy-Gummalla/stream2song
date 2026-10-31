import { Observable, catchError, exhaustMap, filter, map, of, takeWhile, throwError, timer } from 'rxjs';
import type { MusicJobResponse } from './music-job.model';

export function pollMusicJob(jobId: string, load: (jobId: string) => Observable<MusicJobResponse>,
  intervalMs = 5000, maxConsecutiveFailures = 5): Observable<MusicJobResponse> {
  let failures = 0;
  return timer(0, intervalMs).pipe(
    exhaustMap(() => load(jobId).pipe(
      map((job) => { failures = 0; return job; }),
      catchError((error: unknown) => {
        if (++failures >= maxConsecutiveFailures) return throwError(() => error);
        return of(null);
      }),
    )),
    filter((job): job is MusicJobResponse => job !== null),
    takeWhile((job) => !['COMPLETED', 'FAILED', 'EXPIRED'].includes(job.status), true),
  );
}
