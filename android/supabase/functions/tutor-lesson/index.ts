import { admin, authDevice, cors, json } from '../_shared/common.ts';
import { extractLesson, transcribeLessonAudio } from '../_shared/ai.ts';
import { safeFetch, toBase64 } from '../_shared/net.ts';
import { extractMedia, hostAllowed, isSafePublicUrl, isValidDay, nextSection, splitSubject, subjectKey } from '../_shared/logic.ts';

// deno-lint-ignore-file no-explicit-any
// Lessons from the textbook: first page photo and/or QR link -> sections + media; progress is remembered.
const MEDIA_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp']);
const AUDIO_TYPES: Record<string, string> = { 'audio/mpeg': 'audio/mp3', 'audio/mp3': 'audio/mp3', 'audio/mp4': 'audio/aac', 'audio/x-m4a': 'audio/aac', 'audio/aac': 'audio/aac', 'audio/wav': 'audio/wav', 'audio/x-wav': 'audio/wav', 'audio/ogg': 'audio/ogg' };
const MAX_LESSONS_PER_DAY = 8;
const MAX_AUDIO_BYTES = 12_000_000;

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') return new Response('ok', { headers: cors });
  if (req.method !== 'POST') return json({ error: 'method_not_allowed' }, 405);
  let body: any;
  try { body = await req.json(); } catch { return json({ error: 'bad_json' }, 400); }
  const db = admin();
  if (!(await authDevice(db, body.token))) return json({ error: 'unauthorized' }, 401);
  const { data: kid } = await db.from('kids').select('id').eq('id', body.kid_id).eq('active', true).maybeSingle();
  if (!kid) return json({ error: 'kid_not_found' }, 404);
  const day = body.day;
  if (!isValidDay(day)) return json({ error: 'bad_day' }, 400);
  const { data: st } = await db.from('settings').select('lesson_hosts,dictation_requires_review,dictation_points').single();
  const hosts: string = st?.lesson_hosts ?? '';

  try {
    switch (body.action) {
      case 'create': return await create(db, kid, body, day, hosts);
      case 'list': return await list(db, kid, body);
      case 'get': return await getLesson(db, kid, body);
      case 'progress': return await progress(db, kid, body);
      case 'attach_qr': return await attachQr(db, kid, body, hosts);
      case 'audio_dictation': return await audioDictation(db, kid, body, day, hosts, st);
      default: return json({ error: 'bad_action' }, 400);
    }
  } catch (e) {
    console.error(e);
    return json({ error: 'lesson_unavailable', detail: String((e as Error).message).slice(0, 120) }, 502);
  }
});

// School lessons and free classes are stored apart (body.track = 'school' | 'free'), same as the tutor function.
const subjectOf = (b: any) => subjectKey(b.subject, b.track);
const publicLesson = (l: any) => ({ id: l.id, subject: splitSubject(l.subject).name, track: splitSubject(l.subject).track, title: l.title, sections: l.sections, position: l.position, status: l.status, media: l.media, updated_at: l.updated_at });

async function create(db: any, kid: any, body: any, day: string, hosts: string) {
  const subject = subjectOf(body);
  if (!subject) return json({ error: 'subject_required' }, 400);
  const img = typeof body.image_b64 === 'string' ? body.image_b64 : null;
  const qr = typeof body.qr_url === 'string' ? body.qr_url.trim() : '';
  if (!img && !qr) return json({ error: 'photo_or_qr_required' }, 400);
  if (img && (img.length < 1000 || img.length > 7_000_000)) return json({ error: 'bad_image_size' }, 400);
  const mediaType = body.media_type ?? 'image/jpeg';
  if (img && !MEDIA_TYPES.has(mediaType)) return json({ error: 'bad_media_type' }, 400);

  const { count } = await db.from('tutor_lessons').select('id', { count: 'exact', head: true }).eq('kid_id', kid.id).gte('created_at', day);
  if ((count ?? 0) >= MAX_LESSONS_PER_DAY) return json({ error: 'daily_limit' }, 429);

  let pageText = '', media: any[] = [];
  if (qr) {
    if (!isSafePublicUrl(qr)) return json({ error: 'bad_qr' }, 400);
    if (!hostAllowed(qr, hosts)) return json({ error: 'host_not_allowed', host: new URL(qr).hostname }, 403);
    const page = await safeFetch(qr, { maxBytes: 1_500_000, accept: 'text/html,*/*' });
    if (!hostAllowed(page.finalUrl, hosts)) return json({ error: 'host_not_allowed', host: new URL(page.finalUrl).hostname }, 403);
    if (page.contentType.startsWith('video/')) media = [{ type: 'video', url: page.finalUrl }];
    else if (page.contentType.startsWith('audio/')) media = [{ type: 'audio', url: page.finalUrl }];
    else {
      const html = new TextDecoder().decode(page.bytes);
      pageText = html.replace(/<script[\s\S]*?<\/script>|<style[\s\S]*?<\/style>/gi, ' ').replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').slice(0, 6000);
      media = extractMedia(html, page.finalUrl).filter(m => m.type === 'page' || hostAllowed(m.url, hosts));
    }
  }

  let title = 'درس', sections: any[] = [];
  if (img || pageText) {
    const info = await extractLesson({ b64: img ?? undefined, mediaType, pageText: img ? undefined : pageText });
    if (!info.readable) return json({ status: 'retake', feedback: info.feedback || 'صفحه واضح نبود؛ دوباره عکس بگیر.' });
    title = info.title; sections = info.sections;
  } else if (media.length) {
    title = 'درس از QR';
  }
  const { data: row, error } = await db.from('tutor_lessons').insert({
    kid_id: kid.id, subject, title, sections, qr_url: qr || null, media, position: 0,
  }).select('*').single();
  if (error) return json({ error: 'db_error' }, 500);
  return json({ status: 'ok', lesson: publicLesson(row) });
}

