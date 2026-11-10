// Pure helpers (no network / no Deno APIs) so they can be unit-tested.

export type Task = { id: string; kind: 'daily' | 'weekly' | 'once'; weekdays: number[] | null };

/** 0 = Sunday ... 6 = Saturday, for a 'YYYY-MM-DD' string (timezone independent). */
export function weekdayOf(day: string): number {
  return new Date(day + 'T00:00:00Z').getUTCDay();
}

export function prevDay(day: string): string {
  const d = new Date(day + 'T00:00:00Z');
  d.setUTCDate(d.getUTCDate() - 1);
  return d.toISOString().slice(0, 10);
}

export function isValidDay(day: unknown): day is string {
  return typeof day === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(day) && !isNaN(Date.parse(day));
}

export type Schedule = {
  type?: 'daily' | 'weekdays' | 'monthly' | 'interval' | 'once';
  weekdays?: number[]; month_day?: number; every_n?: number; date?: string;
  start?: string | null; end?: string | null;
} | null;

export function dayOfMonth(day: string): number { return Number(day.slice(8, 10)); }
export function lastDayOfMonth(day: string): number {
  const y = Number(day.slice(0, 4)), m = Number(day.slice(5, 7));
  return new Date(Date.UTC(y, m, 0)).getUTCDate();
}
export function daysBetween(a: string, b: string): number {
  return Math.round((Date.parse(b + 'T00:00:00Z') - Date.parse(a + 'T00:00:00Z')) / 864e5);
}

/** Is this task due on `day`? Old tasks (no schedule) use kind/weekdays; new ones use the schedule. */
export function isDueOn(t: { kind: string; weekdays: number[] | null; schedule?: Schedule }, day: string, hasLogEver: boolean): boolean {
  const s = t.schedule;
  if (!s || !s.type) {
    if (t.kind === 'daily') return true;
    if (t.kind === 'weekly') return (t.weekdays ?? []).includes(weekdayOf(day));
    if (t.kind === 'once') return !hasLogEver;
    return false;
  }
  if (s.start && day < s.start) return false;
  if (s.end && day > s.end) return false;
  switch (s.type) {
    case 'daily': return true;
    case 'weekdays': return (s.weekdays ?? []).includes(weekdayOf(day));
    case 'monthly': return dayOfMonth(day) === Math.min(s.month_day ?? 1, lastDayOfMonth(day));
    case 'interval': {
      if (!s.start) return false;
      const diff = daysBetween(s.start, day);
      return diff >= 0 && diff % Math.max(1, s.every_n ?? 1) === 0;
    }
    case 'once': return s.date === day && !hasLogEver;
  }
  return false;
}

/** Task templates that need a log row on this day. */
export function dueTasks<T extends { id: string; kind: string; weekdays: number[] | null; schedule?: Schedule }>(tasks: T[], day: string, hasLogEver: Set<string>): T[] {
  return tasks.filter(t => isDueOn(t, day, hasLogEver.has(t.id)));
}

/** AI score is 0..20; points scale linearly with it. */
export function scaledPoints(base: number, score: number, max = 20): number {
  const s = Math.max(0, Math.min(max, score));
  return Math.round((base * s) / max);
}

export function shouldAutoApprove(score: number, threshold: number | null | undefined): boolean {
  return threshold != null && score >= threshold;
}

/** Daily bonus: only if the phone actually reported that day (else a dead phone would earn points). */
export function bonusFor(kid: { bonus_under_min: number | null; bonus_points: number }, totalMinutes: number, hadReport: boolean): number {
  if (!hadReport || kid.bonus_under_min == null || kid.bonus_points <= 0) return 0;
  return totalMinutes < kid.bonus_under_min ? kid.bonus_points : 0;
}

