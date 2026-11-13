import { isSafePublicUrl } from './logic.ts';

/** The site refused our server (Iranian education sites usually block non-Iranian IPs). The phone must fetch it instead. */
export class SiteBlockedError extends Error {
  constructor(public status: number) { super('site_blocked_' + status); }
}

/**
 * Fetch a public page or media file for the tutor. Every hop (redirects included) must pass isSafePublicUrl,
 * the body is capped, and the call times out. This blocks the obvious ways of reaching internal services.
 */
export async function safeFetch(url: string, opts: { maxBytes: number; timeoutMs?: number; accept?: string }): Promise<{ bytes: Uint8Array; contentType: string; finalUrl: string }> {
  let cur = url;
  for (let hop = 0; hop < 4; hop++) {
    if (!isSafePublicUrl(cur)) throw new Error('unsafe_url');
    const res = await fetch(cur, {
      redirect: 'manual',
      headers: { 'User-Agent': 'Mozilla/5.0 (kids-tutor)', 'Accept': opts.accept ?? '*/*', 'Accept-Language': 'fa,en' },
      signal: AbortSignal.timeout(opts.timeoutMs ?? 15_000),
    });
    if (res.status >= 300 && res.status < 400) {
      const loc = res.headers.get('location');
      if (!loc) throw new Error('bad_redirect');
      cur = new URL(loc, cur).toString();
      await res.body?.cancel();
      continue;
    }
    if (res.status === 403 || res.status === 451 || res.status === 401) throw new SiteBlockedError(res.status);
    if (!res.ok) throw new Error('http_' + res.status);
    const len = Number(res.headers.get('content-length') ?? 0);
    if (len > opts.maxBytes) throw new Error('too_large');
    const reader = res.body!.getReader();
    const chunks: Uint8Array[] = [];
    let total = 0;
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.length;
      if (total > opts.maxBytes) { await reader.cancel(); throw new Error('too_large'); }
      chunks.push(value);
    }
    const bytes = new Uint8Array(total);
    let o = 0;
    for (const c of chunks) { bytes.set(c, o); o += c.length; }
    return { bytes, contentType: (res.headers.get('content-type') ?? '').split(';')[0].trim().toLowerCase(), finalUrl: cur };
  }
  throw new Error('too_many_redirects');
}

export function toBase64(bytes: Uint8Array): string {
  let s = '';
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(s);
}
