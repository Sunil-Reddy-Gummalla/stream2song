# Music generation provider decision

## Selected integration

Stream2Song uses Replicate's official asynchronous `minimax/music-2.5` prediction API. The model accepts supplied lyrics plus a style prompt and returns one output URI. The implementation uses the documented model endpoint and prediction lifecycle; it sends `lyrics`, `prompt`, `audio_format: mp3`, `sample_rate: 44100`, and `bitrate: 128000`, with `Cancel-After: 15m`. It does not request synchronous waiting or invent a percentage from elapsed time. Sources: [model description](https://replicate.com/minimax/music-2.5/readme), [input/output schema](https://replicate.com/minimax/music-2.5/api/schema), [create prediction](https://replicate.com/docs/topics/predictions/create-a-prediction), [prediction lifecycle](https://replicate.com/docs/topics/predictions/lifecycle), and [output files](https://replicate.com/docs/topics/predictions/output-files).

The model accepts lyrics of 1–3,500 characters and a prompt up to 2,000 characters. The server adds only canonical `[Verse]`, `[Chorus]`, and `[Bridge]` tags, preserves generated lyric text exactly, and rejects content that exceeds the model limit. The model page currently lists $0.15 per output; confirm the price in Replicate before use because prices can change. Users must configure `MUSIC_ENABLED=true`, `MUSIC_PROVIDER=replicate`, and the backend-only `REPLICATE_API_TOKEN`. Music generation is disabled by default.

Replicate output file URLs are temporary. The server downloads audio immediately over HTTPS from `replicate.delivery` or its dot-boundary subdomains, without forwarding the API token, and keeps MP3 bytes in bounded in-memory storage for up to 30 minutes. Local jobs and audio are ephemeral and are lost on backend restart. Jobs are scoped to the Twitch connection generation that created them. A relinked account cannot view an earlier generation's job or audio, including after switching back to the same broadcaster. A request with ambiguous provider acceptance is not automatically resubmitted; reconcile it in the Replicate dashboard before taking further action.

## Rights and limitations

[Replicate's Terms](https://replicate.com/terms) describe output rights subject to third-party rights. The [MiniMax provider terms](https://platform.minimax.io/protocol/terms-of-service) govern the model's input/output use and include restrictions on standalone resale outside integrated applications. The user's particular account entitlement has not been tested. These terms are not a clearance or originality guarantee; users remain responsible for reviewing rights and intended use. The API request and stored job never expose Replicate tokens, prediction IDs, or signed output URLs to the browser.

## Considered alternative

ElevenLabs Music offers an official API and composition controls, but its commercial and distribution rights depend on the account plan and use case. Its terms also prohibit prompts that identify artists/songwriters or reference particular works and constrain music-library/model-resale uses. This project selected Replicate's documented async prediction flow for this milestone; it did not implement ElevenLabs. See the [Eleven Music API](https://elevenlabs.io/eleven-music-api) and [Music Terms](https://elevenlabs.io/music-terms) for current rules.
