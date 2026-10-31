import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Observable, Subscription, finalize } from 'rxjs';
import { TwitchApiService } from './features/twitch/twitch-api.service';
import { TwitchConnectionStatus } from './features/twitch/twitch-connection.model';
import { TwitchStreamSession } from './features/twitch/twitch-stream-session.model';
import { streamSubscriptionDisplay } from './features/twitch/twitch-subscription-status';
import { TwitchChatMessage, TwitchChatSummary } from './features/twitch/twitch-chat.model';
import { TwitchClip, TwitchClipsResponse } from './features/twitch/twitch-clip.model';
import { ClipTranscriptionResponse } from './features/twitch/clip-transcription.model';
import { clipTranscriptionErrorMessage } from './features/twitch/clip-transcription-error';
import { MemorableMomentsResponse, MemorableMoment } from './features/twitch/memorable-moments.model';
import { memorableMomentsErrorMessage, momentInterpretationErrorMessage } from './features/twitch/memorable-moments-error';
import { MomentInterpretationResponse } from './features/twitch/moment-interpretation.model';
import { ClipAnalysisEpochs } from './features/twitch/clip-analysis-epochs';
import { ClipRequestSubscriptions } from './features/twitch/clip-request-subscriptions';
import { ClipLyricsRequest, ClipLyricsResponse, LyricsEnergy, LyricsGenre, LyricsMood } from './features/twitch/clip-lyrics.model';
import { clipLyricsErrorMessage, formatClipLyricsForCopy, isCurrentLyricsRequest } from './features/twitch/clip-lyrics-format';
import { MusicJobResponse, canResumeMusicStatus, isCurrentMusicJobRequest, isTerminalMusicJob } from './features/twitch/music-job.model';
import { musicCreateSafeMessage, musicJobSafeMessage } from './features/twitch/music-job-error';
import { pollMusicJob } from './features/twitch/music-job-polling';
import { SpokenPreviewAudioRegistry, canGenerateSpokenPreview, isCurrentSpokenPreview, spokenPreviewSafeMessage } from './features/twitch/spoken-preview';
import { ClipPipelineCancelled, ClipPipelineFailure, ClipPipelineStage, canStartClipPipeline, clipPipelineStageLabel, invalidateRemovedClipSources, runClipPipeline } from './features/twitch/clip-pipeline';

