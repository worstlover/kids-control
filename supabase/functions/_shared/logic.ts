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

/** Which task templates need a log row on this day. "once" tasks get a row only if they have none yet. */
export function dueTasks<T extends Task>(tasks: T[], day: string, hasLogEver: Set<string>): T[] {
  const wd = weekdayOf(day);
  return tasks.filter(t =>
    t.kind === 'daily' ||
    (t.kind === 'weekly' && (t.weekdays ?? []).includes(wd)) ||
    (t.kind === 'once' && !hasLogEver.has(t.id)));
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
