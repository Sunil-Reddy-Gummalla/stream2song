# Stream2Song

Stream2Song turns a Twitch stream's memorable moments into a personalized song. After a stream ends, the application will use clips, audio, and chat messages to find standout moments and recurring jokes, generate lyrics, and create a song the streamer can listen to and share.

## How it works

The Angular frontend is the streamer's interface. It will let streamers connect their account, see song-generation progress, and listen to or share completed songs.

The Java Spring Boot backend owns the workflow and integrations. It will track stream events, retrieve clips and chat data, coordinate analysis and generation, and expose REST endpoints under `/api/**` for the frontend. Keeping provider integrations behind backend services allows them to evolve without coupling the frontend to Twitch or AI providers.

Song generation may take longer than a normal API request. The backend can run it as a job and expose a status endpoint for the frontend to check while it is processing.

## Repository layout

```text
stream2song/
├── README.md
├── .gitignore
├── frontend/                         # Angular and TypeScript application
│   ├── package.json
│   ├── angular.json
│   └── src/
└── backend/                          # Java 21 and Spring Boot application
    ├── pom.xml
    └── src/
        ├── main/
        │   ├── java/com/stream2song/backend/
        │   │   ├── config/
        │   │   ├── controller/
        │   │   ├── dto/
        │   │   ├── exception/
        │   │   ├── model/
        │   │   ├── repository/
        │   │   ├── service/
        │   │   └── Stream2SongApplication.java
        │   └── resources/application.yml
        └── test/
```

Create packages and components as they are needed; avoid adding empty or speculative modules. The frontend and backend are separate build projects in one Git repository. Once both run independently, the Angular production build can be added to the Spring Boot executable JAR for a single-deployable application.

## Technology

- **Frontend:** Angular with TypeScript
- **Backend:** Java 21, Spring Boot, and Maven
- **API:** REST endpoints under `/api/**`
- **Implemented Twitch foundation:** OAuth account linking, EventSub online/offline and chat-message webhook handling
- **Planned integrations:** Twitch Clips API; AI services for transcription and lyrics; a music-generation provider

## Local development

Prerequisites: Node.js 24.15.0 or newer (or 22.22.3 or newer in the 22.x line) and npm for Angular, plus Java 21 and Maven 3.6.3 or newer for Spring Boot.

Start the backend from `backend/`:

```bash
mvn spring-boot:run
```

Start the frontend in a second terminal from `frontend/`:

```bash
npm install
npm start
```

The frontend development server proxies `/api/**` to the backend at `http://localhost:8080`. The API base URL can be changed in `frontend/src/environments/environment.ts`; leave it empty to use the local proxy. The starter API health endpoint is `GET /api/health`.

### Twitch account connection and EventSub

Create a Twitch application in the Twitch Developer Console and register this OAuth Redirect URL exactly:

```text
http://localhost:8080/api/twitch/oauth/callback
```

Copy the Client ID and generate a Client Secret in the developer console. The repository includes `backend/.env.example` with the required names. Spring Boot does not load `.env` files automatically, so create the local file without replacing an existing one and edit it with your Twitch values:

```bash
cp -n backend/.env.example backend/.env
```

The backend reads `TWITCH_CLIENT_ID`, `TWITCH_CLIENT_SECRET`, and `TWITCH_OAUTH_REDIRECT_URI` from its process environment. For EventSub, also set `TWITCH_EVENTSUB_CALLBACK_URL` and `TWITCH_EVENTSUB_SIGNING_SECRET`. Spring Boot does not load `.env` files by itself. Export the shell-compatible local file before starting Spring Boot from the repository root:

```bash
set -a
source backend/.env
set +a
(cd backend && mvn spring-boot:run)
```

In another terminal, start Angular from `frontend/` with `npm start`, then open `http://localhost:4200`. The Connect Twitch button sends the browser through the backend authorization endpoint. The authorization request asks for the three scopes below; users must reconnect and approve them if they previously linked with an older identity-only version. OAuth state is random, single-use, expires after ten minutes, and is bound to the browser's backend session. Twitch's token-validation endpoint supplies the broadcaster ID, login, and granted scopes. The access token is kept only in backend memory; API responses expose only connection status and broadcaster identity. The prototype connection and token are lost when the backend restarts and are not suitable for multiple users.

#### Chat collection authorization and behavior

Chat is received through Twitch EventSub `channel.chat.message` version 1 using the existing public HTTPS webhook transport. The backend creates the subscription using a server-side App Access Token. For this prototype, the connected broadcaster is also the authorized chat user, so the OAuth request asks for:

- `user:read:chat` — read chat messages as the authorized user.
- `user:bot` — allow this user to act as the bot/chat user when creating an EventSub subscription with an App Access Token.
- `channel:bot` — allow the application to subscribe to the broadcaster's chat. Twitch also permits broadcaster moderator status for this authorization, but this prototype requests the explicit scope.