type ManualClipRequestKind = 'transcription' | 'moments' | 'interpretation' | 'lyrics';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [DatePipe, FormsModule],
  template: `
    <div class="app-shell">
      <header class="topbar">
        <a class="brand" href="#top" aria-label="Stream2Song home">
          <span class="brand-mark" aria-hidden="true"><i></i><i></i><i></i><i></i><i></i></span>
          <span class="brand-name">Stream<span>2</span>Song</span>
        </a>
        <div class="header-copy">
          <span>Turn your Twitch streams into songs</span>
          <span class="demo-badge"><span></span> Clip workflow</span>
        </div>
      </header>

      <main id="top" class="main-content">
        <section class="hero" aria-labelledby="hero-title">
          <div class="hero-copy">
            <p class="eyebrow"><span class="eyebrow-line"></span> YOUR STREAM, REMIXED</p>
            <h1 id="hero-title">Your best moments.<br><span>In a different key.</span></h1>
            <p class="hero-description">
              Connect Twitch, choose a clip, and turn its memorable moments into lyrics and a spoken preview. You can also request a generated song from a finished lyric draft.
            </p>
          </div>
          <div class="hero-art" aria-hidden="true">
            <div class="art-orbit orbit-one"></div>
            <div class="art-orbit orbit-two"></div>
            <div class="art-disc"><div class="disc-center"><span>♪</span></div></div>
            <div class="art-spark spark-one">✦</div>
            <div class="art-spark spark-two">✳</div>
          </div>
        </section>

        <div class="dashboard-grid">
          <section class="panel twitch-panel" aria-labelledby="twitch-title">
            <div class="twitch-copy">
              <p class="panel-kicker">ACCOUNT CONNECTION</p>
              <h2 id="twitch-title">Connect your Twitch account</h2>
              <p class="twitch-description">Link your channel so Stream2Song can identify your broadcaster account.</p>
            </div>
            <div class="twitch-actions" aria-live="polite">
              @if (twitchLoading()) {
                <span class="twitch-status"><span class="spinner" aria-hidden="true"></span> Checking connection…</span>
              } @else if (twitchStatus()?.connected) {
                <span class="twitch-status connected"><span class="connection-dot"></span> Connected as <strong>{{ twitchStatus()?.login }}</strong></span>
              } @else {
                <a class="twitch-button" [href]="twitchConnectUrl">Connect Twitch <span aria-hidden="true">↗</span></a>
              }
            </div>
            @if (twitchMessage()) {
              <p class="twitch-feedback success" role="status">{{ twitchMessage() }}</p>
            }
            @if (twitchError()) {
              <p class="twitch-feedback error" role="alert">{{ twitchError() }}</p>
            }
            @if (twitchStatus()?.connected) {
              <div class="stream-session" aria-live="polite">
                <span class="stream-state" [class.live]="twitchSession()?.online">
                  <span class="connection-dot"></span>
                  @if (twitchSessionLoading()) {
                    Checking stream status…
                  } @else if (twitchSession()?.online) {
                    Stream is live
                  } @else if (twitchSession()?.eventType === 'stream.offline') {
                    Stream is offline
                  } @else {
                    Waiting for the first stream event
                  }
                </span>
                @if (twitchSession()?.twitchStreamId) {
                  <span class="stream-id">Stream ID · {{ twitchSession()?.twitchStreamId }}</span>
                }
                @if (twitchSession()?.connected) {
                  <div class="chat-summary" aria-live="polite">
                    <span class="chat-count">Chat · {{ chatSummary()?.totalMessages ?? 0 }} messages</span>
                    <span class="chat-state" [class.chat-active]="chatSummary()?.collecting"
                      [class.chat-error]="chatStatusIsError(chatSummary()?.collectionStatus)">
                      {{ chatStatusLabel(chatSummary()?.collectionStatus) }}
                    </span>
                  </div>
                  @if (chatError()) {
                    <span class="stream-hint error" role="alert">{{ chatError() }}</span>
                  } @else if (chatLoading() && chatMessages().length === 0) {
                    <span class="stream-hint">Loading recent chat…</span>
                  } @else if (chatMessages().length > 0) {
                    <div class="chat-messages" aria-label="Latest Twitch chat messages">
                      @for (message of chatMessages(); track message.messageId) {
                        <div class="chat-message">
                          <strong>{{ message.username }}</strong>
                          <span>{{ message.text }}</span>
                          <time [attr.datetime]="message.timestamp">{{ message.timestamp | date:'shortTime' }}</time>
                        </div>
                      }
                    </div>
                  } @else {
                    <span class="stream-hint">No chat messages collected for this stream yet.</span>
                  }
                }
                @if (streamEventSubDisplay(); as eventSub) {
                  <span class="stream-hint" [class.error]="eventSub.error">
                    <strong>{{ eventSub.label }}.</strong> {{ eventSub.description }}
                  </span>
                }
                @if (streamEventSubDisplay()?.error && streamEventSubDisplay()?.state !== 'conflict_unresolved') {
                  <a class="session-refresh" [href]="twitchConnectUrl">Retry EventSub setup</a>
                }
                <button class="session-refresh" type="button" (click)="loadTwitchSession(true)" [disabled]="twitchSessionLoading()">{{ twitchSessionLoading() ? 'Refreshing…' : 'Refresh status' }}</button>
              </div>
              <section class="clips-section" aria-labelledby="clips-title">
                <div class="clips-heading">
                  <h3 id="clips-title">Recent Twitch clips</h3>
                  @if (clipsPage()?.associationNote) { <span class="stream-hint">{{ clipsPage()?.associationNote }}</span> }
                </div>
                @if (clipsError()) {
                  <span class="stream-hint error" role="alert">{{ clipsError() }}</span>
                  <button class="session-refresh" type="button" (click)="loadClips()">Try again</button>
                } @else if (clipsLoading() && clips().length === 0) {
                  <span class="stream-hint" role="status">Loading recent clips…</span>
                } @else if (clipsLoaded() && clips().length === 0) {
                  <span class="stream-hint">No recent clips were found for this broadcaster.</span>
                } @else {
                  <div class="clip-list">
                    @for (clip of clips(); track clip.id) {
                      <article class="clip-card">
                        <a [href]="clip.url" target="_blank" rel="noopener noreferrer" [attr.aria-label]="'Open Twitch clip: ' + clip.title">
                          <img [src]="clip.thumbnailUrl" [alt]="''" loading="lazy">
                        </a>
                        <div><a class="clip-title" [href]="clip.url" target="_blank" rel="noopener noreferrer">{{ clip.title || 'Untitled clip' }}</a>
                          <span class="stream-hint">{{ clip.createdAt | date:'medium' }} · {{ formatClipDuration(clip.duration) }}</span>
                          <span class="clip-association" [class.clip-verified]="clip.sessionAssociation === 'verified_target_session'">{{ clipAssociationLabel(clip.sessionAssociation) }}</span>
                          <button class="session-refresh clip-transcribe" type="button" (click)="transcribeClip(clip.id)"
                            [disabled]="isClipTranscribing(clip.id) || isRunningClipPipeline(clip.id)">{{ isClipTranscribing(clip.id) ? 'Transcribing…' : (clipTranscriptionError(clip.id) ? 'Try transcription again' : 'Transcribe audio') }}</button>
                          @if (clipTranscription(clip.id)) {
                            <button class="session-refresh clip-transcribe" type="button" (click)="transcribeClip(clip.id, true)"
                              [disabled]="isClipTranscribing(clip.id) || isRunningClipPipeline(clip.id)">{{ isClipTranscribing(clip.id) ? 'Refreshing…' : 'Refresh transcript' }}</button>
                          }
                        </div>
                        @if (clipTranscriptionError(clip.id); as transcriptionError) {
                          <span class="stream-hint error clip-transcription-error" role="alert">{{ transcriptionError }}</span>
                        }
                        @if (clipTranscription(clip.id); as transcript) {
                          <div class="clip-transcript" aria-label="Clip transcript">
                            <p>{{ transcript.text || 'No speech was detected.' }}</p>
                            @for (segment of transcript.segments; track $index) {
                              <div class="clip-transcript-segment">
                                <time>{{ formatTranscriptTime(segment.startSeconds) }}–{{ formatTranscriptTime(segment.endSeconds) }}</time>
                                <span>{{ segment.text }}</span>
                              </div>
                            }
                          </div>
                        }
                        <button class="session-refresh clip-moments-button" type="button" (click)="findClipMoments(clip.id)"
                          [disabled]="isFindingClipMoments(clip.id) || isRunningClipPipeline(clip.id)">{{ isFindingClipMoments(clip.id) ? 'Finding moments…' : (clipMomentsError(clip.id) ? 'Try moments again' : 'Find memorable moments') }}</button>
                        <button class="session-refresh clip-moments-button" type="button" (click)="runClipGenerationPipeline(clip.id)"
                          [disabled]="!canStartClipPipeline(clip.id)">{{ isRunningClipPipeline(clip.id) ? 'Working: ' + clipPipelineStageLabel(clip.id) + '…' : (clipPipelineErrors().has(clip.id) ? 'Retry from saved results' : 'Run all · Generate spoken preview') }}</button>
                        @if (isRunningClipPipeline(clip.id)) { <span class="stream-hint" role="status">Current step: {{ clipPipelineStageLabel(clip.id) }}.</span> }
                        @if (clipPipelineErrors().get(clip.id); as pipelineError) { <span class="stream-hint error" role="alert">Stopped during {{ clipPipelineFailedStageLabel(clip.id) }}: {{ pipelineError }}</span> }
                        @if (clipPipelineEmpty().has(clip.id)) { <span class="stream-hint" role="status">No memorable evidence was found. Nothing was sent for interpretation or generation.</span> }
                        @if (clipMomentsError(clip.id); as momentsError) {
                          <span class="stream-hint error clip-moments-error" role="alert">{{ momentsError }}</span>
                        }
                        @if (clipMoments(clip.id); as momentsResult) {
                          <div class="clip-moments" aria-label="Memorable moments">
                            <div class="moments-context">
                              <strong>Association: {{ momentAssociationLabel(momentsResult.association) }}</strong>
                              <span>{{ momentsResult.chatCorrelationExplanation }}</span>
                              @if (momentsResult.chatTimingBasis) { <span>Chat time basis: EventSub delivery timestamps (coarse context that may lag speech).</span> }
                            </div>
                            @if (momentsResult.message) {
                              <span class="stream-hint">{{ momentsResult.message }}</span>
                            } @else {
                              @for (moment of momentsResult.moments; track moment.id) {
                                <div class="memorable-moment">
                                  <p class="moment-description">{{ moment.description }}</p>
                                  <span class="moment-score">Heuristic ranking · {{ moment.score }}/100</span>
                                  <span class="moment-time">{{ formatMomentRange(moment) }}</span>
                                  @if (moment.transcriptEvidence.length > 0) {
                                    <div class="moment-evidence">
                                      <strong>Transcript evidence</strong>
                                      @for (evidence of moment.transcriptEvidence; track $index) {
                                        <span>{{ evidence.text }}
                                          @if (evidence.startSeconds !== null && evidence.endSeconds !== null) {
                                            <time> · {{ formatTranscriptTime(evidence.startSeconds) }}–{{ formatTranscriptTime(evidence.endSeconds) }}</time>
                                          } @else { <time> · Time unavailable</time> }
                                        </span>
                                      }
                                    </div>
                                  }
                                  @for (evidence of moment.chatEvidence; track evidence.windowStartUtc) {
                                    <div class="moment-evidence chat-moment-evidence">
                                      <strong>Chat activity · {{ evidence.score }}/100</strong>
                                      <span>{{ evidence.summary }}</span>
                                      <span>UTC window {{ evidence.windowStartUtc }}–{{ evidence.windowEndUtc }} · clip-relative
                                        @if (evidence.startSeconds !== null && evidence.endSeconds !== null) {
                                          {{ formatTranscriptTime(evidence.startSeconds) }}–{{ formatTranscriptTime(evidence.endSeconds) }}
                                        } @else { Time unavailable }
                                      </span>
                                      <span>{{ evidence.explanation }}</span>
                                      @for (message of evidence.messages; track $index) { <q>{{ message }}</q> }
                                    </div>
                                  }
                                  <span class="moment-selection">{{ moment.selectionExplanation }}</span>
                                </div>
                              }
                            }
                            @if (momentsResult.transcriptContext.suppliedCount > 0) {
                              <section class="session-chat-context" aria-label="Selected clip transcript context">
                                <strong>Selected clip transcript context · not a scored moment</strong>
                                <span>{{ momentsResult.transcriptContext.sourceType === 'full_text_fallback' ? 'Transcript text fallback' : 'Analyzed ' + momentsResult.transcriptContext.analyzedCount + ' of ' + momentsResult.transcriptContext.availableCount + ' utterances' }}; supplied {{ momentsResult.transcriptContext.suppliedCount }} chunks ({{ momentsResult.transcriptContext.suppliedCharacters }} characters); omitted {{ momentsResult.transcriptContext.truncatedCount }} chunks/utterances and {{ momentsResult.transcriptContext.truncatedCharacters }} characters. Association: {{ momentAssociationLabel(momentsResult.transcriptContext.association) }}.</span>
                                @for (utterance of momentsResult.transcriptContext.utterances; track utterance.id) {
                                  <q>{{ utterance.startSeconds !== null && utterance.endSeconds !== null ? formatTranscriptTime(utterance.startSeconds) + '–' + formatTranscriptTime(utterance.endSeconds) + ' · ' : 'Time unavailable · ' }}{{ utterance.text }}</q>
                                }
                              </section>
                            }
                            @if (momentsResult.sessionChatContext.suppliedCount > 0) {
                              <section class="session-chat-context" aria-label="Unaligned session chat context">
                                <strong>Recent session chat · background only, not clip evidence</strong>
                                <span>Analyzed {{ momentsResult.sessionChatContext.analyzedCount }} of {{ momentsResult.sessionChatContext.availableCount }} stored chat messages; supplied {{ momentsResult.sessionChatContext.suppliedCount }} text-only samples; omitted {{ momentsResult.sessionChatContext.truncatedCount }}. Samples have no clip-relative or UTC time.</span>
                                @for (sample of momentsResult.sessionChatContext.messages; track sample.id) { <q>{{ sample.text }}</q> }
                              </section>
                            } @else if (momentsResult.chatCorrelation === 'unaligned' && momentsResult.association !== 'different_session') {
                              <span class="stream-hint">No recent stored session chat is available for context. Chat collection must have been running earlier; Twitch history is not fetched.</span>
                            }
                            @if (canInterpretClip(clip.id)) {
                              <button class="session-refresh clip-interpret-button" type="button" (click)="interpretClipMoments(clip.id)"
                                [disabled]="isInterpretingClipMoments(clip.id) || isRunningClipPipeline(clip.id)">{{ isInterpretingClipMoments(clip.id) ? 'Interpreting…' : (clipInterpretationError(clip.id) ? 'Retry interpretation' : 'Interpret with AI') }}</button>
                              @if (clipInterpretationError(clip.id); as interpretationError) {
                                <span class="stream-hint error" role="alert">{{ interpretationError }}</span>
                              }
                              @if (clipInterpretation(clip.id); as interpretation) {
                                <div class="moment-interpretations" aria-label="AI moment interpretations">
                                  <strong>AI generated interpretation · unverified</strong>
                                  @for (item of interpretation.moments; track item.momentId) {
                                    <section class="moment-interpretation">
                                      <p>{{ item.summary }}</p>
                                      <span><b>Emotional tone:</b> {{ item.emotionalTone }}</span>
                                      <span><b>Humor / excitement:</b> {{ item.humorExcitement }}</span>
                                      <span><b>Narrative significance:</b> {{ item.narrativeSignificance }}</span>
                                      <span>Source time · {{ item.startSeconds !== null && item.endSeconds !== null ? formatTranscriptTime(item.startSeconds) + '–' + formatTranscriptTime(item.endSeconds) : 'Time unavailable' }}</span>
                                      <div class="moment-evidence"><b>Cited source evidence</b>
                                        @for (citation of item.evidence; track citation.id) { <span>{{ citation.excerpt }} · {{ citation.kind }}</span> }
                                      </div>
                                    </section>
                                  }
                                  @if (interpretation.sessionContexts.length > 0) {
                                    <section class="session-chat-context" aria-label="AI interpretation of session chat context">
                                      <strong>Session chat background · not evidence from this clip</strong>
                                      @for (context of interpretation.sessionContexts; track context.contextId) {
                                        <div class="moment-interpretation">
                                          <p>{{ context.summary }}</p>
                                          <span><b>Possible tone:</b> {{ context.emotionalTone }}</span>
                                          <span><b>Humor / excitement:</b> {{ context.humorExcitement }}</span>
                                          <span><b>Context significance:</b> {{ context.narrativeSignificance }}</span>
                                          <span>Association unknown · timing unavailable · not tied to a clip event</span>
                                          @for (citation of context.evidence; track citation.id) { <q>{{ citation.excerpt }} · session chat context</q> }
                                        </div>
                                      }
                                    </section>
                                  }
                                  @if (interpretation.transcriptContexts.length > 0) {
                                    <section class="session-chat-context" aria-label="AI interpretation of selected clip transcript context">
                                      <strong>Selected clip transcript context · AI interpretation, not a scored moment</strong>
                                      @for (context of interpretation.transcriptContexts; track context.contextId) {
                                        <div class="moment-interpretation">
                                          <p>{{ context.summary }}</p>
                                          <span><b>Emotional tone:</b> {{ context.emotionalTone }}</span>
                                          <span><b>Humor / excitement:</b> {{ context.humorExcitement }}</span>
                                          <span><b>Narrative significance:</b> {{ context.narrativeSignificance }}</span>
                                          <span>Transcript association: {{ momentAssociationLabel(context.association) }} · context spans multiple utterances, so no single range is shown.</span>
                                          @for (citation of context.evidence; track citation.id) { <q>{{ citation.startSeconds !== null && citation.endSeconds !== null ? formatTranscriptTime(citation.startSeconds) + '–' + formatTranscriptTime(citation.endSeconds) + ' · ' : 'Time unavailable · ' }}{{ citation.excerpt }}</q> }
                                        </div>
                                      }
                                    </section>
                                  }
                                </div>
                                <div class="lyrics-controls" aria-label="Lyrics style">
                                  <label>Genre <select [ngModel]="lyricsGenre(clip.id)" (ngModelChange)="setLyricsGenre(clip.id, $event)" [disabled]="isGeneratingLyrics(clip.id) || isRunningClipPipeline(clip.id)"><option value="pop">Pop</option><option value="rock">Rock</option><option value="hip_hop">Hip hop</option><option value="electronic">Electronic</option><option value="folk">Folk</option></select></label>
                                  <label>Mood <select [ngModel]="lyricsMood(clip.id)" (ngModelChange)="setLyricsMood(clip.id, $event)" [disabled]="isGeneratingLyrics(clip.id) || isRunningClipPipeline(clip.id)"><option value="playful">Playful</option><option value="celebratory">Celebratory</option><option value="reflective">Reflective</option><option value="dramatic">Dramatic</option></select></label>
                                  <label>Energy <select [ngModel]="lyricsEnergy(clip.id)" (ngModelChange)="setLyricsEnergy(clip.id, $event)" [disabled]="isGeneratingLyrics(clip.id) || isRunningClipPipeline(clip.id)"><option value="low">Low</option><option value="medium">Medium</option><option value="high">High</option></select></label>
                                </div>
                                <button class="session-refresh clip-interpret-button" type="button" (click)="generateClipLyrics(clip.id)" [disabled]="isGeneratingLyrics(clip.id) || isRunningClipPipeline(clip.id)">{{ isGeneratingLyrics(clip.id) ? 'Writing lyrics…' : (clipLyricsError(clip.id) ? 'Retry lyrics' : 'Generate lyrics') }}</button>
                                @if (clipLyricsError(clip.id); as lyricsError) { <span class="stream-hint error" role="alert">{{ lyricsError }}</span> }
                                @if (clipLyrics(clip.id); as lyrics) {
                                  <section class="clip-lyrics" aria-label="AI generated lyrics">
                                    <strong>AI generated lyrics · unverified</strong><span>Clip association: {{ momentAssociationLabel(lyrics.association === 'verified_target_session' ? 'verified_target_session' : lyrics.association === 'different_session' ? 'different_session' : 'unknown') }}</span><span>{{ lyrics.chatCorrelationExplanation }}</span>@if (lyrics.chatTimingBasis === 'eventsub_delivery_timestamp') { <span>Chat citations use coarse EventSub delivery times and may lag speech; they are not synchronized to audio or video.</span> }<h4>{{ lyrics.title }}</h4>
                                    @for (section of lyrics.sections; track $index) {
                                      <div class="lyrics-section"><b>{{ section.label }}</b>@for (line of section.lines; track $index) { <span>{{ line }}</span> }
                                        <small>Sources: @for (source of section.sources; track source.momentId) { <span>{{ source.momentId }} · {{ source.startSeconds !== null && source.endSeconds !== null ? formatTranscriptTime(source.startSeconds) + '–' + formatTranscriptTime(source.endSeconds) : 'Time unavailable' }} · {{ momentAssociationLabel(source.association === 'verified_target_session' ? 'verified_target_session' : source.association === 'different_session' ? 'different_session' : 'unknown') }} </span> }</small>
                                        @for (source of section.sources; track source.momentId) { @if (isSessionChatSource(source)) { <span>Session chat background · not evidence of a clip event; time unavailable.</span> } @if (isClipTranscriptContextSource(source)) { <span>Selected clip transcript context · not a scored moment.</span> } @for (evidence of source.evidence; track evidence.id) { <q>{{ evidence.startSeconds !== null && evidence.endSeconds !== null ? formatTranscriptTime(evidence.startSeconds) + '–' + formatTranscriptTime(evidence.endSeconds) + ' · ' : '' }}{{ evidence.excerpt }}</q> } }
                                      </div>
                                    }
                                    <button class="session-refresh" type="button" (click)="copyClipLyrics(clip.id)" [disabled]="isCopyingLyrics(clip.id)">{{ isCopyingLyrics(clip.id) ? 'Copying…' : 'Copy lyrics' }}</button>
                                    @if (lyricsCopyMessage(clip.id); as copyMessage) { <span role="status">{{ copyMessage }}</span> }
                                    <button class="session-refresh" type="button" (click)="generateSpokenPreview(clip.id)" [disabled]="!canGenerateSpokenPreview(clip.id) || isRunningClipPipeline(clip.id)">{{ isGeneratingSpokenPreview(clip.id) ? 'Generating spoken preview…' : (spokenPreviewAudioUrl(clip.id) ? 'Regenerate spoken preview' : 'Generate spoken preview') }}</button>
                                    <small>AI-generated speech reads the lyrics aloud; this is not sung music, and the spoken words may differ slightly from the text.</small>
                                    @if (spokenPreviewError(clip.id); as spokenError) { <span class="stream-hint error" role="alert">{{ spokenError }}</span> }
                                    @if (spokenPreviewAudioUrl(clip.id); as spokenAudio) { <audio controls preload="none" [src]="spokenAudio">Your browser does not support audio playback.</audio> }
                                    @if (clipMusicJob(clip.id); as musicJob) {
                                      <div class="music-job" aria-live="polite"><strong>Song generation · {{ musicJob.status }}</strong>
                                        @if (musicJob.progressPercent !== null) { <span>{{ musicJob.progressPercent }}% complete</span> }
                                        @if (musicJob.reason; as musicReason) { <span role="status">{{ musicJobMessage(musicReason) }}</span> }
                                        @if (musicJob.status === 'COMPLETED' && musicJob.audioPath) { <audio controls preload="none" [src]="musicAudioUrl(musicJob)">Your browser does not support audio playback.</audio> }
                                        @if (musicJob.status === 'FAILED' && musicJob.retryable) { <button class="session-refresh" type="button" (click)="retryMusicJob(clip.id)" [disabled]="isCreatingMusic(clip.id) || isRunningClipPipeline(clip.id)">{{ musicJob.retryMode === 'STATUS' ? 'Check provider status again' : 'Retry generation (attempt ' + (musicJob.attempt + 1) + ')' }}</button> }
                                        @if (canResumeMusicStatus(clip.id)) { <button class="session-refresh" type="button" (click)="resumeMusicStatus(clip.id)" [disabled]="isCreatingMusic(clip.id) || isRunningClipPipeline(clip.id)">{{ isCreatingMusic(clip.id) ? 'Checking status…' : 'Resume status checks' }}</button> }
                                      </div>
                                    } @else {
                                      <button class="session-refresh" type="button" (click)="createMusicJob(clip.id)" [disabled]="isCreatingMusic(clip.id) || isRunningClipPipeline(clip.id)">{{ isCreatingMusic(clip.id) ? 'Starting song…' : 'Generate song' }}</button>
                                    }
                                    @if (clipMusicError(clip.id); as musicError) { <span class="stream-hint error" role="alert">{{ musicError }}</span> }
                                  </section>
                                }
                              }
                            }
                          </div>
                        }
                      </article>
                    }
                  </div>
                  @if (clipsPage()?.nextCursor) {
                    <button class="session-refresh" type="button" (click)="loadMoreClips()" [disabled]="clipsLoading()">{{ clipsLoading() ? 'Loading…' : 'Load more clips' }}</button>
                  }
                }
              </section>
            }
          </section>

        </div>

        <footer class="page-footer">
          <span>Made for the moments your chat won’t let you forget.</span>
          <span class="footer-mark">S2S <span>✦</span></span>
        </footer>
      </main>
    </div>
  `,
  styles: [`
    :host { display: block; min-height: 100vh; }
    .app-shell { min-height: 100vh; overflow: hidden; }
    .topbar { align-items: center; border-bottom: 1px solid var(--line); display: flex; justify-content: space-between; margin: 0 auto; max-width: 1440px; padding: 1.1rem clamp(1.25rem, 5vw, 4.5rem); }
    .brand { align-items: center; color: var(--text); display: inline-flex; gap: .75rem; text-decoration: none; }
    .brand-mark { align-items: center; background: linear-gradient(135deg, #9b71f5, #7445d5); border-radius: 12px; box-shadow: 0 5px 18px #885be14a; display: flex; gap: 2px; height: 38px; justify-content: center; width: 38px; }
    .brand-mark i { animation: equalize 1s ease-in-out infinite alternate; background: #fff; border-radius: 2px; height: 9px; width: 2px; }
    .brand-mark i:nth-child(2), .brand-mark i:nth-child(4) { animation-delay: .2s; height: 15px; }
    .brand-mark i:nth-child(3) { animation-delay: .4s; height: 21px; }
    .brand-name { font-size: 1.05rem; font-weight: 760; letter-spacing: -.045em; }
    .brand-name span { color: var(--purple-light); }
    .header-copy { align-items: center; color: var(--muted); display: flex; font-size: .82rem; gap: 1.4rem; }
    .demo-badge { align-items: center; background: #1d1930; border: 1px solid #37304a; border-radius: 99px; color: #c3b3e4; display: inline-flex; font-size: .72rem; gap: .45rem; padding: .42rem .68rem; }
    .demo-badge span { background: #a77aff; border-radius: 50%; height: 6px; width: 6px; }
    .main-content { margin: 0 auto; max-width: 1200px; padding: 0 clamp(1.25rem, 5vw, 4.5rem); }
    .hero { align-items: center; display: flex; justify-content: space-between; min-height: 280px; padding: 2.2rem 1.5rem 2.2rem 0; position: relative; }
    .hero-copy { max-width: 650px; position: relative; z-index: 1; }
    .eyebrow, .panel-kicker { color: var(--purple-light); font-size: .68rem; font-weight: 750; letter-spacing: .16em; margin: 0; }
    .eyebrow { align-items: center; display: flex; gap: .55rem; }
    .eyebrow-line { background: var(--purple); display: inline-block; height: 1px; width: 22px; }
    h1 { font-size: clamp(2.5rem, 5.6vw, 4.45rem); letter-spacing: -.065em; line-height: 1.03; margin: 1rem 0 .8rem; }
    h1 span { color: #b698fa; }
    .hero-description { color: var(--muted); font-size: .97rem; line-height: 1.7; margin: 0; max-width: 500px; }
    .hero-art { height: 220px; margin-right: 2rem; position: relative; width: 260px; }
    .art-orbit { border: 1px solid #8b63dc35; border-radius: 50%; left: 50%; position: absolute; top: 50%; transform: translate(-50%, -50%) rotate(-24deg); }
    .orbit-one { height: 130px; width: 250px; }
    .orbit-two { height: 185px; transform: translate(-50%, -50%) rotate(28deg); width: 210px; }
    .art-disc { align-items: center; background: repeating-radial-gradient(circle, #242035 0 4px, #302a40 5px 6px, #211d31 7px 10px); border: 1px solid #ffffff17; border-radius: 50%; box-shadow: 0 18px 55px #0008, inset 0 0 30px #0008; display: flex; height: 140px; justify-content: center; left: 50%; position: absolute; top: 50%; transform: translate(-50%, -50%) rotate(-18deg); width: 140px; }
    .disc-center { align-items: center; background: linear-gradient(145deg, #a780fb, #7646d4); border: 4px solid #272237; border-radius: 50%; box-shadow: 0 0 24px #a780fb40; color: white; display: flex; font-size: 1.8rem; height: 48px; justify-content: center; width: 48px; }
    .art-spark { color: #bda2fb; position: absolute; text-shadow: 0 0 15px #a780fb99; }
    .spark-one { font-size: 1.25rem; right: 19px; top: 24px; }
    .spark-two { bottom: 31px; font-size: .95rem; left: 15px; }
    .dashboard-grid { align-items: stretch; display: grid; gap: 1.1rem; grid-template-columns: 1fr; }
    .twitch-panel { align-items: center; display: flex; flex-wrap: wrap; gap: .8rem 1.5rem; grid-column: 1 / -1; justify-content: space-between; padding: 1.1rem 1.35rem; }
    .twitch-copy h2 { color: #eee9f6; font-size: .95rem; margin: .28rem 0 .2rem; }
    .twitch-description { color: var(--muted); font-size: .72rem; margin: 0; }
    .twitch-actions { align-items: center; display: flex; justify-content: flex-end; }
    .twitch-button { align-items: center; background: #7040c8; border: 1px solid #9d7be0; border-radius: 8px; color: #fff; display: inline-flex; font-size: .75rem; font-weight: 700; gap: .65rem; padding: .65rem .9rem; text-decoration: none; transition: filter .15s; }
    .twitch-button:hover { filter: brightness(1.12); }
    .twitch-status { align-items: center; color: #c7bed5; display: inline-flex; font-size: .76rem; gap: .45rem; }
    .twitch-status.connected { color: #a8e3c4; }
    .twitch-status strong { color: #d7f5e4; }
    .connection-dot { background: #5fd194; border-radius: 50%; box-shadow: 0 0 10px #5fd19480; height: 7px; width: 7px; }
    .twitch-feedback { flex-basis: 100%; font-size: .7rem; margin: 0; }
    .twitch-feedback.success { color: #9de0bf; }
    .twitch-feedback.error { color: #ffabb5; }
    .stream-session { align-items: center; border-top: 1px solid #302b3a; display: flex; flex-basis: 100%; flex-wrap: wrap; gap: .45rem 1rem; padding-top: .75rem; }
    .stream-state { align-items: center; color: #b6afc4; display: inline-flex; font-size: .72rem; gap: .45rem; }
    .stream-state .connection-dot { background: #817b8e; box-shadow: none; }
    .stream-state.live { color: #a8e3c4; }
    .stream-state.live .connection-dot { background: #5fd194; box-shadow: 0 0 10px #5fd19480; }
    .stream-id, .stream-hint { color: #898394; font-size: .66rem; }
    .stream-hint.error { color: #ffabb5; }
    .chat-summary { align-items: center; border-top: 1px solid #302b3a; display: flex; flex-basis: 100%; gap: .75rem; padding-top: .55rem; }
    .chat-count, .chat-state { color: #a9a1b7; font-size: .68rem; }
    .chat-active { color: #8cdeb2; }
    .chat-error { color: #ffabb5; }
    .chat-messages { display: grid; flex-basis: 100%; gap: .35rem; max-height: 180px; overflow: auto; }
    .chat-message { align-items: baseline; background: #11101980; border-radius: 6px; display: flex; font-size: .7rem; gap: .5rem; min-width: 0; padding: .4rem .55rem; }
    .chat-message strong { color: #c7a9ff; flex: 0 0 auto; }
    .chat-message span { color: #d5d0df; overflow-wrap: anywhere; }
    .chat-message time { color: #817b8e; flex: 0 0 auto; font-size: .62rem; margin-left: auto; }
    .clips-section { border-top: 1px solid #302b3a; display: grid; flex-basis: 100%; gap: .55rem; padding-top: .7rem; }
    .clips-heading { align-items: baseline; display: flex; flex-wrap: wrap; gap: .6rem; }
    .clips-heading h3 { color: #d9d1e7; font-size: .75rem; margin: 0; }
    .clip-list { display: grid; gap: .45rem; grid-template-columns: repeat(auto-fit, minmax(230px, 1fr)); }
    .clip-card { align-items: center; background: #11101980; border-radius: 7px; display: flex; gap: .6rem; min-width: 0; padding: .4rem; }
    .clip-card img { aspect-ratio: 16 / 9; border-radius: 4px; display: block; object-fit: cover; width: 82px; }
    .clip-card > div { display: grid; gap: .25rem; min-width: 0; }
    .clip-title { color: #d5d0df; font-size: .7rem; overflow: hidden; text-decoration: none; text-overflow: ellipsis; white-space: nowrap; }
    .clip-title:hover { color: #c7a9ff; }
    .clip-association { color: #9c93aa; font-size: .62rem; }
    .clip-association.clip-verified { color: #8cdeb2; }
    .clip-card { flex-wrap: wrap; }
    .clip-transcribe { justify-self: start; }
    .clip-transcription-error { flex-basis: 100%; }
    .clip-transcript { background: #0d0c1280; border-radius: 6px; display: grid; flex-basis: 100%; gap: .3rem; padding: .5rem; }
    .clip-transcript p { color: #e2dbea; font-size: .72rem; margin: 0; overflow-wrap: anywhere; }
    .clip-transcript-segment { align-items: baseline; display: flex; font-size: .66rem; gap: .5rem; }
    .clip-transcript-segment time { color: #a99ebd; flex: 0 0 auto; font-variant-numeric: tabular-nums; }
    .clip-transcript-segment span { color: #c9c2d2; overflow-wrap: anywhere; }
    .clip-moments-button { justify-self: start; }
    .clip-moments-error { flex-basis: 100%; }
    .clip-moments { background: #0d0c1280; border-radius: 6px; display: grid; flex-basis: 100%; gap: .5rem; padding: .55rem; }
    .moments-context, .moment-evidence { display: grid; gap: .2rem; }
    .moments-context strong, .moment-evidence strong { color: #c7a9ff; font-size: .68rem; }
    .moments-context span, .moment-evidence span, .moment-selection { color: #a9a1b7; font-size: .64rem; overflow-wrap: anywhere; }
    .memorable-moment { border-top: 1px solid #302b3a; display: grid; gap: .3rem; padding-top: .45rem; }
    .moment-description { color: #e2dbea; font-size: .73rem; margin: 0; overflow-wrap: anywhere; }
    .moment-score { color: #8cdeb2; font-size: .63rem; }
    .moment-time { color: #a99ebd; font-size: .64rem; }
    .moment-evidence q { color: #d5d0df; font-size: .64rem; overflow-wrap: anywhere; }
    .moment-interpretations, .session-chat-context { border-top: 1px solid #302b3a; display: grid; gap: .45rem; padding-top: .45rem; }
    .moment-interpretations > strong, .session-chat-context > strong { color: #d8c5ff; font-size: .68rem; }
    .moment-interpretation { display: grid; gap: .3rem; }
    .moment-interpretation p { color: #e2dbea; font-size: .7rem; margin: 0; }
    .moment-interpretation > span { color: #a9a1b7; font-size: .64rem; overflow-wrap: anywhere; }
    .lyrics-controls { display:flex; gap:.5rem; flex-wrap:wrap; font-size:.68rem; }
    .lyrics-controls label { display:grid; gap:.2rem; color:#c9c1d3; }
    .lyrics-controls select { background:#18151e; color:#eee; border:1px solid #494253; border-radius:.35rem; padding:.3rem; }
    .clip-lyrics { display:grid; gap:.55rem; border-top:1px solid #302b3a; padding-top:.55rem; }
    .clip-lyrics > strong { color:#d8c5ff; font-size:.68rem; }
    .clip-lyrics h4 { margin:0; }
    .lyrics-section { display:grid; gap:.2rem; white-space:pre-line; }
    .lyrics-section small { color:#a9a1b7; display:grid; }
    .music-job { display:grid; gap:.4rem; border-top:1px solid #302b3a; padding-top:.5rem; }
    .music-job strong { color:#d8c5ff; font-size:.72rem; }
    .music-job audio { width:100%; }
    .session-refresh { background: transparent; border: 0; color: #b69ae8; cursor: pointer; font: inherit; font-size: .66rem; padding: .2rem; }
    .session-refresh:disabled { cursor: wait; opacity: .55; }
    .panel { background: linear-gradient(150deg, #191725, #17151f); border: 1px solid var(--line); border-radius: 16px; box-shadow: 0 12px 40px #00000014; padding: 1.5rem; }
    .panel-kicker { font-size: .59rem; margin-bottom: .28rem; }
    h2 { color: var(--text); font-size: 1rem; letter-spacing: -.025em; margin: 0; }
    label { color: #e4e0ec; display: block; font-size: .76rem; font-weight: 650; margin-bottom: .52rem; }
    .spinner { animation: spin .85s linear infinite; border: 2px solid #ffffff50; border-radius: 50%; border-top-color: #fff; display: inline-block; height: 14px; width: 14px; }
    .page-footer { align-items: center; color: #777182; display: flex; font-size: .69rem; justify-content: space-between; padding: 1.6rem 0 2rem; }
    .footer-mark { color: #93889f; font-size: .65rem; font-weight: 800; letter-spacing: .1em; }
    .footer-mark span { color: #a77aff; }
    @keyframes spin { to { transform: rotate(360deg); } }
    @keyframes equalize { to { transform: scaleY(.55); } }
    @media (max-width: 760px) {
      .hero { min-height: auto; padding: 3rem 0 2rem; }
      .hero-art { margin-right: -1.4rem; opacity: .43; position: absolute; right: 0; transform: scale(.78); transform-origin: center right; }
      .hero-copy { max-width: 550px; }
      .dashboard-grid { grid-template-columns: 1fr; }
    }
    @media (max-width: 500px) {
      .topbar { align-items: flex-start; gap: .75rem; padding-bottom: .85rem; padding-top: .85rem; }
      .brand { gap: .5rem; }
      .brand-mark { border-radius: 10px; height: 34px; width: 34px; }
      .brand-name { font-size: .93rem; }
      .header-copy { align-items: flex-end; flex-direction: column; font-size: .62rem; gap: .35rem; text-align: right; }
      .demo-badge { font-size: .61rem; padding: .3rem .5rem; }
      .hero { padding-top: 2.5rem; }
      h1 { font-size: clamp(2.3rem, 11vw, 3.2rem); }
      .hero-description { font-size: .84rem; max-width: 90%; }
      .hero-art { opacity: .23; right: -.8rem; transform: scale(.66); }
      .panel { padding: 1.15rem; }
      .page-footer { align-items: flex-start; font-size: .62rem; gap: 1rem; line-height: 1.4; }
    }
    @media (prefers-reduced-motion: reduce) {
      *, *::before, *::after { animation-duration: .01ms !important; animation-iteration-count: 1 !important; scroll-behavior: auto !important; transition-duration: .01ms !important; }
    }
  `],
})
export class AppComponent implements OnInit, OnDestroy {
  private readonly twitchApi = inject(TwitchApiService);
  readonly twitchConnectUrl = this.twitchApi.connectUrl;
  private readonly clipEpochs = new ClipAnalysisEpochs();
  private clipsLoadRequest = 0;
  private readonly manualClipRequests = new ClipRequestSubscriptions<ManualClipRequestKind>();

