# Stream2Song

Stream2Song is an evolving project for turning Twitch stream moments into a personalized song. The app can find clip moments, interpret them with AI, draft evidence-cited lyrics, and generate a vocal MP3 from a selected lyrics draft.

## How it works

The Angular frontend is the streamer's interface. It lets streamers connect their account, see song-generation status, and listen to temporarily stored completed audio.

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
- **Clip analysis integrations:** Twitch Clips API, Deepgram transcription, OpenAI moment interpretation, and evidence-cited lyric generation
- **Planned integrations:** persistent job storage and music export/sharing

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

### Backend logs

The backend logs to the console and to a rolling file at `backend/logs/stream2song.log` when started from the documented `backend/` directory. Log files are capped at 10 MB per file, retained for 14 days, and rotated files are limited to 200 MB total. The `backend/logs/` directory is created automatically and ignored by Git.

View the current log while the backend runs:

```bash
tail -f backend/logs/stream2song.log
```

Logging defaults to INFO. To enable DEBUG for Stream2Song application classes during local development, set `STREAM2SONG_LOG_LEVEL=DEBUG` in the backend process environment before starting it. For example, from the repository root:

```bash
STREAM2SONG_LOG_LEVEL=DEBUG sh -c 'cd backend && mvn spring-boot:run'
```

Avoid sharing log files without reviewing them first; logs include operational identifiers such as broadcaster IDs and song-job IDs, but must not contain credentials or raw chat messages.

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

The backend reads `TWITCH_CLIENT_ID`, `TWITCH_CLIENT_SECRET`, and `TWITCH_OAUTH_REDIRECT_URI` from its process environment. Set `DEEPGRAM_API_KEY` to enable clip transcription. Set `OPENAI_API_KEY` to enable optional moment interpretation and lyric generation; `OPENAI_MODEL` defaults to `gpt-6-luna`. Keep provider keys server-side. For EventSub, also set `TWITCH_EVENTSUB_CALLBACK_URL` and `TWITCH_EVENTSUB_SIGNING_SECRET`. Spring Boot does not load `.env` files by itself. Export the shell-compatible local file before starting Spring Boot from the repository root:

```bash
set -a
source backend/.env
set +a
(cd backend && mvn spring-boot:run)
```

In another terminal, start Angular from `frontend/` with `npm start`, then open `http://localhost:4200`. The Connect Twitch button sends the browser through the backend authorization endpoint. The authorization request asks for the three chat scopes plus `channel:manage:clips`; users must reconnect and approve the added scope if they previously linked before clip media access was added. OAuth state is random, single-use, expires after ten minutes, and is bound to the browser's backend session. Twitch's token-validation endpoint supplies the broadcaster ID, login, and granted scopes. The access token is kept only in backend memory; API responses expose only connection status and broadcaster identity. The prototype connection and token are lost when the backend restarts and are not suitable for multiple users.

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

When Twitch returns `409 Conflict` during subscription creation, the backend uses its App Access Token to query EventSub subscriptions and matches the event type/version, condition, webhook transport, and configured callback URL. Matching `enabled` subscriptions are treated as active; `webhook_callback_verification_pending` is reported as pending. If a matching subscription has an unusable status, the backend deletes it and makes one replacement attempt. If lookup finds no equivalent subscription or cannot complete, the session status is `conflict_unresolved`. These statuses are available in `subscriptionStatuses` from `GET /api/twitch/session`; subscription health is separate from OAuth account connection, so a 409 does not disconnect the broadcaster. The dashboard labels active and pending subscriptions accurately and only shows a setup error for unresolved or genuine failures.

For an IDE run configuration, set the same three environment variables directly on the backend process instead of relying on `.env` auto-loading. For HTTPS deployments, set `TWITCH_SESSION_COOKIE_SECURE=true`; keep the client secret exclusively on the backend and out of Angular configuration.