/** Pull the first JSON object out of a model reply (tolerates ```json fences and chatter). */
export function parseJsonLoose(text: string): any | null {
  const clean = text.replace(/```json|```/g, '');
  const start = clean.indexOf('{');
  if (start < 0) return null;
  let depth = 0, inStr = false, esc = false;
  for (let i = start; i < clean.length; i++) {
    const c = clean[i];
    if (inStr) { if (esc) esc = false; else if (c === '\\') esc = true; else if (c === '"') inStr = false; continue; }
    if (c === '"') inStr = true;
    else if (c === '{') depth++;
    else if (c === '}' && --depth === 0) {
      try { return JSON.parse(clean.slice(start, i + 1)); } catch { return null; }
    }
  }
  return null;
}

export type Grade = { score: number; feedback: string; readable: boolean };

/** Validate and clamp what the model returned. */
export function normalizeGrade(raw: any): Grade | null {
  if (!raw || typeof raw !== 'object') return null;
  const readable = raw.readable !== false;
  const score = Number(raw.score);
  if (readable && !Number.isFinite(score)) return null;
  return {
    readable,
    score: readable ? Math.max(0, Math.min(20, Math.round(score * 2) / 2)) : 0,
    feedback: String(raw.feedback ?? '').slice(0, 600),
  };
}

/** Persian/Arabic digits -> ASCII, strip separators. "۱٬۲۳۴٬۵۰۰" -> 1234500 */
export function parsePrice(v: unknown): number | null {
  if (typeof v === 'number') return v > 0 ? Math.round(v) : null;
  if (typeof v !== 'string') return null;
  const ascii = v.replace(/[۰-۹]/g, d => String(d.charCodeAt(0) - 1776)).replace(/[٠-٩]/g, d => String(d.charCodeAt(0) - 1632));
  const n = Number(ascii.replace(/[^\d.]/g, ''));
  return Number.isFinite(n) && n > 0 ? Math.round(n) : null;
}

/** Cheap og:image / og:title extraction from raw HTML. */
export function extractMeta(html: string): { title?: string; image?: string } {
  const pick = (prop: string) => {
    const re1 = new RegExp(`<meta[^>]+(?:property|name)=["']${prop}["'][^>]*content=["']([^"']+)["']`, 'i');
    const re2 = new RegExp(`<meta[^>]+content=["']([^"']+)["'][^>]*(?:property|name)=["']${prop}["']`, 'i');
    return (html.match(re1) ?? html.match(re2))?.[1];
  };
  return { title: pick('og:title'), image: pick('og:image') };
}

export function isHttpUrl(u: unknown): u is string {
  if (typeof u !== 'string') return false;
  try { const p = new URL(u).protocol; return p === 'http:' || p === 'https:'; } catch { return false; }
}

/** Validate dictation phrases coming from the model or a parent: 1..15 non-empty strings. */
export function normalizePhrases(raw: unknown, maxPhrases = 15): string[] {
  if (!Array.isArray(raw)) return [];
  return raw.filter((p): p is string => typeof p === 'string').map(p => p.replace(/\s+/g, ' ').trim().slice(0, 200)).filter(Boolean).slice(0, maxPhrases);
}

export function countDictationDoneToday(rows: { status: string; done_day: string | null }[], day: string): number {
  return rows.filter(r => (r.status === 'graded' || r.status === 'done') && r.done_day === day).length;
}

/** Find the last audio block in an Interactions API reply (steps[].content[]). */
export function findAudio(res: any): string | null {
  let found: string | null = null;
  for (const st of res?.steps ?? []) for (const c of st?.content ?? []) if (c?.type === 'audio' && typeof c.data === 'string') found = c.data;
  return found;
}

/** Calendar day in Iran (UTC+3:30) of an ISO timestamp. */
export function tehranDay(iso: string): string {
  return new Date(Date.parse(iso) + 3.5 * 3600_000).toISOString().slice(0, 10);
}

/** The last n days ending at `day`, oldest first. */
export function lastDays(day: string, n = 7): string[] {
  const out = [day];
  for (let i = 1; i < n; i++) out.unshift(prevDay(out[0]));
  return out;
}