  readonly twitchLoading = signal(true);
  readonly twitchStatus = signal<TwitchConnectionStatus | null>(null);
  readonly twitchSession = signal<TwitchStreamSession | null>(null);
  readonly twitchSessionLoading = signal(true);
  readonly chatSummary = signal<TwitchChatSummary | null>(null);
  readonly chatMessages = signal<TwitchChatMessage[]>([]);
  readonly chatLoading = signal(false);
  readonly chatError = signal('');
  readonly clips = signal<TwitchClip[]>([]);
  readonly clipsPage = signal<TwitchClipsResponse | null>(null);
  readonly clipsLoading = signal(false);
  readonly clipsLoaded = signal(false);
  readonly clipsError = signal('');
  readonly clipTranscribing = signal<Set<string>>(new Set());
  readonly clipTranscriptionErrors = signal<Map<string, string>>(new Map());
  readonly clipTranscriptions = signal<Map<string, ClipTranscriptionResponse>>(new Map());
  readonly clipMomentsLoading = signal<Set<string>>(new Set());
  readonly clipMomentsErrors = signal<Map<string, string>>(new Map());
  readonly clipMomentsResults = signal<Map<string, MemorableMomentsResponse>>(new Map());
  readonly clipInterpretationLoading = signal<Set<string>>(new Set());
  readonly clipInterpretationErrors = signal<Map<string, string>>(new Map());
  readonly clipInterpretations = signal<Map<string, MomentInterpretationResponse>>(new Map());
  readonly clipLyricsLoading = signal<Set<string>>(new Set());
  readonly clipLyricsErrors = signal<Map<string, string>>(new Map());
  readonly clipLyricsResults = signal<Map<string, ClipLyricsResponse>>(new Map());
  readonly clipLyricsCopying = signal<Set<string>>(new Set());
  readonly clipLyricsCopyMessages = signal<Map<string, string>>(new Map());
  readonly spokenPreviewLoading = signal<Set<string>>(new Set());
  readonly spokenPreviewErrors = signal<Map<string, string>>(new Map());
  readonly spokenPreviewAudioUrls = signal<Map<string, string>>(new Map());
  private readonly spokenPreviewRegistry = new SpokenPreviewAudioRegistry();
  private readonly spokenPreviewSubscriptions = new Map<string, Subscription>();
  readonly privateLyricsGenres = signal<Map<string, LyricsGenre>>(new Map());
  readonly privateLyricsMoods = signal<Map<string, LyricsMood>>(new Map());
  readonly privateLyricsEnergies = signal<Map<string, LyricsEnergy>>(new Map());
  readonly clipMusicJobs = signal<Map<string, MusicJobResponse>>(new Map());
  readonly clipMusicErrors = signal<Map<string, string>>(new Map());
  readonly clipMusicLoading = signal<Set<string>>(new Set());
  private readonly musicPollSubscriptions = new Map<string, Subscription>();
  readonly clipPipelineLoading = signal<Set<string>>(new Set());
  readonly clipPipelineStages = signal<Map<string, ClipPipelineStage>>(new Map());
  readonly clipPipelineErrors = signal<Map<string, string>>(new Map());
  readonly clipPipelineEmpty = signal<Set<string>>(new Set());
  private readonly clipPipelineRequests = new Map<string, { subscription: Subscription; cancel: () => void }>();
  readonly twitchMessage = signal('');
  readonly twitchError = signal('');
  ngOnInit(): void {
    this.handleTwitchCallback();
    this.loadTwitchStatus();
    this.loadTwitchSession();
  }

