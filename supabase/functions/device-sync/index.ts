import { admin, authDevice, cors, json } from '../_shared/common.ts';
import { bonusFor, dueTasks, isValidDay, lastDays, prevDay, weekPoints } from '../_shared/logic.ts';

// deno-lint-ignore-file no-explicit-any
Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') return new Response('ok', { headers: cors });
  if (req.method !== 'POST') return json({ error: 'method_not_allowed' }, 405);
  let body: any;
  try { body = await req.json(); } catch { return json({ error: 'bad_json' }, 400); }

  const db = admin();
  const device = await authDevice(db, body.token);
  if (!device) return json({ error: 'unauthorized' }, 401);
  const day = body.day;
  if (!isValidDay(day)) return json({ error: 'bad_day' }, 400);

  const { data: kids } = await db.from('kids').select('*').eq('active', true).order('name');
  const kidIds = new Set<string>((kids ?? []).map((k: any) => k.id));

  // 1) usage reported by the phone (absolute minutes for today, so retries are idempotent)
  const rows: any[] = [];
  for (const [kidId, pk] of Object.entries(body.usage ?? {})) {
    if (!kidIds.has(kidId)) continue;
    for (const [pkg, min] of Object.entries(pk as Record<string, number>)) {
      const m = Math.round(Number(min));
      if (pkg.length < 200 && m >= 0 && m <= 1440) rows.push({ kid_id: kidId, day, package_name: pkg, minutes: m });
    }
  }
  if (rows.length) await db.from('usage_logs').upsert(rows, { onConflict: 'kid_id,day,package_name' });

  // 2) installed apps (so the parent can choose which are allowed)
  const apps = (Array.isArray(body.apps) ? body.apps : []).slice(0, 1500)
    .filter((a: any) => typeof a?.package_name === 'string')
    .map((a: any) => ({ device_id: device.id, package_name: a.package_name, label: String(a.label ?? '').slice(0, 80) }));
  if (apps.length) await db.from('device_apps').upsert(apps, { onConflict: 'device_id,package_name' });
  await db.from('devices').update({ last_seen: new Date().toISOString() }).eq('id', device.id);

  // 3) make sure today's task rows exist
  const { data: tasks } = await db.from('tasks').select('*').eq('active', true);
  const onceIds = (tasks ?? []).filter((t: any) => t.kind === 'once').map((t: any) => t.id);
  const seen = new Set<string>();
  if (onceIds.length) {
    const { data: l } = await db.from('task_logs').select('task_id').in('task_id', onceIds);
    (l ?? []).forEach((r: any) => seen.add(r.task_id));
  }
  const due = dueTasks((tasks ?? []).filter((t: any) => kidIds.has(t.kid_id)), day, seen);
  if (due.length) {
    await db.from('task_logs').upsert(
      due.map((t: any) => ({ task_id: t.id, kid_id: t.kid_id, due_date: day })),
      { onConflict: 'task_id,due_date', ignoreDuplicates: true },
    );
  }

  // 4) yesterday's low-screen-time bonus (one per kid per day, enforced by a unique index)
  const y = prevDay(day);
  const { data: yu } = await db.from('usage_logs').select('kid_id,minutes').eq('day', y);
  for (const k of kids ?? []) {
    const mine = (yu ?? []).filter((r: any) => r.kid_id === k.id);
    const pts = bonusFor(k, mine.reduce((a: number, r: any) => a + r.minutes, 0), mine.length > 0);
    if (pts > 0) await db.from('points_ledger').insert({ kid_id: k.id, delta: pts, reason: 'screen_bonus', note: `bonus:${y}` }); // 23505 on repeat is fine
  }

  // 4b) automatic penalty: yesterday's unfinished tasks cost their penalty points, only for kids who turned it on.
  //      The unique index on points_ledger(kid_id, note) makes repeated syncs harmless.
  const { data: yLogs } = await db.from('task_logs').select('task_id,kid_id,status,tasks(penalty_points)').eq('due_date', y);
  for (const log of yLogs ?? []) {
    const k = (kids ?? []).find((x: any) => x.id === log.kid_id);
    if (!k || !(k as any).auto_penalty) continue;
    if (log.status !== 'pending' && log.status !== 'rejected') continue;
    const pts = (log as any).tasks?.penalty_points ?? 0;
    if (pts > 0) await db.from('points_ledger').insert({ kid_id: k.id, delta: -pts, reason: 'penalty', note: `auto:${y}:${log.task_id}` });
  }

  // penalties the kid should see: manual ones applied today, and yesterday's automatic one
  const { data: manualToday } = await db.from('penalty_logs').select('kid_id,points,penalty_rules(title)').eq('day', day);
  const { data: autoRows } = await db.from('points_ledger').select('kid_id,delta,note').eq('reason', 'penalty').like('note', `auto:${y}:%`);
  const titleOf: Record<string, string> = Object.fromEntries((tasks ?? []).map((t: any) => [t.id, t.title]));

  // 5) state for the phone
  const [{ data: logs }, { data: ledger }, { data: rules }, { data: wish }, { data: st }, { data: cmds }, { data: ut }, { data: dicts }] = await Promise.all([
    db.from('task_logs').select('id,kid_id,status,ai_score,ai_feedback,points_awarded,tasks(title,points,needs_photo,subject)').eq('due_date', day),
    db.from('points_ledger').select('kid_id,delta,created_at'),
    db.from('app_rules').select('*'),
    db.from('wishlist_progress').select('id,kid_id,title,image_url,price_toman,points_needed,points_left'),
    db.from('settings').select('*').single(),
    db.from('device_commands').select('*').eq('device_id', device.id).eq('done', false),
    db.from('usage_logs').select('kid_id,package_name,minutes').eq('day', day),
    db.from('dictations').select('id,kid_id,title,status,points,phrases,ai_score,ai_feedback,points_awarded,done_day,audio_url,segments'),
  ]);
  if (cmds?.length) await db.from('device_commands').update({ done: true }).in('id', cmds.map((c: any) => c.id));

  const bal: Record<string, number> = {};
  (ledger ?? []).forEach((r: any) => { bal[r.kid_id] = (bal[r.kid_id] ?? 0) + r.delta; });
  const usage: Record<string, Record<string, number>> = {};
  (ut ?? []).forEach((r: any) => { (usage[r.kid_id] ??= {})[r.package_name] = r.minutes; });

  const days = lastDays(day, 7);
  const screenMin = (kidId: string) => {
    const edu = new Set((rules ?? []).filter((r: any) => r.kid_id === kidId && r.category === 'edu').map((r: any) => r.package_name));
    return Object.entries(usage[kidId] ?? {}).filter(([pkg]) => !edu.has(pkg)).reduce((a, [, m]) => a + m, 0);
  };

  return json({
    day,
    week_days: days,
    toman_per_point: st?.toman_per_point ?? 1000,
    kids: (kids ?? []).map((k: any) => ({
      id: k.id, name: k.name, pin_hash: k.pin_hash, daily_limit_min: k.daily_limit_min,
      reminder_on: k.reminder_on ?? true, reminder_quiet: k.reminder_quiet ?? '23-10,14-18',
      balance: bal[k.id] ?? 0,
      week_points: weekPoints((ledger ?? []).filter((r: any) => r.kid_id === k.id), days),
      screen_min_today: screenMin(k.id),
      penalties: [
        ...(manualToday ?? []).filter((r: any) => r.kid_id === k.id).map((r: any) => ({ title: r.penalty_rules?.title ?? 'جریمه', points: r.points })),
        ...(autoRows ?? []).filter((r: any) => r.kid_id === k.id).map((r: any) => ({ title: 'کار ناتمام دیروز: ' + (titleOf[String(r.note).split(':')[2]] ?? ''), points: -r.delta })),
      ],
      tasks: (logs ?? []).filter((l: any) => l.kid_id === k.id).map((l: any) => ({
        log_id: l.id, title: l.tasks.title, points: l.tasks.points, needs_photo: l.tasks.needs_photo,
        status: l.status, ai_score: l.ai_score, ai_feedback: l.ai_feedback, points_awarded: l.points_awarded,
      })),
      wishlist: (wish ?? []).filter((w: any) => w.kid_id === k.id),
      rules: (rules ?? []).filter((r: any) => r.kid_id === k.id),
      dictations: (dicts ?? []).filter((d: any) => d.kid_id === k.id && (d.status === 'review' || d.status === 'ready' || d.done_day === day))
        .map((d: any) => ({ id: d.id, title: d.title, status: d.status, points: d.points, phrase_count: (d.phrases ?? []).length, ai_score: d.ai_score, ai_feedback: d.ai_feedback, points_awarded: d.points_awarded, audio_url: d.audio_url ?? null, segments: d.segments ?? null })),
    })),
    usage_today: usage,
    commands: (cmds ?? []).map((c: any) => ({ command: c.command, payload: c.payload })),
  });
});
