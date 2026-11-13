import { admin, authDevice, cors, json } from '../_shared/common.ts';
import { speak } from '../_shared/ai.ts';
import { countDictationDoneToday, isValidDay, reminderText } from '../_shared/logic.ts';

// deno-lint-ignore-file no-explicit-any
Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') return new Response('ok', { headers: cors });
  if (req.method !== 'POST') return json({ error: 'method_not_allowed' }, 405);
  let body: any;
  try { body = await req.json(); } catch { return json({ error: 'bad_json' }, 400); }
  const db = admin();
  if (!(await authDevice(db, body.token))) return json({ error: 'unauthorized' }, 401);
  if (!isValidDay(body.day)) return json({ error: 'bad_day' }, 400);

  // The hourly "your tasks are waiting" line, spoken with the kid's name. The text is fixed here (never taken from the phone);
  // the phone keeps the audio and asks again only when the name changes.
  if (body.kind === 'reminder') {
    const { data: kid } = await db.from('kids').select('id,name').eq('id', body.kid_id).eq('active', true).maybeSingle();
    if (!kid) return json({ error: 'kid_not_found' }, 404);
    const text = reminderText(kid.name);
    try {
      const a = await speak(text);
      return json({ text, audio_b64: a.data, sample_rate: a.sampleRate });
    } catch (e) { console.error(e); return json({ error: 'tts_unavailable', text }, 502); }
  }

  const { data: d } = await db.from('dictations').select('*').eq('id', body.dictation_id).maybeSingle();
  if (!d) return json({ error: 'not_found' }, 404);
  if (d.status === 'review') return json({ error: 'not_ready' }, 403);
  if (d.status !== 'ready') return json({ error: 'already_done' }, 409);

  const [{ data: st }, { data: mine }] = await Promise.all([
    db.from('settings').select('dictation_daily_limit').single(),
    db.from('dictations').select('status,done_day').eq('kid_id', d.kid_id).eq('done_day', body.day),
  ]);
  if (countDictationDoneToday(mine ?? [], body.day) >= (st?.dictation_daily_limit ?? 2)) return json({ error: 'daily_limit' }, 429);

  const phrases: string[] = d.phrases;
  const i = Number(body.index);
  if (!Number.isInteger(i) || i < 0 || i >= phrases.length) return json({ error: 'bad_index' }, 400);
  try {
    const a = await speak(phrases[i]);
    return json({ index: i, count: phrases.length, audio_b64: a.data, sample_rate: a.sampleRate });
  } catch (e) { console.error(e); return json({ error: 'tts_unavailable' }, 502); }
});