  ngOnDestroy(): void {
    this.cancelAllClipPipelines();
    this.cancelAllManualClipRequests();
    for (const sub of this.musicPollSubscriptions.values()) sub.unsubscribe(); this.musicPollSubscriptions.clear();
    for (const sub of this.spokenPreviewSubscriptions.values()) sub.unsubscribe(); this.spokenPreviewSubscriptions.clear();
    this.spokenPreviewRegistry.clearAll(); this.spokenPreviewAudioUrls.set(new Map());
  }

  private handleTwitchCallback(): void {
    const parameters = new URLSearchParams(window.location.search);
    const outcome = parameters.get('twitch');
    if (outcome === 'connected') {
      this.twitchMessage.set('Twitch account connected successfully.');
    } else if (outcome === 'error') {
      this.twitchError.set(this.twitchCallbackError(parameters.get('reason')));
    }
    if (outcome) {
      window.history.replaceState({}, document.title, `${window.location.pathname}${window.location.hash}`);
    }
  }

  private loadTwitchStatus(): void {
    this.twitchLoading.set(true);
    this.twitchApi.status().pipe(
      finalize(() => this.twitchLoading.set(false)),
    ).subscribe({
      next: (status) => {
        const previous = this.twitchStatus();
        if (previous && previous.broadcasterId !== status.broadcasterId) this.clearClipAnalysisState();
        this.twitchStatus.set(status);
      },
      error: () => this.twitchError.set('Could not check Twitch connection. Make sure the backend is running and try again.'),
    });
  }