The Twitch endpoints are `GET /api/twitch/connect` (authorization redirect), `GET /api/twitch/oauth/callback` (Twitch callback), and `GET /api/twitch/status` (safe identity/status response). EventSub uses `POST /api/twitch/eventsub/webhook`; the dashboard reads `GET /api/twitch/session`, `GET /api/twitch/chat/summary`, and `GET /api/twitch/chat/messages?limit=20`.

#### Broadcaster clip discovery

`GET /api/twitch/clips?limit=20` lists recent clips for the connected broadcaster. `limit` defaults to 20 and must be 1–100. The endpoint returns one Twitch page at a time; pass the returned `nextCursor` as `after` to continue. The response echoes `startedAt` and `endedAt`; send both unchanged on subsequent page requests. If omitted, the backend uses the latest seven-day interval. Explicit ranges must be positive, no longer than seven days, and not in the future.

The endpoint uses the server-side Twitch App Access Token and does not add an OAuth scope. Twitch's Get Clips endpoint supports broadcaster filtering and pagination. For a current or most recently completed session with a stored stream ID, Stream2Song resolves available clip `video_id` values through Twitch Get Videos and compares the returned VOD `stream_id` to the session's Twitch stream ID. A matching ID is reported as `verified_target_session`; a different returned stream ID is `different_session`. When the VOD or metadata is unavailable, the result is `unknown`. All clips remain available as recent broadcaster results, including those whose association is unknown.

Twitch Get Clips `vod_offset` is the clip's zero-based start position in its VOD, not a wall-clock timestamp. It is returned when present, but this milestone does not turn it into an absolute stream time. `created_at` is when the clip was created and can differ from when the captured moment happened, so it is never used to assert session membership. An active stream may not have a published VOD yet; VOD privacy, removal, or Twitch's delay in assigning metadata can also leave association unknown. Twitch's Get Clips Download API is separate from clip metadata and thumbnails. `GET /api/twitch/clips/{clipId}/media` requests temporary landscape and portrait media URLs using the connected broadcaster's user token and `channel:manage:clips`; the endpoint does not proxy media bytes. Twitch may return either URL as null or return no media for an unavailable clip. URLs are temporary and can expire, so request them when needed. The broadcaster must reconnect through Connect Twitch and approve the added scope. This access layer does not perform video analysis or summarization. `POST /api/twitch/clips/{clipId}/transcription` transcribes one selected clip and returns provider-independent text with utterance start/end timestamps in seconds. It requests a fresh Twitch URL internally, downloads the file only from an HTTPS Twitch media CDN host, accepts supported audio formats and MP4/WebM media, applies 5-second connect and 20-second read timeouts, and caps in-memory media at 20 MiB. The URL and transcript are never logged, and the URL is never returned by this endpoint. Deepgram Nova-3 prerecorded transcription is the current provider; `DEEPGRAM_API_KEY` is required. Empty or expired Twitch media, unsupported content types, oversized files, network timeouts, and provider errors produce safe problem responses. No batch processing, video analysis, summarization, lyric writing, or song generation is performed. Twitch API authorization errors, missing app configuration, and rate limits are returned as safe problem responses, and credentials are never included in response bodies.

Example:

```bash
curl 'http://localhost:8080/api/twitch/clips?limit=10'
```

Response shape (clip list shortened):

```json
{
  "broadcasterId": "12345678",
  "association": "recent_broadcaster_clips",
  "targetSessionStreamId": "987654321",
  "targetSessionEventTimestamp": "2026-10-26T18:12:00Z",
  "startedAt": "2026-10-20T12:00:00Z",
  "endedAt": "2026-10-27T12:00:00Z",
  "nextCursor": "eyJiIjp7...",
  "associationNote": "A clip is associated with a session only when Twitch resolves its VOD to the session's stream ID. Other results remain recent broadcaster clips; clip creation time does not identify when the captured moment happened.",
  "clips": [{
    "id": "ExampleClipId",
    "title": "A memorable moment",
    "creatorName": "clip_creator",
    "createdAt": "2026-10-26T18:12:00Z",
    "duration": 32.4,
    "url": "https://clips.twitch.tv/ExampleClipId",
    "thumbnailUrl": "https://static-cdn.jtvnw.net/example.jpg",
    "vodId": "987654321",
    "vodOffset": 780,
    "sessionAssociation": "verified_target_session"
  }]
}
```

