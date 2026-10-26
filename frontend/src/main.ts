import { provideHttpClient } from '@angular/common/http';
import { bootstrapApplication } from '@angular/platform-browser';
import { API_BASE_URL } from './app/core/api/api-base-url.token';
import { AppComponent } from './app/app.component';
import { environment } from './environments/environment';

bootstrapApplication(AppComponent, {
  providers: [
    provideHttpClient(),
    { provide: API_BASE_URL, useValue: environment.apiBaseUrl },
  ],
}).catch((error: unknown) => console.error(error));