  loadTwitchSession(syncWithTwitch = false): void {
    this.twitchSessionLoading.set(true);
    const request = syncWithTwitch ? this.twitchApi.refreshSession() : this.twitchApi.session();
    request.pipe(
      finalize(() => this.twitchSessionLoading.set(false)),
    ).subscribe({
      next: (session) => {
        this.twitchSession.set(session);
        this.loadChat();
        if (session.connected) this.loadClips();
      },
      error: () => {
        this.twitchSession.set(null);
        this.twitchError.set('Could not load Twitch stream status. Check that the backend is running.');
      },
    });
  }

  loadClips(): void {
    const request = ++this.clipsLoadRequest;
    this.clipsLoading.set(true);
    this.clipsError.set('');
    this.clipsApiRequest().pipe(finalize(() => { if (this.clipsLoadRequest === request) this.clipsLoading.set(false); })).subscribe({
      next: (page) => {
        if (this.clipsLoadRequest !== request) return;
        invalidateRemovedClipSources(this.clips().map(clip => clip.id), page.clips.map(clip => clip.id),
          clipId => this.clearRemovedClipState(clipId));
        this.clipsPage.set(page);
        this.clips.set(page.clips);
        this.clipsLoaded.set(true);
      },
      error: (error: unknown) => {
        if (this.clipsLoadRequest !== request) return;
        this.clipsLoaded.set(true);
        this.clipsError.set(this.clipErrorMessage(error));
      },
    });
  }

  loadMoreClips(): void {
    const page = this.clipsPage();
    if (!page?.nextCursor || this.clipsLoading()) return;
    const request = this.clipsLoadRequest;
    this.clipsLoading.set(true);
    this.clipsError.set('');
    this.twitchApi.clips(20, page.nextCursor, page.startedAt, page.endedAt)
      .pipe(finalize(() => { if (this.clipsLoadRequest === request) this.clipsLoading.set(false); })).subscribe({
        next: (nextPage) => {
          if (this.clipsLoadRequest !== request || this.clipsPage() !== page) return;
          this.clipsPage.set(nextPage);
          this.clips.set([...this.clips(), ...nextPage.clips]);
        },
        error: (error: unknown) => { if (this.clipsLoadRequest === request) this.clipsError.set(this.clipErrorMessage(error)); },
      });
  }

  formatClipDuration(duration: number): string {
    const seconds = Math.round(duration);
    return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
  }

  clipAssociationLabel(association: TwitchClip['sessionAssociation']): string {
    if (association === 'verified_target_session') return 'Matched to this stream session';
    if (association === 'different_session') return 'From a different stream';
    return 'Session unknown · recent broadcaster clip';
  }

  isClipTranscribing(clipId: string): boolean {
    return this.clipTranscribing().has(clipId);
  }

  isRunningClipPipeline(clipId: string): boolean { return this.clipPipelineLoading().has(clipId); }
  canStartClipPipeline(clipId: string): boolean {
    return canStartClipPipeline(this.isRunningClipPipeline(clipId) || this.isClipTranscribing(clipId)
      || this.isFindingClipMoments(clipId) || this.isInterpretingClipMoments(clipId)
      || this.isGeneratingLyrics(clipId) || this.isGeneratingSpokenPreview(clipId) || this.isCreatingMusic(clipId));
  }
  clipPipelineStageLabel(clipId: string): string {
    const stage = this.clipPipelineStages().get(clipId);
    return stage ? clipPipelineStageLabel(stage) : 'starting';
  }
  clipPipelineFailedStageLabel(clipId: string): string { return this.clipPipelineStageLabel(clipId); }

  async runClipGenerationPipeline(clipId: string): Promise<void> {
    if (!this.canStartClipPipeline(clipId)) return;
    const epoch = this.currentClipEpoch(clipId);
    this.cancelSpokenPreviewRequest(clipId);
    this.stopMusicPolling(clipId);
    this.clipPipelineLoading.update(p => new Set(p).add(clipId));
    this.clipPipelineErrors.update(m => { const n = new Map(m); n.delete(clipId); return n; });
    this.clipPipelineEmpty.update(s => { const n = new Set(s); n.delete(clipId); return n; });

    let transcript = this.clipTranscription(clipId);
    let moments = this.clipMoments(clipId);
    let interpretation = this.clipInterpretation(clipId);
    let lyrics = this.clipLyrics(clipId);
    if (transcript && transcript.clipId !== clipId) transcript = undefined;
    const styleMatches = (value: ClipLyricsResponse): boolean => value.genre === this.lyricsGenre(clipId)
      && value.mood === this.lyricsMood(clipId) && value.energy === this.lyricsEnergy(clipId);
    if (moments && moments.clipId !== clipId) moments = undefined;
    if (interpretation && (!moments || interpretation.clipId !== clipId || interpretation.analysisId !== moments.analysisId)) interpretation = undefined;
    if (lyrics && (!interpretation || lyrics.clipId !== clipId || lyrics.analysisId !== interpretation.analysisId
      || lyrics.interpretationId !== interpretation.interpretationId || !styleMatches(lyrics))) lyrics = undefined;
    if (!moments) {
      interpretation = undefined; lyrics = undefined;
      this.clipMomentsResults.update(m => { const n = new Map(m); n.delete(clipId); return n; });
      this.clipInterpretations.update(m => { const n = new Map(m); n.delete(clipId); return n; });
      this.clearLyricsResult(clipId);
    }
    if (!interpretation) { lyrics = undefined; this.clearLyricsResult(clipId); }
    else if (!lyrics && this.clipLyrics(clipId)) this.clearLyricsResult(clipId);

    try {
      const result = await runClipPipeline({
        state: { transcription: transcript, moments, interpretation, lyrics },
        momentsHaveEvidence: value => value.moments.length > 0 || value.sessionChatContext.suppliedCount > 0 || (value.transcriptContext?.suppliedCount ?? 0) > 0,
        isCurrent: () => this.currentClipEpoch(clipId) === epoch,
        onStage: stage => this.clipPipelineStages.update(m => new Map(m).set(clipId, stage)),
        run: {
          transcription: async () => {
            const value = await this.awaitClipPipelineRequest(clipId, this.twitchApi.transcribeClip(clipId));
            if (value.clipId !== clipId) throw new Error('Clip transcription response did not match the selected clip.');
            transcript = value;
            this.clipTranscriptions.update(m => new Map(m).set(clipId, value));
            return value;
          },
          moments: async () => {
            const value = await this.awaitClipPipelineRequest(clipId, this.twitchApi.findClipMoments(clipId));
            if (value.clipId !== clipId) throw new Error('Moment response did not match the selected clip.');
            moments = value;
            this.clipMomentsResults.update(m => new Map(m).set(clipId, value));
            this.clipInterpretations.update(m => { const n = new Map(m); n.delete(clipId); return n; });
            this.clearLyricsResult(clipId);
            return value;
          },
          interpretation: async value => {
            const response = await this.awaitClipPipelineRequest(clipId, this.twitchApi.interpretClipMoments(clipId, value.analysisId));
            if (response.clipId !== clipId || response.analysisId !== value.analysisId) throw new Error('Interpretation response did not match the selected clip analysis.');
            interpretation = response;
            this.clipInterpretations.update(m => new Map(m).set(clipId, response));
            this.clearLyricsResult(clipId);
            return response;
          },
          lyrics: async (value, interpreted) => {
            const request: ClipLyricsRequest = { analysisId: value.analysisId, interpretationId: interpreted.interpretationId,
              genre: this.lyricsGenre(clipId), mood: this.lyricsMood(clipId), energy: this.lyricsEnergy(clipId) };
            const response = await this.awaitClipPipelineRequest(clipId, this.twitchApi.generateClipLyrics(clipId, request));
            if (response.clipId !== clipId || response.analysisId !== value.analysisId || response.interpretationId !== interpreted.interpretationId
              || response.genre !== request.genre || response.mood !== request.mood || response.energy !== request.energy) {
              throw new Error('Lyrics response did not match the selected clip and style.');
            }
            lyrics = response;
            this.clearSpokenPreview(clipId);
            this.clearMusicState(clipId);
            this.clipLyricsResults.update(m => new Map(m).set(clipId, response));
            return response;
          },
          spokenPreview: async value => {
            const blob = await this.awaitClipPipelineRequest(clipId, this.twitchApi.spokenLyricsPreview(clipId, value.lyricsId));
            return blob;
          },
        },
      });
      if (this.currentClipEpoch(clipId) !== epoch) return;
      this.clipTranscriptions.update(m => transcript ? new Map(m).set(clipId, transcript) : m);
      this.clipMomentsResults.update(m => moments ? new Map(m).set(clipId, moments) : m);
      this.clipInterpretations.update(m => interpretation ? new Map(m).set(clipId, interpretation) : m);
      this.clipLyricsResults.update(m => lyrics ? new Map(m).set(clipId, lyrics) : m);
      if (result.stoppedForNoEvidence) {
        this.clipPipelineEmpty.update(s => new Set(s).add(clipId));
      } else if (result.preview) {
        const url = this.spokenPreviewRegistry.replace(clipId, result.preview);
        this.spokenPreviewAudioUrls.update(m => new Map(m).set(clipId, url));
        this.spokenPreviewErrors.update(m => { const n = new Map(m); n.delete(clipId); return n; });
      }
    } catch (error) {
      if (error instanceof ClipPipelineCancelled || this.currentClipEpoch(clipId) !== epoch) return;
      const failure = error instanceof ClipPipelineFailure ? error : null;
      const stage = failure?.stage ?? this.clipPipelineStages().get(clipId) ?? 'transcription';
      const cause = failure?.cause ?? error;
      this.handleClipPipelineFailure(clipId, stage, cause);
    } finally {
      this.clipPipelineLoading.update(s => { const n = new Set(s); n.delete(clipId); return n; });
    }
  }

