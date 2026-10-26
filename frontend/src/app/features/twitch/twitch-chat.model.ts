export interface TwitchChatMessage {
  messageId: string;
  broadcasterId: string;
  streamId: string;
  timestamp: string;
  userId: string | null;
  username: string;
  text: string;
  messageType: string | null;
  color: string | null;
}

export interface TwitchChatSummary {
  collecting: boolean;
  collectionStatus: string;
  streamId: string | null;
  totalMessages: number;
}
