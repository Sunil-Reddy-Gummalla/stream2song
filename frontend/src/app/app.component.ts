import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { finalize } from 'rxjs';
import { SongJob } from './features/songs/song-job.model';
import { SongsApiService } from './features/songs/songs-api.service';
import { TwitchApiService } from './features/twitch/twitch-api.service';
import { TwitchConnectionStatus } from './features/twitch/twitch-connection.model';
import { TwitchStreamSession } from './features/twitch/twitch-stream-session.model';
import { TwitchChatMessage, TwitchChatSummary } from './features/twitch/twitch-chat.model';

const CHANNEL_NAME_PATTERN = /^[A-Za-z0-9_]{4,25}$/;

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
          <span class="demo-badge"><span></span> Project preview</span>
        </div>
      </header>

      <main id="top" class="main-content">
        <section class="hero" aria-labelledby="hero-title">
          <div class="hero-copy">
            <p class="eyebrow"><span class="eyebrow-line"></span> YOUR STREAM, REMIXED</p>
            <h1 id="hero-title">Your best moments.<br><span>In a different key.</span></h1>
            <p class="hero-description">
              Start a song project from your channel. We’ll keep track of your request here while the generation workflow is being built.
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
                    <span class="chat-state" [class.chat-active]="chatSummary()?.collecting">
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
                @if (twitchSession()?.subscriptionStatuses?.['stream.online'] === 'not_configured'
                  || twitchSession()?.subscriptionStatuses?.['stream.offline'] === 'not_configured') {
                  <span class="stream-hint">Configure the public HTTPS EventSub callback and signing secret in the backend.</span>
                } @else if (twitchSession()?.subscriptionStatuses?.['stream.online'] === 'failed'
                  || twitchSession()?.subscriptionStatuses?.['stream.offline'] === 'failed'
                  || twitchSession()?.subscriptionStatuses?.['channel.chat.message'] === 'failed'
                  || twitchSession()?.subscriptionStatuses?.['channel.chat.message'] === 'authorization_required') {
                  <span class="stream-hint error">Twitch EventSub setup failed. Check backend configuration and reconnect.</span>
                } @else if (twitchSession()?.subscriptionStatuses?.['stream.online']?.startsWith('revoked:')
                  || twitchSession()?.subscriptionStatuses?.['stream.offline']?.startsWith('revoked:')) {
                  <span class="stream-hint error">Twitch revoked a stream subscription. Reconnect to retry setup.</span>
                } @else if (twitchSession()?.subscriptionStatuses?.['stream.online'] === 'webhook_callback_verification_pending'
                  || twitchSession()?.subscriptionStatuses?.['stream.offline'] === 'webhook_callback_verification_pending') {
                  <span class="stream-hint">Waiting for Twitch to verify the webhook callback.</span>
                }
                @if (twitchSession()?.subscriptionStatuses?.['stream.online'] === 'failed'
                  || twitchSession()?.subscriptionStatuses?.['stream.offline'] === 'failed'
                  || twitchSession()?.subscriptionStatuses?.['channel.chat.message'] === 'failed'
                  || twitchSession()?.subscriptionStatuses?.['channel.chat.message'] === 'authorization_required'
                  || twitchSession()?.subscriptionStatuses?.['stream.online']?.startsWith('revoked:')
                  || twitchSession()?.subscriptionStatuses?.['stream.offline']?.startsWith('revoked:')) {
                  <a class="session-refresh" [href]="twitchConnectUrl">Retry connection</a>
                }
                <button class="session-refresh" type="button" (click)="loadTwitchSession(true)" [disabled]="twitchSessionLoading()">{{ twitchSessionLoading() ? 'Refreshing…' : 'Refresh status' }}</button>
              </div>
            }
          </section>

          <section class="panel create-panel" aria-labelledby="create-title">
            <div class="panel-heading">
              <div class="heading-icon create-icon" aria-hidden="true">♫</div>
              <div>
                <p class="panel-kicker">GET STARTED</p>
                <h2 id="create-title">Create a song project</h2>
              </div>
            </div>
            <p class="panel-description">Enter your Twitch channel name to start a new project.</p>

            <form (ngSubmit)="createSong()" novalidate>
              <label for="channel-name">Twitch channel name</label>
              <div class="input-wrap" [class.input-error]="showChannelError()">
                <span class="input-at" aria-hidden="true">@</span>
                <input
                  id="channel-name"
                  name="channelName"
                  type="text"
                  autocomplete="off"
                  autocapitalize="none"
                  spellcheck="false"
                  maxlength="25"
                  placeholder="your_channel"
                  [ngModel]="channelName()"
                  (ngModelChange)="onChannelNameChange($event)"
                  (blur)="channelTouched.set(true)"
                  [attr.aria-invalid]="showChannelError()"
                  aria-describedby="channel-hint channel-error"
                >
              </div>
              <div class="field-notes">
                <span id="channel-hint">4–25 letters, numbers, or underscores</span>
                <span class="character-count">{{ channelName().length }}/25</span>
              </div>
              @if (showChannelError()) {
                <p id="channel-error" class="field-error" role="alert">{{ channelErrorMessage() }}</p>
              }

              <button class="primary-button" type="submit" [disabled]="submitting()">
                @if (submitting()) {
                  <span class="spinner" aria-hidden="true"></span>
                  Creating project…
                } @else {
                  <span aria-hidden="true">✦</span>
                  Generate My Song
                  <span class="button-arrow" aria-hidden="true">→</span>
                }
              </button>
            </form>

            @if (successMessage()) {
              <div class="notice notice-success" role="status">
                <span class="notice-icon" aria-hidden="true">✓</span>
                <span>{{ successMessage() }}</span>
              </div>
            }
            @if (createError()) {
              <div class="notice notice-error" role="alert">
                <span class="notice-icon" aria-hidden="true">!</span>
                <span>{{ createError() }}</span>
              </div>
            }
            <p class="honest-note"><span aria-hidden="true">ⓘ</span> This creates a project request. Song generation is not active yet.</p>
          </section>

          <section class="panel jobs-panel" aria-labelledby="jobs-title">
            <div class="jobs-heading">
              <div class="panel-heading">
                <div class="heading-icon jobs-icon" aria-hidden="true">▤</div>
                <div>
                  <p class="panel-kicker">YOUR WORKSPACE</p>
                  <h2 id="jobs-title">Song projects <span class="job-count">{{ jobs().length }}</span></h2>
                </div>
              </div>
              <button class="refresh-button" type="button" (click)="loadJobs()" [disabled]="jobsLoading()" aria-label="Refresh song projects" title="Refresh projects">
                <span [class.spinning]="jobsLoading()" aria-hidden="true">↻</span>
              </button>
            </div>

            @if (jobsError()) {
              <div class="inline-error" role="alert">
                <span>{{ jobsError() }}</span>
                <button type="button" (click)="loadJobs()">Try again</button>
              </div>
            } @else if (jobsLoading() && jobs().length === 0) {
              <div class="loading-state" role="status"><span class="spinner"></span> Loading your projects…</div>
            } @else if (jobs().length === 0) {
              <div class="empty-state">
                <div class="empty-illustration" aria-hidden="true"><span>♫</span><i></i><i></i><i></i></div>
                <h3>Your first project starts here</h3>
                <p>Once you create a project, it’ll show up here with its current status.</p>
                <span class="empty-hint"><span aria-hidden="true">↖</span> Enter your channel name to begin</span>
              </div>
            } @else {
              <div class="job-list">
                @for (job of jobs(); track job.id) {
                  <article class="job-card">
                    <div class="job-avatar" aria-hidden="true">{{ job.channelName.charAt(0).toUpperCase() }}</div>
                    <div class="job-main">
                      <div class="job-title-row">
                        <h3>{{ job.channelName }}</h3>
                        <span class="status-pill" [class.status-pending]="job.status === 'PENDING'">{{ job.status }}</span>
                      </div>
                      <p class="job-id" [title]="job.id">ID · {{ job.id }}</p>
                      <p class="job-date">Created {{ job.createdAt | date:'medium' }}</p>
                    </div>
                    <span class="job-more" aria-hidden="true">⋯</span>
                  </article>
                }
              </div>
            }
            <div class="jobs-footnote"><span class="footnote-dot"></span> Projects are saved for this backend session</div>
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
    .demo-badge span, .footnote-dot { background: #a77aff; border-radius: 50%; height: 6px; width: 6px; }
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
    .dashboard-grid { align-items: stretch; display: grid; gap: 1.1rem; grid-template-columns: minmax(310px, .88fr) minmax(0, 1.4fr); }
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
    .chat-messages { display: grid; flex-basis: 100%; gap: .35rem; max-height: 180px; overflow: auto; }
    .chat-message { align-items: baseline; background: #11101980; border-radius: 6px; display: flex; font-size: .7rem; gap: .5rem; min-width: 0; padding: .4rem .55rem; }
    .chat-message strong { color: #c7a9ff; flex: 0 0 auto; }
    .chat-message span { color: #d5d0df; overflow-wrap: anywhere; }
    .chat-message time { color: #817b8e; flex: 0 0 auto; font-size: .62rem; margin-left: auto; }
    .session-refresh { background: transparent; border: 0; color: #b69ae8; cursor: pointer; font: inherit; font-size: .66rem; padding: .2rem; }
    .session-refresh:disabled { cursor: wait; opacity: .55; }
    .panel { background: linear-gradient(150deg, #191725, #17151f); border: 1px solid var(--line); border-radius: 16px; box-shadow: 0 12px 40px #00000014; padding: 1.5rem; }
    .panel-heading { align-items: center; display: flex; gap: .8rem; }
    .heading-icon { align-items: center; border-radius: 10px; display: flex; font-size: 1.05rem; height: 40px; justify-content: center; width: 40px; }
    .create-icon { background: #34264f; color: #c5a9ff; }
    .jobs-icon { background: #1c3340; color: #85d8e2; }
    .panel-kicker { font-size: .59rem; margin-bottom: .28rem; }
    h2 { color: var(--text); font-size: 1rem; letter-spacing: -.025em; margin: 0; }
    .panel-description { color: var(--muted); font-size: .84rem; line-height: 1.55; margin: 1.1rem 0 1.35rem; }
    label { color: #e4e0ec; display: block; font-size: .76rem; font-weight: 650; margin-bottom: .52rem; }
    .input-wrap { align-items: center; background: #111019; border: 1px solid #393546; border-radius: 9px; display: flex; transition: border-color .18s, box-shadow .18s; }
    .input-wrap:focus-within { border-color: #a078f3; box-shadow: 0 0 0 3px #9d78f31c; }
    .input-wrap.input-error { border-color: #ef6f83; }
    .input-at { color: #978ca9; font-size: .95rem; padding-left: .85rem; }
    input { background: transparent; border: 0; color: var(--text); flex: 1; font: inherit; font-size: .86rem; min-width: 0; outline: 0; padding: .78rem .65rem; }
    input::placeholder { color: #696477; }
    .field-notes { color: #827d8e; display: flex; font-size: .66rem; justify-content: space-between; margin: .45rem 0 1.12rem; }
    .character-count { font-variant-numeric: tabular-nums; }
    .field-error { color: #ff9ba7; font-size: .72rem; margin: -.7rem 0 .85rem; }
    .primary-button { align-items: center; background: linear-gradient(105deg, #9266ed, #7346d3); border: 0; border-radius: 9px; box-shadow: 0 6px 18px #794fd239; color: #fff; cursor: pointer; display: flex; font: inherit; font-size: .8rem; font-weight: 700; gap: .55rem; justify-content: center; min-height: 43px; padding: .65rem .9rem; transition: filter .15s, transform .15s; width: 100%; }
    .primary-button:hover:not(:disabled) { filter: brightness(1.1); transform: translateY(-1px); }
    .primary-button:disabled { cursor: wait; opacity: .75; }
    .button-arrow { font-size: 1rem; margin-left: auto; }
    .spinner { animation: spin .85s linear infinite; border: 2px solid #ffffff50; border-radius: 50%; border-top-color: #fff; display: inline-block; height: 14px; width: 14px; }
    .notice { align-items: flex-start; border-radius: 8px; display: flex; font-size: .73rem; gap: .55rem; line-height: 1.45; margin-top: .9rem; padding: .7rem .75rem; }
    .notice-success { background: #162a25; border: 1px solid #285542; color: #9de0bf; }
    .notice-error { background: #301b25; border: 1px solid #5a303e; color: #ffabb5; }
    .notice-icon { align-items: center; border: 1px solid currentColor; border-radius: 50%; display: inline-flex; flex: 0 0 16px; font-size: .62rem; height: 16px; justify-content: center; margin-top: 1px; }
    .honest-note { align-items: flex-start; color: #898495; display: flex; font-size: .68rem; gap: .45rem; line-height: 1.45; margin: 1.1rem 0 0; }
    .honest-note span { color: #b29bdf; }
    .jobs-panel { display: flex; flex-direction: column; min-height: 390px; }
    .jobs-heading { align-items: center; display: flex; justify-content: space-between; }
    .job-count { background: #2b2637; border-radius: 99px; color: #c1b5d5; display: inline-block; font-size: .62rem; font-weight: 600; margin-left: .35rem; min-width: 20px; padding: .2rem .4rem; text-align: center; vertical-align: 2px; }
    .refresh-button { align-items: center; background: #211e2b; border: 1px solid #393445; border-radius: 8px; color: #c7bed5; cursor: pointer; display: flex; font-size: 1.2rem; height: 34px; justify-content: center; transition: background .15s; width: 34px; }
    .refresh-button:hover:not(:disabled) { background: #302a3c; }
    .refresh-button:disabled { cursor: wait; }
    .spinning { animation: spin .85s linear infinite; display: inline-block; }
    .empty-state { align-items: center; display: flex; flex: 1; flex-direction: column; justify-content: center; min-height: 250px; padding: 1rem; text-align: center; }
    .empty-illustration { align-items: center; background: radial-gradient(circle, #312749, #201a2c); border: 1px solid #44345e; border-radius: 50%; color: #c0a2ff; display: flex; font-size: 1.35rem; gap: 3px; height: 62px; justify-content: center; margin-bottom: 1rem; position: relative; width: 62px; }
    .empty-illustration i { background: #8a68ce; border-radius: 2px; height: 10px; width: 3px; }
    .empty-illustration i:nth-of-type(2) { height: 17px; }
    .empty-illustration i:nth-of-type(3) { height: 7px; }
    .empty-state h3 { color: #e7e2ed; font-size: .87rem; margin: 0 0 .4rem; }
    .empty-state p { color: var(--muted); font-size: .75rem; line-height: 1.5; margin: 0; max-width: 265px; }
    .empty-hint { color: #a99bbd; font-size: .66rem; margin-top: 1rem; }
    .empty-hint span { color: #b798f4; font-size: .85rem; }
    .loading-state { align-items: center; color: var(--muted); display: flex; flex: 1; font-size: .8rem; gap: .65rem; justify-content: center; min-height: 210px; }
    .inline-error { align-items: center; background: #301b25; border: 1px solid #5a303e; border-radius: 8px; color: #ffabb5; display: flex; font-size: .74rem; gap: .8rem; justify-content: space-between; margin-top: 1.5rem; padding: .75rem; }
    .inline-error button { background: transparent; border: 0; color: #ffd7dc; cursor: pointer; font: inherit; font-weight: 700; white-space: nowrap; }
    .job-list { display: grid; gap: .6rem; margin-top: 1.2rem; }
    .job-card { align-items: flex-start; background: #1e1b28; border: 1px solid #302b3b; border-radius: 10px; display: flex; gap: .75rem; min-width: 0; padding: .85rem; }
    .job-avatar { align-items: center; background: linear-gradient(145deg, #58427c, #372a4f); border: 1px solid #715d91; border-radius: 9px; color: #e6d9ff; display: flex; flex: 0 0 36px; font-size: .86rem; font-weight: 700; height: 36px; justify-content: center; }
    .job-main { flex: 1; min-width: 0; }
    .job-title-row { align-items: center; display: flex; flex-wrap: wrap; gap: .5rem; }
    .job-title-row h3 { color: #ede8f5; font-size: .79rem; margin: 0; }
    .status-pill { background: #272330; border: 1px solid #4b4456; border-radius: 99px; color: #c7bed2; font-size: .57rem; font-weight: 750; letter-spacing: .07em; padding: .2rem .43rem; }
    .status-pill.status-pending { background: #302747; border-color: #5b4781; color: #d2baff; }
    .job-id, .job-date { color: #90899c; font-size: .65rem; margin: .38rem 0 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .job-id { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
    .job-date { color: #777183; font-family: inherit; margin-top: .25rem; }
    .job-more { color: #898298; font-size: 1.2rem; letter-spacing: .06em; line-height: 1; }
    .jobs-footnote { align-items: center; border-top: 1px solid #302b3a; color: #827c8e; display: flex; font-size: .64rem; gap: .45rem; margin-top: auto; padding-top: .9rem; }
    .footnote-dot { background: #e0b55f; height: 5px; width: 5px; }
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
      .jobs-panel { min-height: 330px; }
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
export class AppComponent implements OnInit {
  private readonly songsApi = inject(SongsApiService);
  private readonly twitchApi = inject(TwitchApiService);
  readonly twitchConnectUrl = this.twitchApi.connectUrl;

  readonly channelName = signal('');
  readonly channelTouched = signal(false);
  readonly submitting = signal(false);
  readonly jobsLoading = signal(false);
  readonly jobs = signal<SongJob[]>([]);
  readonly createError = signal('');
  readonly jobsError = signal('');
  readonly successMessage = signal('');
  readonly twitchLoading = signal(true);
  readonly twitchStatus = signal<TwitchConnectionStatus | null>(null);
  readonly twitchSession = signal<TwitchStreamSession | null>(null);
  readonly twitchSessionLoading = signal(true);
  readonly chatSummary = signal<TwitchChatSummary | null>(null);
  readonly chatMessages = signal<TwitchChatMessage[]>([]);
  readonly chatLoading = signal(false);
  readonly chatError = signal('');
  readonly twitchMessage = signal('');
  readonly twitchError = signal('');
  readonly channelIsValid = computed(() => CHANNEL_NAME_PATTERN.test(this.channelName()));
  readonly showChannelError = computed(() => this.channelTouched() && !this.channelIsValid());
  readonly channelErrorMessage = computed(() => this.channelName().trim()
    ? 'Use 4–25 letters, numbers, or underscores.'
    : 'Enter a Twitch channel name.');

  ngOnInit(): void {
    this.loadJobs();
    this.handleTwitchCallback();
    this.loadTwitchStatus();
    this.loadTwitchSession();
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
      next: (status) => this.twitchStatus.set(status),
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
      },
      error: () => {
        this.twitchSession.set(null);
        this.twitchError.set('Could not load Twitch stream status. Check that the backend is running.');
      },
    });
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
      not_configured: 'Chat webhook not configured',
      failed: 'Chat subscription failed',
      disconnected: 'Twitch disconnected',
    };
    return status ? labels[status] ?? status : 'Checking chat status';
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

  onChannelNameChange(value: string): void {
    this.channelName.set(value);
    this.createError.set('');
    this.successMessage.set('');
  }

  createSong(): void {
    this.channelTouched.set(true);
    if (!this.channelIsValid() || this.submitting()) return;

    this.submitting.set(true);
    this.createError.set('');
    this.successMessage.set('');

    this.songsApi.create({ channelName: this.channelName() }).pipe(
      finalize(() => this.submitting.set(false)),
    ).subscribe({
      next: (job) => {
        this.successMessage.set(`Project created for ${job.channelName}. Its current status is ${job.status}.`);
        this.channelName.set('');
        this.channelTouched.set(false);
        this.loadJobs();
      },
      error: (error: unknown) => {
        this.createError.set(this.errorMessage(error, 'Could not create the song project. Please try again.'));
      },
    });
  }

  loadJobs(): void {
    this.jobsLoading.set(true);
    this.jobsError.set('');
    this.songsApi.list().pipe(
      finalize(() => this.jobsLoading.set(false)),
    ).subscribe({
      next: (jobs) => this.jobs.set(jobs),
      error: (error: unknown) => {
        this.jobsError.set(this.errorMessage(error, 'Could not load song projects. Check that the backend is running.'));
      },
    });
  }

  private errorMessage(error: unknown, fallback: string): string {
    if (error instanceof HttpErrorResponse) {
      const detail = error.error?.detail;
      if (typeof detail === 'string' && detail.trim()) return detail;
      if (error.status === 0) return 'Can’t reach the backend. Make sure Spring Boot is running on port 8080.';
      if (error.status >= 500) return 'The backend had a problem. Please try again in a moment.';
    }
    return fallback;
  }
}