async function list(db: any, kid: any, body: any) {
  const subject = subjectOf(body);
  let q = db.from('tutor_lessons').select('*').eq('kid_id', kid.id);
  if (subject) q = q.eq('subject', subject);
  const { data } = await q.order('updated_at', { ascending: false }).limit(20);
  return json({ lessons: (data ?? []).map(publicLesson) });
}

async function getLesson(db: any, kid: any, body: any) {
  const { data } = await db.from('tutor_lessons').select('*').eq('id', body.lesson_id).eq('kid_id', kid.id).maybeSingle();
  if (!data) return json({ error: 'not_found' }, 404);
  return json({ lesson: publicLesson(data) });
}

async function progress(db: any, kid: any, body: any) {
  const { data } = await db.from('tutor_lessons').select('*').eq('id', body.lesson_id).eq('kid_id', kid.id).maybeSingle();
  if (!data) return json({ error: 'not_found' }, 404);
  const pos = Number(body.position);
  const position = Number.isInteger(pos) && pos >= 0 && pos < Math.max(1, data.sections.length) ? pos : nextSection(data.sections);
  const done = data.sections.length > 0 && data.sections.every((s: any) => s.done);
  const { data: upd } = await db.from('tutor_lessons').update({ position, status: done ? 'done' : 'active', updated_at: new Date().toISOString() }).eq('id', data.id).select('*').single();
  return json({ lesson: publicLesson(upd) });
}

async function audioDictation(db: any, kid: any, body: any, day: string, hosts: string, st: any) {
  const { data: lesson } = await db.from('tutor_lessons').select('*').eq('id', body.lesson_id).eq('kid_id', kid.id).maybeSingle();
  if (!lesson) return json({ error: 'not_found' }, 404);
  const audios = (lesson.media ?? []).filter((m: any) => m.type === 'audio');
  const a = audios[Number(body.media_index) || 0];
  if (!a) return json({ error: 'no_audio' }, 404);
  if (!isSafePublicUrl(a.url) || !hostAllowed(a.url, hosts)) return json({ error: 'host_not_allowed' }, 403);

  const { count } = await db.from('dictations').select('id', { count: 'exact', head: true }).eq('kid_id', kid.id).eq('created_by', 'kid').eq('created_day', day);
  if ((count ?? 0) >= 5) return json({ error: 'daily_create_limit' }, 429);

  const f = await safeFetch(a.url, { maxBytes: MAX_AUDIO_BYTES, timeoutMs: 30_000, accept: 'audio/*' });
  const mime = AUDIO_TYPES[f.contentType];
  if (!mime) return json({ error: 'bad_audio_type' }, 415);
  const segs = await transcribeLessonAudio({ b64: toBase64(f.bytes), mime });
  if (segs.length < 2) return json({ status: 'retake', feedback: 'صوت درس خوانده نشد.' });

  const status = st?.dictation_requires_review === false ? 'ready' : 'review';
  const { data: row, error } = await db.from('dictations').insert({
    kid_id: kid.id, title: lesson.title.slice(0, 40), phrases: segs.map(s => s.text), status, points: st?.dictation_points ?? 10,
    created_by: 'kid', created_day: day, audio_url: a.url, segments: segs.map(s => ({ start: s.start, end: s.end })), lesson_id: lesson.id,
  }).select('id').single();
  if (error) return json({ error: 'db_error' }, 500);
  return json({ id: row.id, status, phrase_count: segs.length });
}

/** The kid scanned the book QR after the lesson already existed: add its video/audio to that lesson. */
async function attachQr(db: any, kid: any, body: any, hosts: string) {
  const { data: lesson } = await db.from('tutor_lessons').select('*').eq('id', body.lesson_id).eq('kid_id', kid.id).maybeSingle();
  if (!lesson) return json({ error: 'not_found' }, 404);
  const qr = typeof body.qr_url === 'string' ? body.qr_url.trim() : '';
  if (!isSafePublicUrl(qr)) return json({ error: 'bad_qr' }, 400);
  if (!hostAllowed(qr, hosts)) return json({ error: 'host_not_allowed', host: new URL(qr).hostname }, 403);
  const page = await safeFetch(qr, { maxBytes: 1_500_000, accept: 'text/html,*/*' });
  if (!hostAllowed(page.finalUrl, hosts)) return json({ error: 'host_not_allowed', host: new URL(page.finalUrl).hostname }, 403);
  let found: any[];
  if (page.contentType.startsWith('video/')) found = [{ type: 'video', url: page.finalUrl }];
  else if (page.contentType.startsWith('audio/')) found = [{ type: 'audio', url: page.finalUrl }];
  else found = extractMedia(new TextDecoder().decode(page.bytes), page.finalUrl).filter(m => m.type === 'page' || hostAllowed(m.url, hosts));
  const have = new Set((lesson.media ?? []).map((m: any) => m.url));
  const media = [...(lesson.media ?? []), ...found.filter(m => !have.has(m.url))].slice(0, 8);
  const { data: upd } = await db.from('tutor_lessons').update({ media, qr_url: qr, updated_at: new Date().toISOString() }).eq('id', lesson.id).select('*').single();
  return json({ lesson: publicLesson(upd), added: media.length - (lesson.media ?? []).length });
}
