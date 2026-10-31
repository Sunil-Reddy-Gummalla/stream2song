import { Subscription } from 'rxjs';

export type ClipRequestKey<K extends string> = { clipId: string; kind: K };

export class ClipRequestSubscriptions<K extends string> {
  private readonly requests = new Map<string, Map<K, Subscription>>();

  track(clipId: string, kind: K, subscription: Subscription): void {
    this.cancel(clipId, kind);
    let byKind = this.requests.get(clipId);
    if (!byKind) { byKind = new Map(); this.requests.set(clipId, byKind); }
    byKind.set(kind, subscription);
  }

  isCurrent(clipId: string, kind: K, subscription: Subscription): boolean {
    return this.requests.get(clipId)?.get(kind) === subscription;
  }

  finish(clipId: string, kind: K, subscription: Subscription): boolean {
    if (!this.isCurrent(clipId, kind, subscription)) return false;
    this.remove(clipId, kind);
    return true;
  }

  cancel(clipId: string, kind: K, onCancel?: () => void): boolean {
    const subscription = this.requests.get(clipId)?.get(kind);
    if (!subscription) return false;
    this.remove(clipId, kind);
    subscription.unsubscribe();
    onCancel?.();
    return true;
  }

  cancelClip(clipId: string, onCancel: (kind: K) => void): void {
    const kinds = [...(this.requests.get(clipId)?.keys() ?? [])];
    for (const kind of kinds) this.cancel(clipId, kind, () => onCancel(kind));
  }

  cancelAll(onCancel: (clipId: string, kind: K) => void): void {
    for (const clipId of [...this.requests.keys()]) this.cancelClip(clipId, kind => onCancel(clipId, kind));
  }

  private remove(clipId: string, kind: K): void {
    const byKind = this.requests.get(clipId);
    byKind?.delete(kind);
    if (byKind?.size === 0) this.requests.delete(clipId);
  }
}
