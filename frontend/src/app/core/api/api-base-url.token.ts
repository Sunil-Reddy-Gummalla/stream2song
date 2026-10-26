import { InjectionToken } from '@angular/core';

/** Base URL for backend requests. Empty uses the current origin (and the dev proxy locally). */
export const API_BASE_URL = new InjectionToken<string>('API_BASE_URL', {
  providedIn: 'root',
  factory: () => '',
});