  private handleClipPipelineFailure(clipId: string, stage: ClipPipelineStage, error: unknown): void {
    const detail = (error as { error?: { detail?: unknown } } | null)?.error?.detail;
    let message: string;
    switch (stage) {
      case 'transcription': message = clipTranscriptionErrorMessage(error); break;
      case 'moments': message = memorableMomentsErrorMessage(error); break;
      case 'interpretation': message = momentInterpretationErrorMessage(error); break;
      case 'lyrics': message = clipLyricsErrorMessage(error); break;
      case 'spoken_preview': message = spokenPreviewSafeMessage(error); break;
    }
    if ((stage === 'interpretation' && ['moment_analysis_expired', 'moment_analysis_stale'].includes(String(detail)))) {
      this.clipMomentsResults.update(m => { const n = new Map(m); n.delete(clipId); return n; });
      this.clipInterpretations.update(m => { const n = new Map(m); n.delete(clipId); return n; });
      this.clearLyricsResult(clipId);
      message += ' Run All again to extract fresh moments before continuing.';
    } else if ((stage === 'lyrics' && ['interpretation_snapshot_expired', 'interpretation_snapshot_stale'].includes(String(detail)))
      || (stage === 'spoken_preview' && detail === 'spoken_preview_lyrics_stale')) {
      this.clipInterpretations.update(m => { const n = new Map(m); n.delete(clipId); return n; });
      this.clearLyricsResult(clipId);
      message += ' Run All again to create a fresh interpretation and lyrics.';
    }
    this.clipPipelineErrors.update(m => new Map(m).set(clipId, message));
  }

  private awaitClipPipelineRequest<T>(clipId: string, observable: Observable<T>): Promise<T> {
    return new Promise<T>((resolve, reject) => {
      let settled = false;
      const subscription = new Subscription();
      const finish = (action: () => void): void => {
        if (settled) return;
        settled = true;
        if (this.clipPipelineRequests.get(clipId)?.subscription === subscription) this.clipPipelineRequests.delete(clipId);
        subscription.unsubscribe();
        action();
      };
      const cancel = (): void => finish(() => reject(new ClipPipelineCancelled()));
      this.clipPipelineRequests.set(clipId, { subscription, cancel });
      subscription.add(observable.subscribe({
        next: value => finish(() => resolve(value)),
        error: error => finish(() => reject(error)),
        complete: () => finish(() => reject(new Error('The request returned no result.'))),
      }));
    });
  }

  private cancelClipPipeline(clipId: string): void { this.clipPipelineRequests.get(clipId)?.cancel(); }
  private cancelAllClipPipelines(): void { for (const request of this.clipPipelineRequests.values()) request.cancel(); this.clipPipelineRequests.clear(); }

  private trackManualClipRequest<T>(clipId: string, kind: ManualClipRequestKind, epoch: number, source: Observable<T>,
    observer: { next: (value: T) => void; error: (error: unknown) => void }, onFinish: () => void): void {
    const request = new Subscription();
    this.manualClipRequests.track(clipId, kind, request);
    const child = source.pipe(finalize(() => {
      if (this.manualClipRequests.finish(clipId, kind, request) && this.currentClipEpoch(clipId) === epoch) onFinish();
    })).subscribe(observer);
    request.add(child);
  }

  private cancelManualClipRequest(clipId: string, kind: ManualClipRequestKind): void {
    this.manualClipRequests.cancel(clipId, kind, () => this.clearManualClipLoading(clipId, kind));
  }

  private cancelManualClipRequests(clipId: string): void {
    this.manualClipRequests.cancelClip(clipId, kind => this.clearManualClipLoading(clipId, kind));
  }

  private cancelAllManualClipRequests(): void {
    this.manualClipRequests.cancelAll((clipId, kind) => this.clearManualClipLoading(clipId, kind));
  }

  private clearManualClipLoading(clipId: string, kind: ManualClipRequestKind): void {
    if (kind === 'transcription') this.clipTranscribing.update(s => { const n = new Set(s); n.delete(clipId); return n; });
    if (kind === 'moments') this.clipMomentsLoading.update(s => { const n = new Set(s); n.delete(clipId); return n; });
    if (kind === 'interpretation') this.clipInterpretationLoading.update(s => { const n = new Set(s); n.delete(clipId); return n; });
    if (kind === 'lyrics') this.clipLyricsLoading.update(s => { const n = new Set(s); n.delete(clipId); return n; });
  }

  clipTranscriptionError(clipId: string): string | undefined {
    return this.clipTranscriptionErrors().get(clipId);
  }

  clipTranscription(clipId: string): ClipTranscriptionResponse | undefined {
    return this.clipTranscriptions().get(clipId);
  }

  transcribeClip(clipId: string, refresh = false): void {
    if (this.isClipTranscribing(clipId) || this.isRunningClipPipeline(clipId)) return;
    const epoch = this.bumpClipEpoch(clipId);
    this.cancelSpokenPreviewRequest(clipId);
    this.stopMusicPolling(clipId);
    this.clipTranscribing.update((pending) => new Set(pending).add(clipId));
    this.clipTranscriptionErrors.update((errors) => {
      const next = new Map(errors);
      next.delete(clipId);
      return next;
    });
    this.trackManualClipRequest(clipId, 'transcription', epoch, this.twitchApi.transcribeClip(clipId, refresh), {
      next: (response) => {
        if (this.currentClipEpoch(clipId) !== epoch) return;
        this.clipTranscriptions.update((transcripts) => new Map(transcripts).set(clipId, response));
        if (refresh) this.clearClipDerivedState(clipId);
        else this.resumeMusicPolling(clipId);
      },
      error: (error: unknown) => {
        if (this.currentClipEpoch(clipId) === epoch) this.clipTranscriptionErrors.update((errors) =>
          new Map(errors).set(clipId, clipTranscriptionErrorMessage(error)));
        this.resumeMusicPolling(clipId);
      },
    }, () => this.clipTranscribing.update((pending) => { const next = new Set(pending); next.delete(clipId); return next; }));
  }

  formatTranscriptTime(seconds: number): string {
    const safeSeconds = Math.max(0, seconds);
    const minutes = Math.floor(safeSeconds / 60);
    const remaining = (safeSeconds % 60).toFixed(1).padStart(4, '0');
    return `${minutes}:${remaining}`;
  }

  isFindingClipMoments(clipId: string): boolean { return this.clipMomentsLoading().has(clipId); }
  clipMomentsError(clipId: string): string | undefined { return this.clipMomentsErrors().get(clipId); }
  clipMoments(clipId: string): MemorableMomentsResponse | undefined { return this.clipMomentsResults().get(clipId); }

  findClipMoments(clipId: string): void {
    if (this.isFindingClipMoments(clipId) || this.isRunningClipPipeline(clipId)) return;
    const epoch = this.bumpClipEpoch(clipId);
    this.cancelSpokenPreviewRequest(clipId);
    this.stopMusicPolling(clipId);
    this.clipMomentsLoading.update((pending) => new Set(pending).add(clipId));
    this.clipMomentsErrors.update((errors) => {
      const next = new Map(errors);
      next.delete(clipId);
      return next;
    });
    this.trackManualClipRequest(clipId, 'moments', epoch, this.twitchApi.findClipMoments(clipId), {
      next: (response) => {
        if (this.currentClipEpoch(clipId) !== epoch) return;
        this.clipMomentsResults.update((results) => new Map(results).set(clipId, response));
        this.clipInterpretations.update((results) => { const next = new Map(results); next.delete(clipId); return next; });
        this.clipInterpretationErrors.update((errors) => { const next = new Map(errors); next.delete(clipId); return next; });
        this.clearLyricsResult(clipId);
      },
      error: (error: unknown) => {
        if (this.currentClipEpoch(clipId) === epoch) this.clipMomentsErrors.update((errors) =>
          new Map(errors).set(clipId, memorableMomentsErrorMessage(error)));
        this.resumeMusicPolling(clipId);
      },
    }, () => this.clipMomentsLoading.update((pending) => { const next = new Set(pending); next.delete(clipId); return next; }));
  }

  isInterpretingClipMoments(clipId: string): boolean { return this.clipInterpretationLoading().has(clipId); }
  canInterpretClip(clipId: string): boolean {
    const result = this.clipMoments(clipId);
    return !!result && (result.moments.length > 0 || result.sessionChatContext.suppliedCount > 0 || (result.transcriptContext?.suppliedCount ?? 0) > 0);
  }
  clipInterpretationError(clipId: string): string | undefined { return this.clipInterpretationErrors().get(clipId); }
  clipInterpretation(clipId: string): MomentInterpretationResponse | undefined { return this.clipInterpretations().get(clipId); }

  interpretClipMoments(clipId: string): void {
    const moments = this.clipMoments(clipId);
    if (!moments || (moments.moments.length === 0 && moments.sessionChatContext.suppliedCount === 0 && (moments.transcriptContext?.suppliedCount ?? 0) === 0)
      || this.isInterpretingClipMoments(clipId) || this.isRunningClipPipeline(clipId)) return;
    const epoch = this.currentClipEpoch(clipId);
    this.cancelSpokenPreviewRequest(clipId);
    this.stopMusicPolling(clipId);
    this.clipInterpretationLoading.update((pending) => new Set(pending).add(clipId));
    this.clipInterpretationErrors.update((errors) => { const next = new Map(errors); next.delete(clipId); return next; });
    this.trackManualClipRequest(clipId, 'interpretation', epoch, this.twitchApi.interpretClipMoments(clipId, moments.analysisId), {
      next: (response) => {
        if (this.currentClipEpoch(clipId) !== epoch || this.clipMoments(clipId)?.analysisId !== moments.analysisId) return;
        this.clipInterpretations.update((results) => new Map(results).set(clipId, response));
        this.clearLyricsResult(clipId);
      },
      error: (error: unknown) => {
        if (this.currentClipEpoch(clipId) === epoch) this.clipInterpretationErrors.update((errors) =>
          new Map(errors).set(clipId, momentInterpretationErrorMessage(error)));
        this.resumeMusicPolling(clipId);
      },
    }, () => this.clipInterpretationLoading.update((pending) => { const next = new Set(pending); next.delete(clipId); return next; }));
  }

