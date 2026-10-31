export interface TwitchClip {
  id: string;
  title: string;
  creatorName: string;
  createdAt: string;
  duration: number;
  url: string;
  thumbnailUrl: string;
  vodId: string | null;
  vodOffset: number | null;
  sessionAssociation: 'verified_target_session' | 'different_session' | 'unknown';
}

export interface TwitchClipsResponse {
  broadcasterId: string;
  association: 'recent_broadcaster_clips';
  targetSessionStreamId: string | null;
  targetSessionEventTimestamp: string | null;
  startedAt: string;
  endedAt: string;
  nextCursor: string | null;
  associationNote: string;
  clips: TwitchClip[];
}
