import { admin, authDevice, cors, json } from '../_shared/common.ts';
import { extractDictation } from '../_shared/ai.ts';
import { isValidDay } from '../_shared/logic.ts';

// deno-lint-ignore-file no-explicit-any
const MEDIA = new Set(['image/jpeg', 'image/png', 'image/webp']);
const MAX_PER_DAY = 5;

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') return new Response('ok', { headers: cors });
  if (req.method !== 'POST') return json({ error: 'method_not_allowed' }, 405);
  let body: any;
  try { body = await req.json(); } catch { return json({ error: 'bad_json' }, 400); }
  const db = admin();
  if (!(await authDevice(db, body.token))) return json({ error: 'unauthorized' }, 401);
  if (!isValidDay(body.day)) return json({ error: 'bad_day' }, 400);

  const { data: kid } = await db.from('kids').select('id').eq('id', body.kid_id).eq('active', true).maybeSingle();
  if (!kid) return json({ error: 'kid_not_found' }, 404);
  const b64 = body.image_b64, mediaType = body.media_type ?? 'image/jpeg';
  if (typeof b64 !== 'string' || b64.length < 1000) return json({ error: 'image_required' }, 400);
  if (b64.length > 7_000_000) return json({ error: 'image_too_large' }, 413);
  if (!MEDIA.has(mediaType)) return json({ error: 'bad_media_type' }, 400);

  // Cost / abuse guard: only a few book photos per kid per day
  const { count } = await db.from('dictations').select('id', { count: 'exact', head: true }).eq('kid_id', kid.id).eq('created_by', 'kid').eq('created_day', body.day);
  if ((count ?? 0) >= MAX_PER_DAY) return json({ error: 'daily_create_limit' }, 429);

  let d;
  try { d = await extractDictation({ b64, mediaType }); } catch (e) { console.error(e); return json({ error: 'ai_unavailable' }, 502); }
  if (!d.readable) return json({ status: 'retake', feedback: d.feedback || 'صفحه واضح نبود؛ دوباره عکس بگیر.' });

  const { data: st } = await db.from('settings').select('dictation_requires_review,dictation_points').single();
  const status = st?.dictation_requires_review === false ? 'ready' : 'review';
  const { data: row, error } = await db.from('dictations').insert({
    kid_id: kid.id, title: d.title, phrases: d.phrases, status, points: st?.dictation_points ?? 10, created_by: 'kid', created_day: body.day,
  }).select('id').single();
  if (error) return json({ error: 'db_error' }, 500);
  return json({ id: row.id, status, title: d.title, phrase_count: d.phrases.length });
});