The dashboard displays recent clips and supports loading the next page. Each clip is labeled as matched to the target session, from a different stream, or unknown/recent fallback. An empty result is a successful response with `clips: []`. The account must be connected, and the backend needs valid Twitch client credentials to mint its App Access Token. OAuth retains the chat scopes (`user:read:chat`, `user:bot`, `channel:bot`) and also requests `channel:manage:clips` for clip media URLs. If the connected token lacks this scope, chat and the account connection remain available, while media access returns a safe permission error; reconnect and approve the scope to enable it.

#### Memorable moments API

`POST /api/twitch/clips/{clipId}/moments` analyzes one selected clip. It verifies the clip belongs to the connected broadcaster, fetches current clip/VOD metadata, and obtains a transcript from the shared transcription service cache. Cache entries are scoped by broadcaster, clip, and provider/model/options fingerprint; successful entries expire after 30 minutes by default (bounded to 60 seconds–24 hours) and the cache holds at most 100 entries. Text/segments are size-limited and only successful results are cached. Concurrent requests for the same key share one load; no signed media URLs are cached. `POST /api/twitch/clips/{clipId}/transcription?refresh=true` explicitly requests a new transcript, coalesces concurrent refreshes, and replaces the cached result only on success. A failed refresh keeps the prior success until its original expiry. Successful refresh invalidates derived moment snapshots. Set `CLIP_TRANSCRIPTION_CACHE_TTL_SECONDS` and `CLIP_TRANSCRIPTION_CACHE_MAX_ENTRIES` to tune the cache within the server-enforced limits. The response includes safe clip metadata only: no signed media URLs or tokens.

Transcript timestamps are relative to the clip. `createdAt` is not used as capture time. The response reports `association` as `verified_target_session`, `different_session`, or `unknown`. Clip-timed chat evidence is included only if the clip has a nonnegative VOD offset and positive duration, the VOD is an archive owned by the connected broadcaster, its source stream ID matches the stored Twitch session ID, and Twitch's VOD `created_at` matches the independently stored Twitch stream `started_at` to the second. The stored start time is retained across offline events.

When those guards pass, the backend filters stored messages for that stream to the clip's VOD interval, then runs the existing Twitch chat activity/highlight scorer on those clip-local windows. Stored chat timestamps are signed EventSub delivery timestamps, so `chatTimingBasis` identifies `eventsub_delivery_timestamp` and each explanation calls them coarse, potentially lagging context. They are not proof that chat and speech/video activity happened simultaneously and are not frame-accurate synchronization.

When timing cannot be verified, the extraction may include a separate `sessionChatContext` for the connected broadcaster's current or latest stored stream, except when the clip is known to belong to a different session. This is not a clip moment or clip evidence: its association is `unknown`, all times are null, and samples have no UTC timestamps. Retrieval examines at most the latest 500 stored messages and supplies up to 20 deterministic, text-only excerpts of at most 200 characters each. Counts report available, analyzed, supplied, and omitted stored messages. This uses only chat collected and retained by Stream2Song; no Twitch chat history is fetched. If none is stored, collection must have been running earlier. Aligned clip chat scoring remains unchanged, and no chat score is invented for session context.