  lyricsGenre(clipId: string): LyricsGenre { return this.privateLyricsGenres().get(clipId) ?? 'pop'; }
  lyricsMood(clipId: string): LyricsMood { return this.privateLyricsMoods().get(clipId) ?? 'playful'; }
  lyricsEnergy(clipId: string): LyricsEnergy { return this.privateLyricsEnergies().get(clipId) ?? 'medium'; }
  setLyricsGenre(clipId: string, value: LyricsGenre): void { this.privateLyricsGenres.update(m => new Map(m).set(clipId,value)); this.clearLyricsResult(clipId); }
  setLyricsMood(clipId: string, value: LyricsMood): void { this.privateLyricsMoods.update(m => new Map(m).set(clipId,value)); this.clearLyricsResult(clipId); }
  setLyricsEnergy(clipId: string, value: LyricsEnergy): void { this.privateLyricsEnergies.update(m => new Map(m).set(clipId,value)); this.clearLyricsResult(clipId); }
  isGeneratingLyrics(clipId: string): boolean { return this.clipLyricsLoading().has(clipId); }
  isCopyingLyrics(clipId: string): boolean { return this.clipLyricsCopying().has(clipId); }
  clipLyricsError(clipId: string): string | undefined { return this.clipLyricsErrors().get(clipId); }
  clipLyrics(clipId: string): ClipLyricsResponse | undefined { return this.clipLyricsResults().get(clipId); }
  lyricsCopyMessage(clipId: string): string | undefined { return this.clipLyricsCopyMessages().get(clipId); }

  isGeneratingSpokenPreview(clipId: string): boolean { return this.spokenPreviewLoading().has(clipId); }
  canGenerateSpokenPreview(clipId: string): boolean {
    return canGenerateSpokenPreview(this.clipLyrics(clipId), this.isGeneratingSpokenPreview(clipId));
  }
  spokenPreviewError(clipId: string): string | undefined { return this.spokenPreviewErrors().get(clipId); }
  spokenPreviewAudioUrl(clipId: string): string | undefined { return this.spokenPreviewAudioUrls().get(clipId); }

  generateSpokenPreview(clipId: string): void {
    if (this.isRunningClipPipeline(clipId)) return;
    const lyrics=this.clipLyrics(clipId);if(!canGenerateSpokenPreview(lyrics,this.isGeneratingSpokenPreview(clipId))||!lyrics)return;
    const epoch=this.currentClipEpoch(clipId),lyricsId=lyrics.lyricsId;
    this.spokenPreviewLoading.update(p=>new Set(p).add(clipId));
    this.spokenPreviewErrors.update(m=>{const n=new Map(m);n.delete(clipId);return n;});
    let sub:Subscription;
    sub=this.twitchApi.spokenLyricsPreview(clipId,lyricsId).pipe(finalize(()=>{
      this.spokenPreviewLoading.update(p=>{const n=new Set(p);n.delete(clipId);return n;});
      if(this.spokenPreviewSubscriptions.get(clipId)===sub)this.spokenPreviewSubscriptions.delete(clipId);
    })).subscribe({
      next:blob=>{
        if(!isCurrentSpokenPreview(this.currentClipEpoch(clipId),epoch,this.clipLyrics(clipId)?.lyricsId,lyricsId))return;
        const url=this.spokenPreviewRegistry.replace(clipId,blob);
        this.spokenPreviewAudioUrls.update(m=>new Map(m).set(clipId,url));
      },
      error:error=>{
        if(isCurrentSpokenPreview(this.currentClipEpoch(clipId),epoch,this.clipLyrics(clipId)?.lyricsId,lyricsId))
          this.spokenPreviewErrors.update(m=>new Map(m).set(clipId,spokenPreviewSafeMessage(error)));
      },
    });
    this.spokenPreviewSubscriptions.set(clipId,sub);
  }

  private clearSpokenPreview(clipId:string):void {
    this.cancelSpokenPreviewRequest(clipId);
    this.spokenPreviewRegistry.clear(clipId);
    this.spokenPreviewAudioUrls.update(m=>{const n=new Map(m);n.delete(clipId);return n;});
    this.spokenPreviewErrors.update(m=>{const n=new Map(m);n.delete(clipId);return n;});
    this.spokenPreviewLoading.update(p=>{const n=new Set(p);n.delete(clipId);return n;});
  }

  private cancelSpokenPreviewRequest(clipId:string):void {
    this.spokenPreviewSubscriptions.get(clipId)?.unsubscribe();this.spokenPreviewSubscriptions.delete(clipId);
  }

  generateClipLyrics(clipId: string): void {
    const interpretation = this.clipInterpretation(clipId);
    if (!interpretation || this.isGeneratingLyrics(clipId) || this.isRunningClipPipeline(clipId)) return;
    const epoch = this.currentClipEpoch(clipId);
    this.cancelSpokenPreviewRequest(clipId);
    this.stopMusicPolling(clipId);
    const request: ClipLyricsRequest = { analysisId: interpretation.analysisId, interpretationId: interpretation.interpretationId,
      genre: this.lyricsGenre(clipId), mood: this.lyricsMood(clipId), energy: this.lyricsEnergy(clipId) };
    this.clipLyricsLoading.update(p => new Set(p).add(clipId));
    this.clipLyricsErrors.update(e => { const n=new Map(e); n.delete(clipId); return n; });
    this.trackManualClipRequest(clipId, 'lyrics', epoch, this.twitchApi.generateClipLyrics(clipId,request), {
      next: result => { if(!isCurrentLyricsRequest(this.currentClipEpoch(clipId),epoch,this.clipInterpretation(clipId)?.interpretationId,request.interpretationId)) return; this.clearSpokenPreview(clipId); this.clearMusicState(clipId); this.clipLyricsResults.update(r=>new Map(r).set(clipId,result)); },
      error: error => { if(isCurrentLyricsRequest(this.currentClipEpoch(clipId),epoch,this.clipInterpretation(clipId)?.interpretationId,request.interpretationId)) this.clipLyricsErrors.update(e=>new Map(e).set(clipId,clipLyricsErrorMessage(error))); this.resumeMusicPolling(clipId); },
    }, () => this.clipLyricsLoading.update(p=>{const n=new Set(p);n.delete(clipId);return n;}));
  }

  clipMusicJob(clipId: string): MusicJobResponse | undefined { return this.clipMusicJobs().get(clipId); }
  clipMusicError(clipId: string): string | undefined { return this.clipMusicErrors().get(clipId); }
  isCreatingMusic(clipId: string): boolean { return this.clipMusicLoading().has(clipId); }
  canResumeMusicStatus(clipId: string): boolean {
    return canResumeMusicStatus(this.clipMusicJob(clipId), !!this.clipMusicError(clipId),
      this.musicPollSubscriptions.has(clipId), this.isCreatingMusic(clipId));
  }
  musicAudioUrl(job: MusicJobResponse): string { return this.twitchApi.musicAudioUrl(job.jobId); }
  musicJobMessage(reason: string): string { return musicJobSafeMessage(reason); }

  createMusicJob(clipId: string): void {
    const lyrics=this.clipLyrics(clipId); if(!lyrics||this.isCreatingMusic(clipId)||this.clipMusicJob(clipId)||this.isRunningClipPipeline(clipId))return;
    const epoch=this.currentClipEpoch(clipId), lyricsId=lyrics.lyricsId;
    this.clipMusicLoading.update(p=>new Set(p).add(clipId));this.clipMusicErrors.update(m=>{const n=new Map(m);n.delete(clipId);return n;});
    this.twitchApi.createMusicJob(clipId,lyricsId).pipe(finalize(()=>this.clipMusicLoading.update(p=>{const n=new Set(p);n.delete(clipId);return n;}))).subscribe({
      next:job=>{if(!isCurrentMusicJobRequest(this.currentClipEpoch(clipId),epoch,this.clipLyrics(clipId)?.lyricsId,lyricsId))return;this.setMusicJob(clipId,job,epoch,lyricsId);},
      error:error=>{if(isCurrentMusicJobRequest(this.currentClipEpoch(clipId),epoch,this.clipLyrics(clipId)?.lyricsId,lyricsId))this.clipMusicErrors.update(m=>new Map(m).set(clipId,musicCreateSafeMessage(error)));},
    });
  }

  retryMusicJob(clipId: string): void {
    const current=this.clipMusicJob(clipId);if(!current||!current.retryable||this.isCreatingMusic(clipId))return;
    const epoch=this.currentClipEpoch(clipId),lyricsId=this.clipLyrics(clipId)?.lyricsId;if(!lyricsId)return;
    this.clipMusicLoading.update(p=>new Set(p).add(clipId));
    this.twitchApi.retryMusicJob(current.jobId,current.attempt).pipe(finalize(()=>this.clipMusicLoading.update(p=>{const n=new Set(p);n.delete(clipId);return n;}))).subscribe({
      next:job=>{if(isCurrentMusicJobRequest(this.currentClipEpoch(clipId),epoch,this.clipLyrics(clipId)?.lyricsId,lyricsId))this.setMusicJob(clipId,job,epoch,lyricsId);},
      error:error=>{if(isCurrentMusicJobRequest(this.currentClipEpoch(clipId),epoch,this.clipLyrics(clipId)?.lyricsId,lyricsId)){this.clipMusicErrors.update(m=>new Map(m).set(clipId,musicCreateSafeMessage(error)));this.twitchApi.musicJob(current.jobId).subscribe({next:job=>{if(isCurrentMusicJobRequest(this.currentClipEpoch(clipId),epoch,this.clipLyrics(clipId)?.lyricsId,lyricsId))this.setMusicJob(clipId,job,epoch,lyricsId);},error:()=>{}});}},
    });
  }

  resumeMusicStatus(clipId: string): void {
    const current=this.clipMusicJob(clipId);if(!this.canResumeMusicStatus(clipId)||!current)return;
    const epoch=this.currentClipEpoch(clipId),lyricsId=this.clipLyrics(clipId)?.lyricsId;if(!lyricsId)return;
    this.clipMusicLoading.update(p=>new Set(p).add(clipId));this.clipMusicErrors.update(m=>{const n=new Map(m);n.delete(clipId);return n;});
    this.twitchApi.musicJob(current.jobId).pipe(finalize(()=>this.clipMusicLoading.update(p=>{const n=new Set(p);n.delete(clipId);return n;}))).subscribe({
      next:job=>{if(isCurrentMusicJobRequest(this.currentClipEpoch(clipId),epoch,this.clipLyrics(clipId)?.lyricsId,lyricsId))this.setMusicJob(clipId,job,epoch,lyricsId);},
      error:()=>{if(isCurrentMusicJobRequest(this.currentClipEpoch(clipId),epoch,this.clipLyrics(clipId)?.lyricsId,lyricsId))this.clipMusicErrors.update(m=>new Map(m).set(clipId,'Could not check song status. Try again later.'));},
    });
  }