/** Points EARNED (positive ledger rows) per day, aligned with `days`. */
export function weekPoints(rows: { delta: number; created_at: string }[], days: string[]): number[] {
  const m = new Map(days.map(d => [d, 0]));
  for (const r of rows) if (r.delta > 0) { const d = tehranDay(r.created_at); if (m.has(d)) m.set(d, m.get(d)! + r.delta); }
  return days.map(d => m.get(d)!);
}

/** Kid/parent PINs are 4 to 8 digits. */
export function isValidPin(p: unknown): p is string {
  return typeof p === 'string' && /^\d{4,8}$/.test(p);
}

/** AI-made drawing for the tutor's whiteboard. Only plain SVG shapes are allowed; anything risky -> null. */
export function cleanSvg(svg: unknown): string | null {
  if (typeof svg !== 'string') return null;
  const s = svg.trim().slice(0, 8000);
  if (!/^<svg[\s>]/i.test(s) || !/<\/svg>$/i.test(s)) return null;
  if (/<\s*(script|foreignobject|iframe|object|embed|image|use|style|link|meta|animate|set)\b/i.test(s)) return null;
  if (/\son[a-z]+\s*=/i.test(s) || /javascript:|data:|https?:/i.test(s)) return null;
  return s;
}

/** Key used for the automatic penalty of one unfinished task on one day (unique per kid). */
export function autoPenaltyNote(day: string, taskId: string): string { return `auto:${day}:${taskId}`; }

/** Today's date in Iran (UTC+3:30, no DST). The server must never use UTC for a "day". */
export function tehranToday(now: Date = new Date()): string {
  return new Date(now.getTime() + 3.5 * 3600_000).toISOString().slice(0, 10);
}


// ---------------- Lessons from a textbook QR ----------------

/** Hostname is exactly one of the allowed domains or a sub-domain of one. Empty list = nothing allowed. */
export function hostAllowed(url: string, allowedCsv: string): boolean {
  let host: string;
  try { host = new URL(url).hostname.toLowerCase(); } catch { return false; }
  const list = allowedCsv.split(',').map(x => x.trim().toLowerCase().replace(/^\./, '')).filter(Boolean);
  return list.some(d => host === d || host.endsWith('.' + d));
}

/** Only public http(s) pages: no credentials, no odd ports, no localhost / private / link-local / IP-literal hosts. */
export function isSafePublicUrl(u: unknown): u is string {
  if (typeof u !== 'string' || u.length > 600) return false;
  let p: URL;
  try { p = new URL(u); } catch { return false; }
  if (p.protocol !== 'http:' && p.protocol !== 'https:') return false;
  if (p.username || p.password) return false;
  if (p.port && p.port !== '80' && p.port !== '443') return false;
  const h = p.hostname.toLowerCase();
  if (!h.includes('.') || h.startsWith('[') || /^\d+(\.\d+){3}$/.test(h) || /^\d+$/.test(h)) return false;   // no IP literals / single-label hosts
  if (h === 'localhost' || h.endsWith('.localhost') || h.endsWith('.local') || h.endsWith('.internal') || h.endsWith('.lan') || h.endsWith('.home')) return false;
  return true;
}

export type MediaItem = { type: 'video' | 'audio' | 'page'; url: string; title?: string };