The deterministic heuristic looks for excited/emotional phrases, repeated substantive transcript phrases, notable-word cues, and the existing aligned chat activity/reaction scores. A lone function word or contraction such as repeated “let’s” is not a repetition cue; a standalone reaction like “pog” can be meaningful. It merges overlapping candidates, keeps supporting transcript/chat evidence, and ranks clip moments on a labeled `0–100` evidence scale, not a probability. It quotes supplied transcript/chat evidence and does not infer a story or event beyond those cues. Missing timestamps remain unavailable; no ranges are invented. If there are no clip moments, `moments` remains empty even when transcript or session chat context is present. Example:

When a transcript is present, a separate `transcriptContext` supplies up to 20 transcript utterances/chunks and 8,000 characters to later interpretation and lyrics steps. Utterances of at most 400 characters are included in full when they fit. Longer or excess material is omitted using deterministic sampling spread across the clip; response counts report omitted utterances/chunks and characters. If a transcript has no segment list, its text is split into untimed 400-character chunks. Only coherent, clip-relative start/end pairs are shown; invalid or unavailable ranges remain null. This is selected-clip transcript context, not a scored or guaranteed memorable moment. `sessionChatContext` remains a distinct untimed chat background with its prior owner/session restrictions. If neither moments nor either context is supplied, interpretation and generation stop.

```bash
curl -X POST 'http://localhost:8080/api/twitch/clips/ExampleClipId/moments'
```

#### AI moment interpretation

Finding moments stores a bounded, immutable server snapshot for 30 minutes and returns an `analysisId`. `POST /api/twitch/clips/{clipId}/moments/interpretation` accepts only `{ "analysisId": "…" }`; it never triggers extraction or transcription. The server checks broadcaster/clip scope and snapshot freshness, sends bounded clip-moment evidence plus separate typed `clip_context` transcript and `session_context` chat inputs where available, then validates every returned item and citation against server references. Context-only snapshots can be interpreted with an empty `moments` list. Selected-clip transcript context keeps valid utterance times in its citations; session chat remains untimed background and is never evidence that a mentioned event occurred in the clip. Instructions ask interpretation to preserve concrete transcript subject and details; this is a model instruction, not a guarantee of output quality. Each explicit interpretation request may incur provider usage; successful AI outputs are not cached. The fixed API endpoint is `https://api.openai.com/v1/responses`, the default model is `gpt-6-luna`, structured output uses a strict JSON schema, tools are disabled, and `store` is false. Provider calls have a 5-second connect timeout, a 50-second request timeout, a 1 MiB response cap, and a four-request concurrency cap.

Interpretation results are explicitly AI-generated and unverified. Moment IDs, source clip, association, times, and cited evidence are copied from the immutable extraction snapshot; the model cannot supply or change them. A successful interpretation is stored server-side under an opaque `interpretationId`, scoped to the connected Twitch account generation, and expires with its analysis snapshot. A later successful interpretation replaces the previous interpretation for that analysis; failures leave the last successful result available. Refreshing the transcript invalidates analysis and interpretation snapshots, and stale/expired/other-account analyses must be extracted again. Transcript and chat text are treated as untrusted input and the model is instructed to ignore embedded instructions, preserve uncertainty, avoid unsupported visual/gameplay claims, and cite supplied evidence. These instructions and schema validation reduce risk but cannot mechanically prove semantic accuracy.

