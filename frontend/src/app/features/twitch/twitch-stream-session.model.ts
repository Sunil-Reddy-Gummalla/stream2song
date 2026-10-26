export interface TwitchStreamSession {
  connected: boolean;
  broadcasterId: string | null;
  login: string | null;
  online: boolean;
  twitchStreamId: string | null;
  eventType: 'stream.online' | 'stream.offline' | null;
  eventTimestamp: string | null;
  subscriptionStatuses: Record<string, string>;
}
