import { admin, authDevice, cors, json } from '../_shared/common.ts';
import { gradeDictation, gradeHomework } from '../_shared/ai.ts';
import { countDictationDoneToday, isValidDay, scaledPoints, shouldAutoApprove } from '../_shared/logic.ts';

// deno-lint-ignore-file no-explicit-any
const MEDIA = new Set(['image/jpeg', 'image/png', 'image/webp']);

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') return new Response('ok', { headers: cors });
  if (req.method !== 'POST') return json({ error: 'method_not_allowed' }, 405);
  let body: any;
  try { body = await req.json(); } catch { return json({ error: 'bad_json' }, 400); }

  const db = admin();
  if (!(await authDevice(db, body.token))) return json({ error: 'unauthorized' }, 401);

  if (body.dictation_id) return await submitDictation(db, body);

  const { data: log } = await db.from('task_logs').select('*, tasks(title,points,needs_photo,subject)').eq('id', body.log_id).maybeSingle();
  if (!log) return json({ error: 'not_found' }, 404);
  if (log.status === 'approved') return json({ error: 'already_approved' }, 409);
  const task = log.tasks;

  // Tasks without a photo: the kid says "done", the parent approves.
  if (!task.needs_photo) {
    await db.from('task_logs').update({ status: 'submitted' }).eq('id', log.id);
    return json({ status: 'submitted' });
  }

  const b64 = body.image_b64, mediaType = body.media_type ?? 'image/jpeg';
  if (typeof b64 !== 'string' || b64.length < 1000) return json({ error: 'image_required' }, 400);
  if (b64.length > 7_000_000) return json({ error: 'image_too_large' }, 413);
  if (!MEDIA.has(mediaType)) return json({ error: 'bad_media_type' }, 400);

  // Keep the photo for the parent (best effort: grading still works if storage fails)
  const path = `${log.kid_id}/${log.id}.jpg`;
  try {
    const bytes = Uint8Array.from(atob(b64), c => c.charCodeAt(0));
    const up = await db.storage.from('homework').upload(path, bytes, { contentType: mediaType, upsert: true });
    if (up.error) console.error('upload failed', up.error.message);
  } catch (e) { console.error('upload error', e); }

  let grade;
  try {
    grade = await gradeHomework({ title: task.title, subject: task.subject, b64, mediaType });
  } catch (e) {
    console.error('grading failed', e);
    // AI unavailable: hand it to the parent instead of failing the kid
    await db.from('task_logs').update({ status: 'submitted', photo_path: path }).eq('id', log.id);
    return json({ status: 'submitted', note: 'ai_unavailable' });
  }
  if (!grade.readable) return json({ status: 'retake', feedback: grade.feedback });

  const points = scaledPoints(task.points, grade.score);
  const { data: st } = await db.from('settings').select('auto_approve_score').single();
  const auto = shouldAutoApprove(grade.score, st?.auto_approve_score);
  const { error } = await db.from('task_logs').update({
    status: auto ? 'approved' : 'graded', ai_score: grade.score, ai_feedback: grade.feedback,
    points_awarded: points, photo_path: path,
  }).eq('id', log.id);
  if (error) return json({ error: 'db_error' }, 500);
  if (auto && points > 0) {
    await db.from('points_ledger').insert({ kid_id: log.kid_id, delta: points, reason: 'task', ref_id: log.id, note: task.title });
  }
  return json({ status: auto ? 'approved' : 'graded', score: grade.score, feedback: grade.feedback, points });
});

async function submitDictation(db: any, body: any): Promise<Response> {
  if (!isValidDay(body.day)) return json({ error: 'bad_day' }, 400);
  const { data: d } = await db.from('dictations').select('*').eq('id', body.dictation_id).maybeSingle();
  if (!d) return json({ error: 'not_found' }, 404);
  if (d.status === 'review') return json({ error: 'not_ready' }, 403);
  if (d.status !== 'ready') return json({ error: 'already_done' }, 409);
  const b64 = body.image_b64, mediaType = body.media_type ?? 'image/jpeg';
  if (typeof b64 !== 'string' || b64.length < 1000) return json({ error: 'image_required' }, 400);
  if (b64.length > 7_000_000) return json({ error: 'image_too_large' }, 413);
  if (!MEDIA.has(mediaType)) return json({ error: 'bad_media_type' }, 400);

  const [{ data: st }, { data: mine }] = await Promise.all([
    db.from('settings').select('auto_approve_score,dictation_daily_limit').single(),
    db.from('dictations').select('status,done_day').eq('kid_id', d.kid_id).eq('done_day', body.day),
  ]);
  if (countDictationDoneToday(mine ?? [], body.day) >= (st?.dictation_daily_limit ?? 2)) return json({ error: 'daily_limit' }, 429);

  const path = `${d.kid_id}/dict-${d.id}.jpg`;
  try {
    const up = await db.storage.from('homework').upload(path, Uint8Array.from(atob(b64), (c: string) => c.charCodeAt(0)), { contentType: mediaType, upsert: true });
    if (up.error) console.error('upload failed', up.error.message);
  } catch (e) { console.error('upload error', e); }

  let grade;
  try { grade = await gradeDictation({ phrases: d.phrases, b64, mediaType }); }
  catch (e) {
    console.error('dictation grading failed', e);
    await db.from('dictations').update({ status: 'graded', answer_photo_path: path, done_day: body.day }).eq('id', d.id);   // parent grades it
    return json({ status: 'submitted', note: 'ai_unavailable' });
  }
  if (!grade.readable) return json({ status: 'retake', feedback: grade.feedback });

  const points = scaledPoints(d.points, grade.score);
  const auto = shouldAutoApprove(grade.score, st?.auto_approve_score);
  const { error } = await db.from('dictations').update({
    status: auto ? 'done' : 'graded', ai_score: grade.score, ai_feedback: grade.feedback, points_awarded: points, answer_photo_path: path, done_day: body.day,
  }).eq('id', d.id);
  if (error) return json({ error: 'db_error' }, 500);
  if (auto && points > 0) await db.from('points_ledger').insert({ kid_id: d.kid_id, delta: points, reason: 'task', ref_id: d.id, note: 'دیکته: ' + d.title });
  return json({ status: auto ? 'approved' : 'graded', score: grade.score, feedback: grade.feedback, points });
}