`POST /api/twitch/clips/{clipId}/lyrics` accepts `{ "analysisId": "…", "interpretationId": "…", "genre": "pop", "mood": "playful", "energy": "medium" }`; omitted style values default to pop/playful/medium. The request cannot supply lyrics prompts, artist names, moments, or evidence; the service uses all server-held interpreted clip moments, the bounded full transcript context, and session contexts for that exact clip and snapshot. Fixed genres are `pop`, `rock`, `hip_hop`, `electronic`, `folk`; moods are `playful`, `celebratory`, `reflective`, `dramatic`; energy is `low`, `medium`, or `high`. Lyrics require at least two verses and a chorus, may include one bridge, and are limited to eight sections, bounded lines, and citations. Every returned source time, association, moment ID, and evidence excerpt is copied from the server snapshot. Transcript-context citations retain only valid utterance times and are labeled as clip transcript context, not scored moments; session-context citations remain untimed, have unknown association, and are labeled as background rather than clip events. The prompt asks that transcript-specific subject and details remain central while style shapes the writing; this cannot guarantee a particular semantic quality, and no live generated song quality was verified. The response is labeled AI-generated and unverified. The model receives score-ranked moments, not a presumed chronology; only reliable clip-relative timestamps guide ordering, and unknown associations/times remain explicit. The prompt asks for original, audience-appropriate lyrics, rejects continuation of copyrighted input and imitation of named living artists, and allows metaphor without asserting unsupported events. A lightweight check rejects long exact overlaps with supplied evidence; it is not a general copyright or originality guarantee. Interpretation and lyrics may contain semantic errors despite these safeguards. OpenAI requests use the fixed Responses API, strict structured output, no tools, `store:false`, a 1 MiB response limit, and bounded concurrency; provider usage may incur charges. `OPENAI_API_KEY` is required on the backend, with `OPENAI_MODEL` defaulting to `gpt-6-luna`.

`POST /api/twitch/clips/{clipId}/lyrics/{lyricsId}/spoken-preview` accepts no body or client-provided text. It resolves the current lyrics snapshot for the connected account, reads only its lyric lines, and returns a complete `audio/wav` response with `Cache-Control: no-store`; stale, expired, or other-account IDs are rejected. This creates a spoken read-aloud preview, not sung music, and model speech may differ slightly from the lyric text. The backend uses the OpenAI Realtime API over a server-side WebSocket with `gpt-realtime-2.1-mini`, PCM16 mono at 24 kHz, and voice `marin` by default. It reuses `OPENAI_API_KEY`; optional `OPENAI_SPOKEN_MODEL` and `OPENAI_SPOKEN_VOICE` settings are independent of the interpretation/lyrics model. The fixed instructions treat lyrics as untrusted data and request plain speech without singing or added commentary, but the model is not guaranteed to follow them perfectly. Inputs are capped at 4,096 characters, audio at 8 MiB, active synthesis at three distinct requests, and WebSocket work at a 5-second connect/60-second total deadline. Successful audio is cached in memory for up to 15 minutes (never beyond its lyrics snapshot), across at most 32 entries/64 MiB; concurrent identical requests share one generation. This temporary cache is cleared by process restart and is scoped to the Twitch connection generation. See OpenAI's [Realtime conversations guide](https://developers.openai.com/api/docs/guides/realtime-conversations), [Realtime client events](https://developers.openai.com/api/reference/resources/realtime/client-events), [server events](https://developers.openai.com/api/reference/resources/realtime/server-events), and [GPT-Realtime-2.1 Mini model page](https://developers.openai.com/api/docs/models/gpt-realtime-2.1-mini) for current API details.

On each clip card, **Run all · Generate spoken preview** coordinates the existing transcription, moment extraction, interpretation, lyrics, and spoken-preview endpoints in order. It reuses available clip-local results, shows the active step, stops before interpretation or generation when no clip moments or transcript/chat context is available, and never starts Replicate song generation. If a step fails, retry resumes from still-valid successful results; expired snapshots are cleared and the retry explicitly starts the necessary upstream stages again. After deploying the transcript-context update, restart the backend, refresh the dashboard, and run moment extraction again before interpreting or generating lyrics so old in-memory snapshots are replaced. Manual controls remain available when the pipeline is idle. No additional backend endpoint is introduced for this dashboard shortcut.

#### Music generation

