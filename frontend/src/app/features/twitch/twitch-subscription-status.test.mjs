import assert from 'node:assert/strict';
import test from 'node:test';
import { streamSubscriptionDisplay } from './twitch-subscription-status.ts';

test('enabled subscriptions map to an active state, not a connection failure', () => {
  const display = streamSubscriptionDisplay({
    'stream.online': 'enabled',
    'stream.offline': 'enabled',
  });

  assert.equal(display.state, 'active');
  assert.equal(display.error, false);
  assert.match(display.label, /active/i);
});

test('pending verification is reported as pending, not failed', () => {
  const display = streamSubscriptionDisplay({
    'stream.online': 'webhook_callback_verification_pending',
    'stream.offline': 'enabled',
  });

  assert.equal(display.state, 'pending');
  assert.equal(display.error, false);
  assert.match(display.label, /pending verification/i);
});

test('an unresolved conflict is a subscription issue while preserving account connection messaging', () => {
  const display = streamSubscriptionDisplay({
    'stream.online': 'conflict_unresolved',
    'stream.offline': 'enabled',
  });

  assert.equal(display.state, 'conflict_unresolved');
  assert.equal(display.error, true);
  assert.match(display.description, /account is still connected/i);
});
