/** Exponential backoff with full jitter (RFC 0003 §5.2). */
export const DEFAULT_BASE_MS = 200;
export const DEFAULT_CAP_MS = 30_000;

/** random(0, min(cap, base·2^attempt)) in milliseconds; rnd ∈ [0,1), attempt starts at 0. */
export function delay(attempt: number, baseMs: number, capMs: number, rnd: () => number): number {
  const n = Math.max(0, Math.floor(attempt));
  const limit = Math.min(capMs, baseMs * 2 ** Math.min(n, 60));
  return rnd() * limit;
}