After a lyrics draft is stored, `POST /api/twitch/clips/{clipId}/music-jobs` accepts only `{ "lyricsId": "…" }`. It never accepts lyric text or style overrides. The server adds canonical section tags, rechecks that lyrics belong to the current connection generation and are fresh before provider submission, and creates one idempotent job per connection, clip, lyrics snapshot, and provider fingerprint. `GET /api/twitch/music-jobs/{jobId}` returns `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`, or `EXPIRED`; percentage progress remains null because Replicate does not provide a documented percentage. A paid generation retry is available only for an explicit HTTP 429 rate-limit rejection or a terminal prediction failure, and must include the job's current `expectedAttempt`; known 429 retries have no automatic retry/backoff and remain subject to the three-attempt cap. Provider credential, access, billing, input, and generic rejection errors are permanent. Status-only recovery checks the existing provider prediction and never resubmits generation. Ambiguous submit timeouts/errors are not retried automatically or manually through this endpoint; reconcile them in Replicate before creating another lyrics snapshot. Completed audio is served through the owner-checked `GET /api/twitch/music-jobs/{jobId}/audio` route with byte-range support. Provider IDs and signed URLs are never sent to the browser.

Music generation is disabled by default. Configure `MUSIC_ENABLED=true`, `MUSIC_PROVIDER=replicate`, and the backend-only `REPLICATE_API_TOKEN`. The integration uses Replicate's official asynchronous `minimax/music-2.5` endpoint. It has a 15-minute provider deadline, bounded workers/jobs, a 20 MiB MP3 limit per output, 100 MiB aggregate reserved in-memory audio storage, and up to 30 minutes of temporary playback. Jobs and audio are lost on backend restart, and relinking Twitch creates a new access generation that cannot view the previous generation's jobs. Provider cost is currently listed as $0.15 per output; verify current pricing before enabling. Rights depend on Replicate and MiniMax terms, the user's account entitlement was not tested, and generated audio is not guaranteed original or cleared for a particular use. See [music-provider-research.md](docs/music-provider-research.md) for the current provider and rights research.

#### Chat highlights API

`GET /api/twitch/chat/highlights` ranks possible memorable moments from the messages already stored for the current or most recently completed stream. It does not make an AI request and does not alter or clear chat data.

Optional query parameters:

- `windowSeconds`: length of each non-overlapping time window; defaults to `30`, allowed range `5`–`300`.
- `limit`: maximum number of highlights; defaults to `10`, allowed range `1`–`20`.

Example:

```bash
curl 'http://localhost:8080/api/twitch/chat/highlights?windowSeconds=30&limit=5'
```

Example response:

```json
{
  "streamId": "1234567890",
  "windowSeconds": 30,
  "messagesAnalyzed": 86,
  "heuristicOnly": true,
  "message": null,
  "highlights": [
    {
      "startTime": "2026-10-26T12:03:30Z",
      "endTime": "2026-10-26T12:04:00Z",
      "score": 84.6,
      "messageCount": 24,
      "summary": "Repeated chat reactions: pog, lol.",
      "explanation": "24 messages in 30 seconds; message volume was 3.0× the average of adjacent windows; repeated reactions: pog, lol. This is a chat-activity heuristic, not a verified stream event.",
      "representativeMessages": ["Pog!", "That was wild", "lol"]
    }
  ]
}
```

The deterministic score combines relative message volume (40%), burst size compared with the two adjacent windows, including empty ones (25%), repeated recognized reactions (23%), and repeated meaningful terms (12%). Common filler, URLs, known chatter usernames, and repeated-character tokens are excluded from term scoring. A window needs at least three timestamped messages. Results rank by score and then start time; adjacent windows with the same repeated terms or reactions are collapsed to avoid duplicate suggestions. Representative message text is included without usernames. Chat activity is only a clue: these scores and summaries do not prove that an exciting event occurred.

Messages remain in memory. The current prototype retains only the current or most recently completed stream session ID, so the endpoint scopes analysis to that stream and does not combine different stream IDs. Messages without timestamps are ignored. If no messages are available, the endpoint returns an empty `highlights` array and a descriptive `message`; if messages exist but no window reaches the three-message threshold, it returns a different explanatory `message`.

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
