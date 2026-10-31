export class ClipAnalysisEpochs {
  private readonly values = new Map<string, number>();
  private nextValue = 0;

  current(key: string): number { return this.values.get(key) ?? -1; }

  next(key: string): number {
    const value = ++this.nextValue;
    this.values.delete(key);
    this.values.set(key, value);
    while (this.values.size > 100) this.values.delete(this.values.keys().next().value!);
    return value;
  }

  invalidateAll(): void {
    for (const key of [...this.values.keys()]) this.next(key);
  }
}