  private setMusicJob(clipId:string,job:MusicJobResponse,epoch:number,lyricsId:string):void {
    this.clipMusicJobs.update(m=>new Map(m).set(clipId,job));
    if(isTerminalMusicJob(job.status)){this.stopMusicPolling(clipId);return;}
    this.stopMusicPolling(clipId);
    let sub: Subscription;
    sub=pollMusicJob(job.jobId,id=>this.twitchApi.musicJob(id)).pipe(
      finalize(()=>{if(this.musicPollSubscriptions.get(clipId)===sub)this.musicPollSubscriptions.delete(clipId);})).subscribe({next:value=>{
        if(!isCurrentMusicJobRequest(this.currentClipEpoch(clipId),epoch,this.clipLyrics(clipId)?.lyricsId,lyricsId)){this.stopMusicPolling(clipId);return;}
        this.clipMusicJobs.update(m=>new Map(m).set(clipId,value));
      },error:()=>{if(isCurrentMusicJobRequest(this.currentClipEpoch(clipId),epoch,this.clipLyrics(clipId)?.lyricsId,lyricsId))this.clipMusicErrors.update(m=>new Map(m).set(clipId,'Could not check song status. Try again later.'));}});
    this.musicPollSubscriptions.set(clipId,sub);
  }

  private stopMusicPolling(clipId:string):void { this.musicPollSubscriptions.get(clipId)?.unsubscribe();this.musicPollSubscriptions.delete(clipId); }
  private resumeMusicPolling(clipId:string):void {
    const job=this.clipMusicJob(clipId),lyricsId=this.clipLyrics(clipId)?.lyricsId;
    if(job&&!isTerminalMusicJob(job.status)&&lyricsId)this.setMusicJob(clipId,job,this.currentClipEpoch(clipId),lyricsId);
  }
  private clearMusicState(clipId:string):void {
    this.stopMusicPolling(clipId);this.clipMusicJobs.update(m=>{const n=new Map(m);n.delete(clipId);return n;});
    this.clipMusicErrors.update(m=>{const n=new Map(m);n.delete(clipId);return n;});
  }

  async copyClipLyrics(clipId: string): Promise<void> {
    const lyrics=this.clipLyrics(clipId); if(!lyrics || this.isCopyingLyrics(clipId)) return;
    const epoch=this.currentClipEpoch(clipId);
    this.clipLyricsCopying.update(p=>new Set(p).add(clipId));
    try { await navigator.clipboard.writeText(formatClipLyricsForCopy(lyrics));
      if(this.currentClipEpoch(clipId)===epoch && this.clipLyrics(clipId)===lyrics) this.clipLyricsCopyMessages.update(m=>new Map(m).set(clipId,'Lyrics copied.'));
    } catch { if(this.currentClipEpoch(clipId)===epoch && this.clipLyrics(clipId)===lyrics) this.clipLyricsCopyMessages.update(m=>new Map(m).set(clipId,'Could not copy lyrics. Check clipboard permissions.')); }
    finally { this.clipLyricsCopying.update(p=>{const n=new Set(p);n.delete(clipId);return n;}); }
  }

  private clearLyricsResult(clipId: string): void {
    this.clearSpokenPreview(clipId);
    this.clearMusicState(clipId);
    this.clipLyricsResults.update(m=>{const n=new Map(m);n.delete(clipId);return n;});
    this.clipLyricsErrors.update(m=>{const n=new Map(m);n.delete(clipId);return n;});
    this.clipLyricsCopyMessages.update(m=>{const n=new Map(m);n.delete(clipId);return n;});
  }

  private currentClipEpoch(clipId: string): number { return this.clipEpochs.current(clipId); }
  private bumpClipEpoch(clipId: string): number {
    this.cancelClipPipeline(clipId);
    this.cancelManualClipRequests(clipId);
    return this.clipEpochs.next(clipId);
  }
  private clearRemovedClipState(clipId: string): void {
    this.bumpClipEpoch(clipId);
    this.stopMusicPolling(clipId);
    this.clearSpokenPreview(clipId);
    this.clipTranscriptions.update(m => { const n = new Map(m); n.delete(clipId); return n; });
    this.clipTranscriptionErrors.update(m => { const n = new Map(m); n.delete(clipId); return n; });
    this.clipMomentsResults.update(m => { const n = new Map(m); n.delete(clipId); return n; });
    this.clipMomentsErrors.update(m => { const n = new Map(m); n.delete(clipId); return n; });
    this.clipInterpretations.update(m => { const n = new Map(m); n.delete(clipId); return n; });
    this.clipInterpretationErrors.update(m => { const n = new Map(m); n.delete(clipId); return n; });
    this.clipTranscribing.update(s => { const n = new Set(s); n.delete(clipId); return n; });
    this.clipMomentsLoading.update(s => { const n = new Set(s); n.delete(clipId); return n; });
    this.clipInterpretationLoading.update(s => { const n = new Set(s); n.delete(clipId); return n; });
    this.clipLyricsLoading.update(s => { const n = new Set(s); n.delete(clipId); return n; });
    this.clipLyricsCopying.update(s => { const n = new Set(s); n.delete(clipId); return n; });
    this.clipMusicLoading.update(s => { const n = new Set(s); n.delete(clipId); return n; });
    this.clearLyricsResult(clipId);
    this.clipPipelineLoading.update(s => { const n = new Set(s); n.delete(clipId); return n; });
    this.clipPipelineStages.update(m => { const n = new Map(m); n.delete(clipId); return n; });
    this.clipPipelineErrors.update(m => { const n = new Map(m); n.delete(clipId); return n; });
    this.clipPipelineEmpty.update(s => { const n = new Set(s); n.delete(clipId); return n; });
  }
  private clearClipDerivedState(clipId: string): void {
    this.clipMomentsResults.update((values) => { const next = new Map(values); next.delete(clipId); return next; });
    this.clipInterpretations.update((values) => { const next = new Map(values); next.delete(clipId); return next; });
    this.clipMomentsErrors.update((values) => { const next = new Map(values); next.delete(clipId); return next; });
    this.clipInterpretationErrors.update((values) => { const next = new Map(values); next.delete(clipId); return next; });
    this.clearLyricsResult(clipId);
  }
  private clearClipAnalysisState(): void {
    this.cancelAllClipPipelines();
    this.cancelAllManualClipRequests();
    this.clipEpochs.invalidateAll();
    this.clipTranscriptions.set(new Map());
    this.clipTranscriptionErrors.set(new Map());
    this.clipMomentsResults.set(new Map());
    this.clipMomentsErrors.set(new Map());
    this.clipInterpretations.set(new Map());
    this.clipInterpretationErrors.set(new Map());
    this.clipTranscribing.set(new Set());
    this.clipMomentsLoading.set(new Set());
    this.clipInterpretationLoading.set(new Set());
    this.clipLyricsResults.set(new Map()); this.clipLyricsErrors.set(new Map()); this.clipLyricsLoading.set(new Set());
    this.clipLyricsCopyMessages.set(new Map()); this.clipLyricsCopying.set(new Set());
    this.privateLyricsGenres.set(new Map()); this.privateLyricsMoods.set(new Map()); this.privateLyricsEnergies.set(new Map());
    for(const sub of this.musicPollSubscriptions.values())sub.unsubscribe();this.musicPollSubscriptions.clear();
    for(const sub of this.spokenPreviewSubscriptions.values())sub.unsubscribe();this.spokenPreviewSubscriptions.clear();
    this.spokenPreviewRegistry.clearAll();this.spokenPreviewAudioUrls.set(new Map());this.spokenPreviewErrors.set(new Map());this.spokenPreviewLoading.set(new Set());
    this.clipMusicJobs.set(new Map());this.clipMusicErrors.set(new Map());this.clipMusicLoading.set(new Set());
    this.clipPipelineLoading.set(new Set()); this.clipPipelineStages.set(new Map());
    this.clipPipelineErrors.set(new Map()); this.clipPipelineEmpty.set(new Set());
  }

  formatMomentRange(moment: MemorableMoment): string {
    return moment.startSeconds !== null && moment.endSeconds !== null
      ? `${this.formatTranscriptTime(moment.startSeconds)}–${this.formatTranscriptTime(moment.endSeconds)}`
      : 'Time unavailable';
  }

  momentAssociationLabel(association: MemorableMomentsResponse['association']): string {
    if (association === 'verified_target_session') return 'verified target stream';
    if (association === 'different_session') return 'different stream';
    return 'unknown';
  }

  isSessionChatSource(source: ClipLyricsResponse['sections'][number]['sources'][number]): boolean {
    return source.evidence.some(evidence => evidence.kind === 'session_chat_context');
  }

  isClipTranscriptContextSource(source: ClipLyricsResponse['sections'][number]['sources'][number]): boolean {
    return source.evidence.some(evidence => evidence.kind === 'clip_transcript_context');
  }

  private clipsApiRequest() { return this.twitchApi.clips(); }

  private clipErrorMessage(error: unknown): string {
    if (error instanceof HttpErrorResponse) {
      if (error.status === 409) return 'Connect a Twitch account to discover clips.';
      if (error.status === 429) return 'Twitch is rate limiting clip requests. Try again shortly.';
      if (error.status === 502) return 'Twitch could not complete the clip request. Check app credentials and try again.';
      if (error.status === 503 || error.status === 0) return 'Clip discovery is unavailable. Check the backend configuration and try again.';
    }
    return 'Could not load Twitch clips. Please try again.';
  }

  private loadChat(): void {
    this.chatLoading.set(true);
    this.chatError.set('');
    this.twitchApi.chatSummary().subscribe({
      next: (summary) => this.chatSummary.set(summary),
      error: () => this.chatError.set('Could not load chat collection status.'),
    });
    this.twitchApi.chatMessages().pipe(finalize(() => this.chatLoading.set(false))).subscribe({
      next: (messages) => this.chatMessages.set(messages),
      error: () => this.chatError.set('Could not load recent chat messages.'),
    });
  }

  chatStatusLabel(status: string | undefined): string {
    if (status?.startsWith('revoked:')) return 'Chat subscription revoked — reconnect Twitch';
    const labels: Record<string, string> = {
      enabled: 'Collecting chat',
      webhook_callback_verification_pending: 'Verifying chat webhook',
      awaiting_stream: 'Waiting for a live stream',
      stopped: 'Collection stopped',
      authorization_required: 'Chat authorization required — reconnect Twitch',
      unauthorized: 'Twitch rejected the app token',
      not_configured: 'Chat webhook not configured',
      conflict_unresolved: 'Existing chat subscription needs review',
      failed: 'Chat subscription failed',
      disconnected: 'Twitch disconnected',
    };
    return status ? labels[status] ?? status : 'Checking chat status';
  }

  chatStatusIsError(status: string | undefined): boolean {
    return Boolean(status && (status.startsWith('revoked:')
      || ['failed', 'unauthorized', 'authorization_required', 'conflict_unresolved'].includes(status)));
  }

  streamEventSubDisplay() {
    return streamSubscriptionDisplay(this.twitchSession()?.subscriptionStatuses);
  }

  private twitchCallbackError(reason: string | null): string {
    const messages: Record<string, string> = {
      authorization_denied: 'Twitch authorization was declined.',
      authorization_failed: 'Twitch could not authorize this connection. Please try again.',
      authorization_code_missing: 'Twitch did not return an authorization code. Please try again.',
      invalid_state: 'The Twitch sign-in request expired or could not be verified. Please try again.',
      token_exchange_failed: 'Twitch could not complete sign-in. Please try again.',
      token_validation_failed: 'Twitch could not verify the account. Please try again.',
      chat_scopes_missing: 'Reconnect Twitch and approve the requested chat access to enable chat collection.',
      configuration_missing: 'Twitch is not configured on the backend yet.',
    };
    return reason ? messages[reason] ?? 'Twitch sign-in could not be completed. Please try again.'
      : 'Twitch sign-in could not be completed. Please try again.';
  }

}
