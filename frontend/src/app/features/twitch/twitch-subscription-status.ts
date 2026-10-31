export type StreamSubscriptionDisplay = {
  state: 'not_configured' | 'conflict_unresolved' | 'failed' | 'revoked' | 'pending' | 'active' | 'waiting';
  label: string;
  description: string;
  error: boolean;
};

export function streamSubscriptionDisplay(
  statuses: Record<string, string> | undefined,
): StreamSubscriptionDisplay | null {
  if (!statuses) return null;
  const streamStatuses = [statuses['stream.online'], statuses['stream.offline']].filter(
    (status): status is string => Boolean(status),
  );
  if (streamStatuses.length === 0) return null;

  if (streamStatuses.includes('conflict_unresolved')) {
    return {
      state: 'conflict_unresolved',
      label: 'Subscription conflict needs review',
      description: 'Twitch has a conflicting subscription that does not match this callback or event setup. Your Twitch account is still connected.',
      error: true,
    };
  }
  if (streamStatuses.some((status) => status.startsWith('revoked:'))) {
    return {
      state: 'revoked',
      label: 'Subscription revoked',
      description: 'Twitch revoked a stream subscription. Your account remains connected; retry setup after reviewing the subscription.',
      error: true,
    };
  }
  if (streamStatuses.some((status) => ['failed', 'unauthorized', 'authorization_required'].includes(status))) {
    const unauthorized = streamStatuses.includes('unauthorized');
    const authorizationRequired = streamStatuses.includes('authorization_required');
    return {
      state: 'failed',
      label: unauthorized ? 'Twitch rejected the app token' : 'Subscription setup failed',
      description: authorizationRequired
        ? 'Twitch requires authorization for this subscription. Your Twitch account is still connected.'
        : 'Check the backend EventSub configuration and logs. Your Twitch account is still connected.',
      error: true,
    };
  }
  if (streamStatuses.includes('not_configured')) {
    return {
      state: 'not_configured',
      label: 'EventSub not configured',
      description: 'Configure a public HTTPS EventSub callback and signing secret in the backend.',
      error: false,
    };
  }
  if (streamStatuses.includes('webhook_callback_verification_pending')) {
    return {
      state: 'pending',
      label: 'Subscription pending verification',
      description: 'Twitch accepted the subscription and is verifying the webhook callback.',
      error: false,
    };
  }
  if (streamStatuses.length === 2 && streamStatuses.every((status) => status === 'enabled')) {
    return {
      state: 'active',
      label: 'Stream subscriptions active',
      description: 'Twitch is ready to send online and offline events.',
      error: false,
    };
  }
  if (streamStatuses.some((status) => status === 'not_subscribed')) {
    return {
      state: 'waiting',
      label: 'Stream subscriptions not confirmed',
      description: 'Refresh status to retry setup and check Twitch for existing subscriptions.',
      error: false,
    };
  }
  return null;
}
