export interface CreateSongRequest {
  channelName: string;
}

export type SongJobStatus = 'PENDING';

export interface SongJob {
  id: string;
  channelName: string;
  status: SongJobStatus;
  createdAt: string;
}