The app does not request `user:write:chat` and never sends chat messages. Twitch requires the subscription condition to identify both `broadcaster_user_id` and `user_id`. At account connection, the backend checks Twitch's Get Streams API with its App Access Token so it can also start collecting when the broadcaster is already live. On later `stream.online` notifications it subscribes to that active stream; on `stream.offline`, it deletes the chat subscription. Notifications are accepted only when the broadcaster is the connected account, the chat subscription is registered, and the in-memory stream session is still online. HMAC verification uses the raw webhook body. EventSub delivery IDs and Twitch chat message IDs are deduplicated, including retries with a different delivery ID. A message stores the Twitch ID, broadcaster and stream IDs, Twitch chatter ID/login, text, type/color metadata, and the signed EventSub delivery timestamp (the chat event payload does not provide a separate message timestamp).

Grant the scopes by reconnecting with the Connect Twitch button. If Twitch returns an older token without them, the backend rejects linking with a safe `chat_scopes_missing` reason. The dashboard shows chat subscription state, total collected message count, and up to 20 latest messages. API routes are `GET /api/twitch/chat/summary` and `GET /api/twitch/chat/messages?limit=20` (the limit is clamped to 1–100). Chat records and subscription state are held in memory and are lost when the backend restarts. A failed or revoked subscription can be retried by reconnecting; delivery is not guaranteed while the backend or public tunnel is unavailable.

#### Local EventSub webhook delivery

Twitch must reach the callback from the public internet over HTTPS on port 443. For local development, run a tunnelling service that forwards its public HTTPS hostname to `http://localhost:8080`, for example `ngrok http 8080`. Set the generated hostname in `TWITCH_EVENTSUB_CALLBACK_URL`, including `/api/twitch/eventsub/webhook`, then restart the backend and connect/reconnect Twitch. The callback URL registered with Twitch for OAuth remains the separate localhost OAuth URL above.

Generate the webhook signing secret as ASCII text (a 32-byte random value encoded as hex is suitable):

```bash
openssl rand -hex 32
```

Put that value in `TWITCH_EVENTSUB_SIGNING_SECRET` in the ignored `backend/.env`. Do not share it or place it in Angular. On successful account linking, the backend requests a Twitch App Access Token with its client credentials and creates `stream.online` and `stream.offline` webhook subscriptions for the connected broadcaster. Twitch first sends a signed callback challenge; once verified, subscription status appears in `GET /api/twitch/session`. That endpoint also reports the latest in-memory online/offline state. Notifications are HMAC-verified using their raw request body and deduplicated by message ID. Stream state, subscription status, and deduplication records are in memory and are lost when the application restarts. Twitch may retain its subscription records independently; this prototype does not reconcile or clean up old subscriptions, so keep a stable tunnel URL/signing secret during development and remove stale test subscriptions if you change them.

The local session endpoint is `GET /api/twitch/session`; the signed EventSub callback is `POST /api/twitch/eventsub/webhook`. A configured HTTPS URL does not prove the tunnel is reachable. No live EventSub or chat delivery is assumed until Twitch verifies the callback and a real notification is received.

For an IDE run configuration, set the same three environment variables directly on the backend process instead of relying on `.env` auto-loading. For HTTPS deployments, set `TWITCH_SESSION_COOKIE_SECURE=true`; keep the client secret exclusively on the backend and out of Angular configuration.

The Twitch endpoints are `GET /api/twitch/connect` (authorization redirect), `GET /api/twitch/oauth/callback` (Twitch callback), and `GET /api/twitch/status` (safe identity/status response). EventSub uses `POST /api/twitch/eventsub/webhook`; the dashboard reads `GET /api/twitch/session`, `GET /api/twitch/chat/summary`, and `GET /api/twitch/chat/messages?limit=20`.

The dashboard's **Refresh status** action calls `POST /api/twitch/session/refresh`. This re-attempts missing stream subscriptions and rechecks the connected broadcaster with Get Streams, then creates a chat subscription if Twitch confirms the stream is live. It does not set the session online unless Twitch returns an active stream or a valid signed `stream.online` notification is received. Subscription failures are logged with a Twitch HTTP status or a sanitized local reason; credentials and webhook secrets are never included.

Verify the backend endpoints with:

```bash
curl -i http://localhost:8080/api/health
curl -i http://localhost:8080/api/songs
```

## Song project API

Song-generation requests are currently stored in memory and are cleared when the backend restarts. A valid Twitch channel name is 4–25 letters, numbers, or underscores.

Create a song job:

```bash
curl -i -X POST http://localhost:8080/api/songs \
  -H 'Content-Type: application/json' \
  -d '{"channelName":"streamer_one"}'
```

The API responds with `201 Created`, a `Location` header for the new job, and a JSON body similar to:

```json
{
  "id": "68a40c60-fbc7-4b8d-832a-f6c27aa633d4",
  "channelName": "streamer_one",
  "status": "PENDING",
  "createdAt": "2026-10-26T12:30:00Z"
}
```

Retrieve one job with `GET /api/songs/{id}`. List jobs created during this application run with `GET /api/songs`; the response is an array of the same job objects. Invalid channel names receive `400 Bad Request`, and an unknown job ID receives `404 Not Found`.

Run the backend tests from `backend/` with:

```bash
mvn test
```

Keep API credentials and other secrets out of source control. When integrations are added, configure them through environment variables or local ignored configuration.