/** Find playable media on a page: <video>/<audio>/<source>, direct links, og:video, and known embed iframes. */
export function extractMedia(html: string, baseUrl: string, max = 8): MediaItem[] {
  const out: MediaItem[] = [];
  const seen = new Set<string>();
  const add = (raw: string | undefined, type: MediaItem['type'], title?: string) => {
    if (!raw) return;
    let abs: string;
    try { abs = new URL(raw.trim().replace(/&amp;/g, '&'), baseUrl).toString(); } catch { return; }
    if (!isSafePublicUrl(abs) || seen.has(abs) || out.length >= max) return;
    seen.add(abs); out.push({ type, url: abs, ...(title ? { title: title.slice(0, 80) } : {}) });
  };
  const VID = /\.(mp4|webm|m4v|mov|m3u8)(\?|#|$)/i, AUD = /\.(mp3|m4a|aac|ogg|oga|wav)(\?|#|$)/i;
  for (const m of html.matchAll(/<video\b[^>]*\bsrc=["']([^"']+)["']/gi)) add(m[1], 'video');
  for (const m of html.matchAll(/<audio\b[^>]*\bsrc=["']([^"']+)["']/gi)) add(m[1], 'audio');
  for (const m of html.matchAll(/<source\b[^>]*\bsrc=["']([^"']+)["'][^>]*>/gi)) add(m[1], AUD.test(m[1]) || /type=["']audio/i.test(m[0]) ? 'audio' : 'video');
  for (const m of html.matchAll(/<meta[^>]+property=["']og:(video|audio)(?::url|:secure_url)?["'][^>]*content=["']([^"']+)["']/gi)) add(m[2], m[1] === 'audio' ? 'audio' : 'video');
  for (const m of html.matchAll(/<a\b[^>]*\bhref=["']([^"']+)["'][^>]*>([\s\S]*?)<\/a>/gi)) {
    const t = m[2].replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim();
    if (VID.test(m[1])) add(m[1], 'video', t); else if (AUD.test(m[1])) add(m[1], 'audio', t);
  }
  for (const m of html.matchAll(/<iframe\b[^>]*\bsrc=["']([^"']+)["']/gi)) if (/aparat\.com|youtube\.com\/embed|youtube-nocookie\.com/i.test(m[1])) add(m[1], 'page');
  return out;
}

export type Section = { title: string; asked: number; correct: number; done: boolean };

export function normalizeSections(raw: unknown, max = 12): Section[] {
  if (!Array.isArray(raw)) return [];
  return raw.map(x => typeof x === 'string' ? x : (x as any)?.title).filter((x): x is string => typeof x === 'string')
    .map(x => x.replace(/\s+/g, ' ').trim().slice(0, 100)).filter(Boolean).slice(0, max)
    .map(title => ({ title, asked: 0, correct: 0, done: false }));
}

/** Record one practice round on a section; a section is done after >= 5 questions with >= 70% correct. */
export function applyPractice(sections: Section[], idx: number, correct: number, total: number): Section[] {
  return sections.map((s, i) => {
    if (i !== idx) return s;
    const asked = s.asked + total, ok = s.correct + correct;
    return { ...s, asked, correct: ok, done: s.done || (asked >= 5 && ok / asked >= 0.7) };
  });
}

/** Where to continue: the first section that is not done (or the last one if all are). */
export function nextSection(sections: Section[]): number {
  const i = sections.findIndex(s => !s.done);
  return i === -1 ? Math.max(0, sections.length - 1) : i;
}

/** Segments for dictation from audio: sorted, within the audio length, each 0.5s..30s. */
export function cleanSegments(raw: unknown, maxSeconds = 3600): { text: string; start: number; end: number }[] {
  if (!Array.isArray(raw)) return [];
  const out: { text: string; start: number; end: number }[] = [];
  for (const r of raw as any[]) {
    const text = typeof r?.text === 'string' ? r.text.replace(/\s+/g, ' ').trim().slice(0, 200) : '';
    const start = Number(r?.start), end = Number(r?.end);
    if (!text || !Number.isFinite(start) || !Number.isFinite(end)) continue;
    if (start < 0 || end > maxSeconds || end - start < 0.5 || end - start > 30) continue;
    if (out.length && start < out[out.length - 1].start) continue;
    out.push({ text, start: Math.round(start * 10) / 10, end: Math.round(end * 10) / 10 });
  }
  return out.slice(0, 15);
}
