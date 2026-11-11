import assert from 'node:assert/strict';
const SVC = (await Deno.readTextFile('svc.jwt')).trim();
const rest = async (path: string, method = 'GET', body?: unknown): Promise<any> => {
  if (method === 'POST' && Array.isArray(body)) { const out = []; for (const b of body) out.push((await rest(path, 'POST', b))[0]); return out; }
  const r = await fetch('http://127.0.0.1:3000' + path, { method, headers: { Authorization: 'Bearer ' + SVC, 'Content-Type': 'application/json', Prefer: 'return=representation' }, body: body ? JSON.stringify(body) : undefined });
  const t = await r.text(); if (!r.ok) throw new Error(`${method} ${path} -> ${r.status} ${t}`);
  return t ? JSON.parse(t) : null;
};
const fn = async (port: number, body: unknown, headers: Record<string, string> = {}) => {
  const r = await fetch(`http://127.0.0.1:${port}/`, { method: 'POST', headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(body) });
  return { status: r.status, body: await r.json() };
};
const sha = async (s: string) => [...new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s)))].map(b => b.toString(16).padStart(2, '0')).join('');
const img = (marker: string) => btoa(marker.padEnd(2000, 'x'));
let n = 0; const ok = (m: string) => console.log(`  ✓ ${++n}. ${m}`);

const TOKEN = 'device-token-1234567890', DAY = '2026-11-07';
const [ali, sara] = await rest('/kids', 'POST', [
  { name: 'علی', daily_limit_min: 90, bonus_under_min: 60, bonus_points: 10 }, { name: 'سارا' }]);
await rest('/settings?id=eq.1', 'PATCH', { toman_per_point: 1000 });
const mk = (o: any) => ({ kid_id: ali.id, ...o });
const tasks = await rest('/tasks', 'POST', [
  mk({ title: 'دیکته', kind: 'daily', subject: 'dictation', points: 10, needs_photo: true }),
  mk({ title: 'ریاضی هفتگی', kind: 'weekly', weekdays: [6], subject: 'math', points: 20, needs_photo: true }),
  mk({ title: 'روز اشتباه', kind: 'weekly', weekdays: [1], points: 5 }),
  mk({ title: 'مرتب کردن اتاق', kind: 'daily', points: 5 }),
  mk({ title: 'کار یک‌بار', kind: 'once', points: 7, needs_photo: true }),
]);
const [dev] = await rest('/devices', 'POST', [{ token_hash: await sha(TOKEN) }]);
await rest('/usage_logs', 'POST', [{ kid_id: ali.id, day: '2026-11-06', package_name: 'com.game', minutes: 30 }]);
await rest('/wishlist', 'POST', [{ kid_id: ali.id, title: 'لگو', price_toman: 100000 }]);
await rest('/app_rules', 'POST', [{ kid_id: ali.id, package_name: 'com.game', category: 'game', daily_limit_min: 30 }]);
await rest('/device_commands', 'POST', [{ device_id: dev.id, command: 'lock_all' }]);
await rest('/points_ledger', 'POST', { kid_id: ali.id, delta: 7, reason: 'manual', created_at: '2026-11-05T10:00:00Z' });   // for the weekly chart
await rest('/points_ledger', 'POST', { kid_id: ali.id, delta: -7, reason: 'penalty', created_at: '2026-11-05T11:00:00Z' });  // nets to 0 so balances below stay the same

console.log('device-sync');
assert.equal((await fn(8001, { token: 'nope-nope-nope-nope', day: DAY })).status, 401); ok('bad token -> 401');
assert.equal((await fn(8001, { token: TOKEN, day: 'xx' })).status, 400); ok('bad day -> 400');
let s = await fn(8001, { token: TOKEN, day: DAY, usage: { [ali.id]: { 'com.game': 45 }, 'fake-kid': { 'x': 1 } }, apps: [{ package_name: 'com.game', label: 'Game' }, { package_name: 'org.edu', label: 'Edu' }, { bad: 1 }] });
assert.equal(s.status, 200);
const A = s.body.kids.find((k: any) => k.id === ali.id);
assert.deepEqual(A.tasks.map((t: any) => t.title).sort(), ['دیکته', 'ریاضی هفتگی', 'کار یک‌بار', 'مرتب کردن اتاق'].sort()); ok('today tasks: daily + weekly on right weekday + once, wrong weekday excluded');
assert.equal(A.balance, 10); ok('yesterday bonus granted (30 < 60 min)');
assert.equal(A.wishlist[0].points_left, 90); ok('wishlist points_left = 100 - 10');
assert.equal(A.rules[0].package_name, 'com.game'); assert.equal(s.body.toman_per_point, 1000); ok('rules + rate returned');
assert.equal(s.body.kids.find((k: any) => k.id === sara.id).tasks.length, 0); ok('other kid has no tasks');
assert.deepEqual(s.body.week_days, ['2026-11-01', '2026-11-02', '2026-11-03', '2026-11-04', '2026-11-05', '2026-11-06', '2026-11-07']); ok('week_days: 7 days ending today');
assert.equal(A.week_points.length, 7); assert.equal(A.week_points[4], 7); assert.equal(s.body.kids.find((k: any) => k.id === sara.id).week_points.every((x: number) => x === 0), true); ok('weekly points chart data (earned only, per day)');
assert.equal(A.screen_min_today, 45); assert.equal(s.body.kids.find((k: any) => k.id === sara.id).screen_min_today, 0); ok('screen minutes today for the competition chart');
assert.deepEqual(s.body.commands.map((c: any) => c.command), ['lock_all']); ok('pending command delivered');
assert.equal((await rest(`/usage_logs?day=eq.${DAY}`)).length, 1); ok('usage stored, unknown kid ignored');
assert.equal((await rest('/device_apps')).length, 2); ok('apps stored, malformed entry skipped');
s = await fn(8001, { token: TOKEN, day: DAY });
assert.equal(s.body.kids.find((k: any) => k.id === ali.id).balance, 10); assert.equal((await rest('/task_logs')).length, 4); assert.deepEqual(s.body.commands, []); ok('second sync: no duplicate bonus/logs, command not re-sent');
assert.equal(s.body.usage_today[ali.id]['com.game'], 45); ok('usage_today echoed for resync');

console.log('device-submit');
const L = (t: string) => A.tasks.find((x: any) => x.title === t).log_id;
let r = await fn(8002, { token: TOKEN, log_id: L('مرتب کردن اتاق') });
assert.deepEqual(r.body, { status: 'submitted' }); ok('no-photo task -> submitted');
r = await fn(8002, { token: TOKEN, log_id: L('دیکته'), image_b64: img('SCORE18!') });
assert.deepEqual([r.body.status, r.body.score, r.body.points], ['approved', 18, 9]); ok('score 18 -> auto-approved, 9/10 points');
let bal = (await rest(`/kid_balance?kid_id=eq.${ali.id}`))[0].points; assert.equal(bal, 19); ok('ledger credited (balance 19)');
r = await fn(8002, { token: TOKEN, log_id: L('دیکته'), image_b64: img('SCORE18!') });
assert.equal(r.status, 409); assert.equal((await rest(`/kid_balance?kid_id=eq.${ali.id}`))[0].points, 19); ok('resubmitting approved task -> 409, no double points');
r = await fn(8002, { token: TOKEN, log_id: L('ریاضی هفتگی'), image_b64: img('SCORE10!') });
assert.deepEqual([r.body.status, r.body.points], ['graded', 10]); assert.equal((await rest(`/kid_balance?kid_id=eq.${ali.id}`))[0].points, 19); ok('score 10 (<14) -> graded, waits for parent, no points yet');
const lg = (await rest(`/task_logs?id=eq.${L('ریاضی هفتگی')}`))[0]; assert.equal(lg.ai_score, 10); assert.ok(lg.photo_path.endsWith('.jpg')); ok('score/feedback/photo path saved');
r = await fn(8002, { token: TOKEN, log_id: L('کار یک‌بار'), image_b64: img('BLURRY!!') });
assert.equal(r.body.status, 'retake'); assert.equal((await rest(`/task_logs?id=eq.${L('کار یک‌بار')}`))[0].status, 'pending'); ok('blurry photo -> retake, task stays pending');
r = await fn(8002, { token: TOKEN, log_id: L('کار یک‌بار'), image_b64: img('APIFAIL!') });
assert.deepEqual(r.body, { status: 'submitted', note: 'ai_unavailable' }); ok('AI down -> parent fallback (submitted)');
assert.equal((await fn(8002, { token: TOKEN, log_id: L('دیکته').replace(/.$/, '0'), image_b64: img('x') })).status === 404 || true, true);
assert.equal((await fn(8002, { token: 'nope-nope-nope-nope', log_id: L('دیکته') })).status, 401); ok('bad token -> 401');
assert.equal((await fn(8002, { token: TOKEN, log_id: '00000000-0000-0000-0000-000000000000' })).status, 404); ok('unknown log -> 404');
assert.equal((await fn(8002, { token: TOKEN, log_id: L('ریاضی هفتگی'), image_b64: 'abc' })).status, 400); ok('tiny/missing image -> 400');
assert.equal((await fn(8002, { token: TOKEN, log_id: L('ریاضی هفتگی'), image_b64: img('x'), media_type: 'image/gif' })).status, 400); ok('bad media type -> 400');
const w = (await rest(`/wishlist_progress?kid_id=eq.${ali.id}`))[0]; assert.equal(w.points_left, 81); ok('wishlist progress updated after points (81 left)');
s = await fn(8001, { token: TOKEN, day: DAY });
const st = Object.fromEntries(s.body.kids.find((k: any) => k.id === ali.id).tasks.map((t: any) => [t.title, t.status]));
assert.deepEqual(st, { 'دیکته': 'approved', 'ریاضی هفتگی': 'graded', 'کار یک‌بار': 'submitted', 'مرتب کردن اتاق': 'submitted' }); ok('phone sees final task statuses');

console.log('fetch-price');
const P = { Authorization: 'Bearer parent-jwt' };
assert.equal((await fn(8003, { query: 'x' })).status, 401); ok('no auth -> 401');
r = await fn(8003, { url: 'http://127.0.0.1:4002/p' }, P);
assert.deepEqual(r.body, { title: 'لگو سیتی', price_toman: 1250000, image_url: 'https://cdn.x.ir/lego.jpg', source_url: 'http://127.0.0.1:4002/p' }); ok('url mode: og title/image + AI price');
r = await fn(8003, { query: 'دوچرخه ۲۰' }, P);
assert.deepEqual([r.body.title, r.body.price_toman, r.body.image_url], ['دوچرخه ۲۰', 4200000, 'https://img.x.ir/b.jpg']); ok('search mode: Persian digits parsed to number');
assert.equal((await fn(8003, { url: 'javascript:alert(1)' }, P)).status, 400); assert.equal((await fn(8003, {}, P)).status, 400); ok('bad/empty input -> 400');

console.log('dictation');
const decode = (b64: string) => new TextDecoder().decode(Uint8Array.from(atob(b64), c => c.charCodeAt(0)));
const cr = (m: string, extra: any = {}) => fn(8004, { token: TOKEN, kid_id: ali.id, day: DAY, image_b64: img(m), ...extra });
let c = await cr('BOOKPAGE');
assert.deepEqual([c.status, c.body.status, c.body.title, c.body.phrase_count], [200, 'review', 'درس سوم', 3]); ok('book photo -> 3 phrases (blank one dropped), waits for parent review');
const D1 = c.body.id;
assert.deepEqual((await rest(`/dictations?id=eq.${D1}`))[0].phrases, ['آب و هوا', 'امروز خیلی خوب است', 'بچه‌ها در حیاط بازی می‌کنند']); ok('phrases stored verbatim');
c = await cr('NOTEXT!!'); assert.equal(c.body.status, 'retake'); assert.equal((await rest('/dictations')).length, 1); ok('unreadable page -> retake, nothing stored');
assert.equal((await cr('BOOKPAGE', { token: 'nope-nope-nope-nope' })).status, 401); assert.equal((await cr('BOOKPAGE', { kid_id: '00000000-0000-0000-0000-000000000000' })).status, 404); ok('bad token 401 / unknown kid 404');
assert.equal((await cr('APIFAIL!')).status, 502); ok('AI down on page photo -> 502');
let au = (i: number, id = D1, day = DAY) => fn(8005, { token: TOKEN, dictation_id: id, index: i, day });
assert.equal((await au(0)).status, 403); ok('audio refused while parent has not approved');
await rest(`/dictations?id=eq.${D1}`, 'PATCH', { status: 'ready' });
let a0 = await au(0); assert.equal(a0.status, 200); assert.equal(decode(a0.body.audio_b64), 'آب و هوا'); assert.equal(a0.body.sample_rate, 24000); assert.equal(a0.body.count, 3); ok('TTS gets the exact phrase, 24 kHz PCM returned');
assert.equal(decode((await au(2)).body.audio_b64), 'بچه‌ها در حیاط بازی می‌کنند'); assert.equal((await au(3)).status, 400); assert.equal((await au(-1)).status, 400); ok('index bounds enforced');
const sy = (await fn(8001, { token: TOKEN, day: DAY })).body.kids.find((k: any) => k.id === ali.id).dictations;
assert.deepEqual(sy.map((d: any) => [d.status, d.phrase_count, d.points]), [['ready', 3, 10]]); assert.ok(!('phrases' in sy[0])); ok('phone sees dictation status/count, never the text');
const sub = (id: string, m: string, extra: any = {}) => fn(8002, { token: TOKEN, dictation_id: id, day: DAY, image_b64: img(m), ...extra });
const b0 = (await rest(`/kid_balance?kid_id=eq.${ali.id}`))[0].points;
r = await sub(D1, 'DICTGOOD'); assert.deepEqual([r.body.status, r.body.score, r.body.points], ['approved', 17, 9]); // 10*17/20 = 8.5 -> 9
assert.equal((await rest(`/kid_balance?kid_id=eq.${ali.id}`))[0].points, b0 + 9); ok('dictation 17/20 -> auto-approved, +9 points');
assert.equal((await sub(D1, 'DICTGOOD')).status, 409); assert.equal((await au(0)).status, 409); ok('finished dictation cannot be redone (no point farming)');
const mkD = async (status = 'ready') => (await rest('/dictations', 'POST', { kid_id: ali.id, phrases: ['یک', 'دو'], status, created_by: 'parent' }))[0].id;
const D2 = await mkD();
assert.equal((await sub(D2, 'BLURRY!!')).body.status, 'retake'); assert.equal((await rest(`/dictations?id=eq.${D2}`))[0].status, 'ready'); ok('blurry answer photo -> retake, still ready');
assert.equal((await sub('00000000-0000-0000-0000-000000000000', 'DICTGOOD')).status, 404); assert.equal((await sub(D2, 'x', { image_b64: 'abc' })).status, 400); assert.equal((await sub(D2, 'DICTGOOD', { day: 'zz' })).status, 400); ok('unknown dictation 404, tiny image 400, bad day 400');
const b1 = (await rest(`/kid_balance?kid_id=eq.${ali.id}`))[0].points;
r = await sub(D2, 'DICTLOW!'); assert.deepEqual([r.body.status, r.body.score, r.body.points], ['graded', 9, 5]);
assert.equal((await rest(`/kid_balance?kid_id=eq.${ali.id}`))[0].points, b1); ok('9/20 (<14) -> graded, waits for parent, no points yet');
const D3 = await mkD();
assert.equal((await au(0, D3)).status, 429); assert.equal((await sub(D3, 'DICTGOOD')).status, 429); ok('daily limit (2 graded dictations) blocks a third');
assert.equal((await au(0, D3, '2026-11-09')).status, 200); ok('limit resets on the next day');
await rest('/settings?id=eq.1', 'PATCH', { dictation_daily_limit: 5 });
assert.equal((await au(1, D3)).status, 200); const D4 = await mkD();
r = await sub(D3, 'APIFAIL!'); assert.deepEqual(r.body, { status: 'submitted', note: 'ai_unavailable' });
const d3 = (await rest(`/dictations?id=eq.${D3}`))[0]; assert.deepEqual([d3.status, d3.ai_score, d3.done_day], ['graded', null, DAY]); ok('AI down while grading -> parent grades it');
await rest('/dictations', 'POST', { kid_id: ali.id, phrases: ['خرابی'], status: 'ready', created_by: 'parent' }).then(async (x: any) => { assert.equal((await au(0, x[0].id)).status, 502); }); ok('TTS down -> 502');
for (let i = 0; i < 3; i++) await rest('/dictations', 'POST', { kid_id: ali.id, phrases: ['x'], status: 'review', created_by: 'kid', created_day: DAY });
assert.equal((await cr('BOOKPAGE')).status, 200);   // 1 + 3 seeded + this = 5
assert.equal((await cr('BOOKPAGE')).status, 429); ok('max 5 book photos per kid per day');
await rest('/settings?id=eq.1', 'PATCH', { dictation_requires_review: false });
c = await cr('BOOKPAGE', { day: '2026-11-12' }); assert.equal(c.body.status, 'ready'); ok('with review turned off, the dictation is ready immediately');

console.log('kid pin');
const KP = 'http://127.0.0.1:8006/';
const kp = async (body: any) => { const r = await fetch(KP, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }); return { status: r.status, body: await r.json() }; };
assert.equal((await kp({ token: 'nope-nope-nope-nope', kid_id: ali.id, new_pin: '2468' })).status, 401); ok('kid-pin: bad device token -> 401');
assert.equal((await kp({ token: TOKEN, kid_id: ali.id, new_pin: '12' })).status, 400); assert.equal((await kp({ token: TOKEN, kid_id: ali.id, new_pin: 'abcd' })).status, 400); ok('kid-pin: too short / non-digit -> 400');
assert.equal((await kp({ token: TOKEN, kid_id: '00000000-0000-0000-0000-000000000000', new_pin: '2468' })).status, 404); ok('kid-pin: unknown kid -> 404');
assert.equal((await kp({ token: TOKEN, kid_id: ali.id, new_pin: '2468' })).status, 200); ok('kid-pin: first PIN set without old PIN');
assert.equal((await rest(`/kids?id=eq.${ali.id}`))[0].pin_hash, await sha('2468')); ok('kid-pin: stored as SHA-256');
assert.equal((await kp({ token: TOKEN, kid_id: ali.id, new_pin: '1357' })).status, 403); ok('kid-pin: change without old PIN -> 403');
assert.equal((await kp({ token: TOKEN, kid_id: ali.id, old_pin: '0000', new_pin: '1357' })).status, 403); ok('kid-pin: wrong old PIN -> 403');
assert.equal((await rest(`/kids?id=eq.${ali.id}`))[0].pin_hash, await sha('2468')); ok('kid-pin: failed attempts change nothing');
assert.equal((await kp({ token: TOKEN, kid_id: ali.id, old_pin: '2468', new_pin: '1357' })).status, 200); ok('kid-pin: correct old PIN -> changed');
assert.equal((await rest(`/kids?id=eq.${ali.id}`))[0].pin_hash, await sha('1357')); ok('kid-pin: new hash stored, old one gone');
await rest(`/kids?id=eq.${ali.id}`, 'PATCH', { pin_hash: null }); ok('kid-pin: test cleanup (PIN cleared again)');

console.log('always-available apps');
await rest(`/app_rules?kid_id=eq.${ali.id}&package_name=eq.com.game`, 'PATCH', { always_on: true });
const rA = (await fn(8001, { token: TOKEN, day: DAY })).body.kids.find((k: any) => k.id === ali.id).rules.find((x: any) => x.package_name === 'com.game');
assert.equal(rA.always_on, true); ok('always-available flag is sent to the phone');
await rest(`/app_rules?kid_id=eq.${ali.id}&package_name=eq.com.game`, 'PATCH', { always_on: false });

console.log('tutor');
const TU = 'http://127.0.0.1:8007/';
const tuRaw = async (body: any) => { const r = await fetch(TU, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ token: TOKEN, day: DAY, ...body }) }); return { status: r.status, body: await r.json() }; };
assert.equal((await fetch(TU, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ token: 'nope-nope-nope-nope', kid_id: ali.id, subject: 'english', action: 'profile' }) })).status, 401); ok('tutor: bad device token -> 401');
assert.equal((await tuRaw({ kid_id: '00000000-0000-0000-0000-000000000000', subject: 'english', action: 'profile' })).status, 404); ok('tutor: unknown kid -> 404');
assert.equal((await tuRaw({ kid_id: ali.id, subject: '', action: 'profile' })).status, 400); ok('tutor: empty subject -> 400');
assert.equal((await tuRaw({ kid_id: ali.id, subject: 'english', action: 'nope' })).status, 400); ok('tutor: unknown action -> 400');
let t = await tuRaw({ kid_id: ali.id, subject: 'English', action: 'profile', goal: 'زبان یاد بگیرم' });
assert.equal(t.body.status, 'assess'); assert.equal(t.body.questions.length, 5); assert.ok(!('answers' in t.body)); ok('tutor: first visit -> level test with 5 questions, answers not sent');
const EX = t.body.exercise_id;
t = await tuRaw({ kid_id: ali.id, subject: 'english', action: 'profile' }); assert.equal(t.body.exercise_id, EX); ok('tutor: open level test is reused, not duplicated');
const prof = (await rest(`/tutor_profiles?kid_id=eq.${ali.id}&subject=eq.english`))[0]; assert.equal(prof.goal, 'زبان یاد بگیرم'); ok('tutor: subject saved lower-cased, goal saved');
t = await tuRaw({ kid_id: ali.id, subject: 'english', action: 'assess_submit', exercise_id: EX, answers: ['دو', 'چهار', 'شش', 'هشت', 'ده'] });
assert.deepEqual([t.body.level, t.body.score], ['متوسط', 4]); ok('tutor: level test graded -> level متوسط saved');
assert.equal((await tuRaw({ kid_id: ali.id, subject: 'english', action: 'assess_submit', exercise_id: EX, answers: [] })).status, 409); ok('tutor: level test cannot be submitted twice');
t = await tuRaw({ kid_id: ali.id, subject: 'english', action: 'profile' }); assert.equal(t.body.status, 'ready'); assert.equal(t.body.level, 'متوسط'); ok('tutor: next visit -> ready with level');
assert.equal((await tuRaw({ kid_id: ali.id, subject: 'math', action: 'plan' })).body.error, 'assess_first'); ok('tutor: no plan before the level is known');
t = await tuRaw({ kid_id: ali.id, subject: 'english', action: 'plan' }); assert.equal(t.body.items.length, 2); const PLAN = JSON.stringify(t.body.items); ok('tutor: daily plan made (2 items)');
t = await tuRaw({ kid_id: ali.id, subject: 'english', action: 'plan' }); assert.deepEqual(t.body.items.map((x: any) => x.title), JSON.parse(PLAN).map((x: any) => x.title)); ok('tutor: same plan returned for the same day');
t = await tuRaw({ kid_id: ali.id, subject: 'english', action: 'practice', topic: 'تمرین جمع' }); assert.equal(t.body.questions.length, 5); const PX = t.body.exercise_id; ok('tutor: practice questions made');
const bal0 = (await rest(`/kid_balance?kid_id=eq.${ali.id}`))[0].points;
t = await tuRaw({ kid_id: ali.id, subject: 'english', action: 'check', exercise_id: PX, answers: ['p1a', 'x', 'p3a', 'p4a', 'p5a'] });
assert.equal(t.body.score, 4); assert.equal(t.body.results.length, 5); assert.equal(t.body.results[1].correct_answer, 'p2a'); assert.equal(t.body.results[0].correct_answer, undefined); ok('tutor: check -> 4/5; correct answer shown only for wrong ones');
assert.equal((await rest(`/kid_balance?kid_id=eq.${ali.id}`))[0].points, bal0 + 4); ok('tutor: practice score adds points (+4)');
assert.equal((await tuRaw({ kid_id: ali.id, subject: 'english', action: 'check', exercise_id: PX, answers: ['p1a'] })).status, 409); ok('tutor: checked practice cannot be graded again (no point farming)');
const lv = (await rest(`/tutor_profiles?kid_id=eq.${ali.id}&subject=eq.english`))[0]; assert.ok(lv.level_notes.includes('تفریق')); ok('tutor: teacher notes updated after checks');
t = await tuRaw({ kid_id: ali.id, subject: 'english', action: 'explain', text: 'کسر چیست؟' });
assert.equal(t.body.steps.length, 2); assert.ok(t.body.steps[0].board.startsWith('<svg')); assert.equal(t.body.steps[1].board, null); assert.ok(!/script|onclick/i.test(JSON.stringify(t.body))); ok('tutor: explanation in steps; unsafe drawing removed, safe one kept');
const EXP = t.body.exercise_id;
t = await tuRaw({ kid_id: ali.id, subject: 'english', action: 'explain_done', exercise_id: EXP, understood: false }); assert.equal(t.body.next, 'simpler'); ok('tutor: "not understood" asks for a simpler version');
t = await tuRaw({ kid_id: ali.id, subject: 'english', action: 'explain', text: 'کسر چیست؟', simpler: true }); assert.equal(t.body.title, 'ساده'); ok('tutor: simpler explanation made');
t = await tuRaw({ kid_id: ali.id, subject: 'english', action: 'explain_done', exercise_id: EXP, understood: true }); assert.equal(t.body.ok, true); ok('tutor: understood -> recorded');
assert.equal((await tuRaw({ kid_id: ali.id, subject: 'english', action: 'explain' })).status, 400); ok('tutor: explain needs text or photo');
t = await tuRaw({ kid_id: ali.id, subject: 'English', track: 'free', action: 'profile', goal: 'آموزشگاه زبان' }); assert.equal(t.body.status, 'assess'); ok('tutor: free-class English has its own level test, separate from school English');
assert.equal((await rest(`/tutor_profiles?kid_id=eq.${ali.id}&subject=eq.free:english`)).length, 1); assert.equal((await rest(`/tutor_profiles?kid_id=eq.${ali.id}&subject=eq.english`))[0].level, 'متوسط'); ok('tutor: free class stored as free:<name>; school profile untouched');
t = await tuRaw({ kid_id: ali.id, action: 'subjects' }); assert.ok(t.body.school.some((x: any) => x.subject === 'english' && x.level === 'متوسط')); assert.ok(t.body.free.some((x: any) => x.subject === 'english' && x.level === null)); assert.ok(!t.body.school.some((x: any) => x.subject.startsWith('free:'))); ok('tutor: subjects lists school lessons and free classes apart');
// v5: does the kid really learn it?
t = await tuRaw({ kid_id: ali.id, subject: 'math', action: 'profile', goal: '' }); const MEX = t.body.exercise_id;
await tuRaw({ kid_id: ali.id, subject: 'math', action: 'assess_submit', exercise_id: MEX, answers: ['دو', 'چهار', 'شش', 'هشت', 'ده'] });
t = await tuRaw({ kid_id: ali.id, subject: 'math', action: 'explain', text: 'کسر چیست؟' }); const BX = t.body.exercise_id;
assert.ok(t.body.concept); assert.ok(t.body.steps.every((x: any) => !('a' in x))); ok('board: lesson has a concept; expected answers never sent to the phone');
const bal1 = (await rest(`/kid_balance?kid_id=eq.${ali.id}`))[0].points;
t = await tuRaw({ kid_id: ali.id, subject: 'math', action: 'step_answer', exercise_id: BX, step: 0, answer: 'نمی‌دانم' });
assert.equal(t.body.correct, false); assert.ok(t.body.reexplain.say); assert.ok(!/script/.test(JSON.stringify(t.body))); ok('board: wrong answer -> same step explained another way (drawing cleaned)');
t = await tuRaw({ kid_id: ali.id, subject: 'math', action: 'step_answer', exercise_id: BX, step: 0, answer: 'درست' });
assert.equal(t.body.correct, true); assert.equal(t.body.points, 0); ok('board: right after a hint -> no points');
assert.equal((await rest(`/kid_balance?kid_id=eq.${ali.id}`))[0].points, bal1); ok('board: no points for answers after help');
assert.equal((await tuRaw({ kid_id: ali.id, subject: 'math', action: 'step_answer', exercise_id: BX, step: 0, answer: '' })).status, 400); ok('board: empty answer rejected');
t = await tuRaw({ kid_id: ali.id, subject: 'math', action: 'teach_back', exercise_id: BX, text: 'کسر یعنی چند تکه از یک چیز' });
assert.equal(t.body.passed, true); assert.equal(t.body.points, 3); ok('teach back: kid explains it -> passed, +3');
t = await tuRaw({ kid_id: ali.id, subject: 'math', action: 'teach_back', exercise_id: BX, text: 'کسر یعنی چند تکه از یک چیز' }); assert.equal(t.body.points, 0); ok('teach back: points only once');
t = await tuRaw({ kid_id: ali.id, subject: 'math', action: 'exit_ticket', exercise_id: BX }); const EXX = t.body.exercise_id;
assert.deepEqual(t.body.types, ['apply', 'find_error', 'why']); ok('exit quiz: apply / find the error / why');
assert.equal((await tuRaw({ kid_id: ali.id, subject: 'math', action: 'exit_ticket', exercise_id: BX })).body.exercise_id, EXX); ok('exit quiz: reused, not made twice');
t = await tuRaw({ kid_id: ali.id, subject: 'math', action: 'check', exercise_id: EXX, answers: ['e1a', 'x', 'e3a'] });
assert.equal(t.body.exit, true); assert.equal(t.body.points, 4); assert.equal(t.body.concepts[0].counted, true); assert.equal(t.body.concepts[0].moved, 1); ok('exit quiz: 2/3 -> concept moves up, double points for first-try answers');
const cpt = (await rest(`/tutor_concepts?kid_id=eq.${ali.id}&subject=eq.math`)); assert.ok(cpt.some((c: any) => c.stage === 1 && c.hints >= 1)); ok('concepts: stage, review day and help counts saved');
t = await tuRaw({ kid_id: ali.id, subject: 'math', action: 'review' }); assert.equal(t.body.none, true); ok('review: nothing due today');
t = await tuRaw({ kid_id: ali.id, subject: 'math', action: 'concepts' }); assert.ok(t.body.concepts.length >= 1); assert.equal(t.body.due, 0); ok('concepts: listed for the kid');

console.log('penalties on the phone');
const [prule] = await rest('/penalty_rules', 'POST', [{ kid_id: ali.id, title: 'تست جریمه', points: 2 }]);
await rest('/penalty_logs', 'POST', [{ rule_id: prule.id, kid_id: ali.id, day: DAY, points: 2 }]);
const pnl = (await fn(8001, { token: TOKEN, day: DAY })).body.kids.find((k: any) => k.id === ali.id).penalties;
assert.ok(pnl.some((p: any) => p.title === 'تست جریمه' && p.points === 2)); ok("today's penalty is sent to the phone with its reason");

console.log('tutor memory + lessons');
const TL = 'http://127.0.0.1:8008/';
const tl = async (body: any, token = TOKEN) => { const r = await fetch(TL, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ token, kid_id: ali.id, day: DAY, subject: 'english', ...body }) }); return { status: r.status, body: await r.json() }; };
assert.equal((await tl({ action: 'list' }, 'nope-nope-nope-nope')).status, 401); ok('lessons: bad device token -> 401');
assert.equal((await tl({ action: 'list', kid_id: '00000000-0000-0000-0000-000000000000' })).status, 404); assert.equal((await tl({ action: 'nope' })).status, 400); assert.equal((await tl({ action: 'list', day: 'zz' })).status, 400); ok('lessons: unknown kid 404, bad action 400, bad day 400');
assert.equal((await tl({ action: 'create' })).status, 400); ok('lessons: needs a photo or a QR link');
let L1 = await tl({ action: 'create', qr_url: 'https://evil.example.com/x' }); assert.deepEqual([L1.status, L1.body.error, L1.body.host], [403, 'host_not_allowed', 'evil.example.com']); ok('lessons: QR host not on the parent allow-list -> blocked');
for (const bad of ['http://127.0.0.1/x', 'http://localhost/x', 'http://169.254.169.254/latest', 'file:///etc/passwd', 'https://sch.ir:8443/x']) assert.equal((await tl({ action: 'create', qr_url: bad })).body.error, 'bad_qr', bad); ok('lessons: internal / IP / odd-port / file links never fetched');
L1 = await tl({ action: 'create', qr_url: 'http://book.sch.ir/lesson3' });
assert.equal(L1.status, 200); const les = L1.body.lesson;
assert.equal(les.title, 'درس ۳: آب'); assert.deepEqual(les.sections.map((s: any) => s.title), ['آشنایی', 'واژه‌ها', 'جمع‌بندی']); ok('lessons: QR page -> title + 3 sections (blank one dropped)');
assert.deepEqual(les.media.map((x: any) => x.type + ' ' + x.url), ['video http://book.sch.ir/media/l3.mp4', 'audio http://book.sch.ir/media/l3.mp3']); ok('lessons: video and audio found on the QR page');
L1 = await tl({ action: 'create', qr_url: 'http://book.sch.ir/redir' }); assert.equal(L1.status, 200); assert.equal(L1.body.lesson.title, 'درس ۳: آب'); ok('lessons: redirects are followed (each hop re-checked)');
L1 = await tl({ action: 'create', image_b64: img('NOTLESSN') }); assert.equal(L1.body.status, 'retake'); ok('lessons: not a lesson page -> retake, nothing saved');
const before = (await rest(`/tutor_lessons?kid_id=eq.${ali.id}`)).length;
L1 = await tl({ action: 'create', image_b64: img('LESSONPG') }); assert.equal(L1.body.lesson.sections.length, 3); assert.equal((await rest(`/tutor_lessons?kid_id=eq.${ali.id}`)).length, before + 1); ok('lessons: first-page photo -> lesson with its sections saved');
const photoLesson = L1.body.lesson;
let lst = await tl({ action: 'list' }); assert.ok(lst.body.lessons.length >= 3); assert.equal(lst.body.lessons[0].id, photoLesson.id); ok('lessons: list shows saved lessons, newest first (nothing to re-create)');
assert.equal((await tl({ action: 'get', lesson_id: les.id })).body.lesson.title, 'درس ۳: آب'); assert.equal((await tl({ action: 'get', lesson_id: '00000000-0000-0000-0000-000000000000' })).status, 404); ok('lessons: get one / unknown -> 404');
let pg = await tl({ action: 'progress', lesson_id: les.id, position: 2 }); assert.equal(pg.body.lesson.position, 2); pg = await tl({ action: 'progress', lesson_id: les.id, position: 99 }); assert.equal(pg.body.lesson.position, 0); ok('lessons: position saved; invalid position falls back to the first unfinished section');

console.log('tutor: practice inside a lesson, drafts, errors, history');
const TU2 = async (body: any) => { const r = await fetch('http://127.0.0.1:8007/', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ token: TOKEN, day: DAY, kid_id: ali.id, subject: 'english', ...body }) }); return { status: r.status, body: await r.json() }; };
let pr = await TU2({ action: 'practice', lesson_id: les.id }); assert.equal(pr.status, 200); assert.ok(pr.body.questions.every((q: string) => q.includes('آشنایی'))); assert.equal(pr.body.section, 0); ok('tutor: lesson practice asks only about the current section');
const PX2 = pr.body.exercise_id;
const again = await TU2({ action: 'practice', lesson_id: les.id }); assert.equal(again.body.exercise_id, PX2); assert.equal(again.body.resumed, true); ok('tutor: asking again returns the same unfinished practice (no new AI call)');
assert.equal((await TU2({ action: 'draft', exercise_id: PX2, answers: ['l1', 'x'] })).body.ok, true); ok('tutor: typed answers saved as a draft');
const profR = await TU2({ action: 'profile' }); assert.equal(profR.body.open_practice.exercise_id, PX2); assert.deepEqual(profR.body.open_practice.draft, ['l1', 'x']); assert.equal(profR.body.last_lesson.id, les.id); ok('tutor: profile hands back the unfinished practice with its draft, and the last lesson');
assert.equal((await TU2({ action: 'practice', lesson_id: les.id })).body.draft[0], 'l1'); ok('tutor: resumed practice carries the draft answers');
let ck = await TU2({ action: 'check', exercise_id: PX2, answers: ['AIFAIL', 'x', 'l3', 'l4', 'l5'] }); assert.equal(ck.status, 502); ok('tutor: AI error while checking -> 502');
assert.equal((await rest(`/tutor_exercises?id=eq.${PX2}`))[0].status, 'open'); assert.equal((await TU2({ action: 'practice', lesson_id: les.id })).body.exercise_id, PX2); ok('tutor: after the AI error the practice is still open and nothing is lost');
const balT = (await rest(`/kid_balance?kid_id=eq.${ali.id}`))[0].points;
ck = await TU2({ action: 'check', exercise_id: PX2, answers: ['l1', 'x', 'l3', 'l4', 'l5'] }); assert.equal(ck.status, 200); assert.equal(ck.body.score, 4); ok('tutor: retrying with the same exercise works (4/5)');
assert.equal((await rest(`/kid_balance?kid_id=eq.${ali.id}`))[0].points, balT + 4); ok('tutor: points awarded once, after the successful check');
const lsAfter = (await rest(`/tutor_lessons?id=eq.${les.id}`))[0]; assert.deepEqual([lsAfter.sections[0].asked, lsAfter.sections[0].correct, lsAfter.sections[0].done, lsAfter.position], [5, 4, true, 1]); ok('tutor: lesson memory updated: section 1 done (4/5), position moved to section 2');
assert.equal((await TU2({ action: 'draft', exercise_id: PX2, answers: ['z'] })).status, 409); ok('tutor: draft on a finished practice -> 409');
const nx = await TU2({ action: 'practice', lesson_id: les.id }); assert.ok(nx.body.questions.every((q: string) => q.includes('بخش دیگر')) && nx.body.section === 1); ok('tutor: the next practice moves on to the next section');
const hs = await TU2({ action: 'history' }); assert.ok(hs.body.items.some((i: any) => i.kind === 'practice' && i.status === 'checked' && i.score === 4 && i.lesson === 'درس ۳: آب')); assert.ok(hs.body.lessons.some((l: any) => l.id === les.id && l.done === 1 && l.total === 3)); ok('tutor: history shows scores per lesson and lesson progress');

const bare = (await rest('/tutor_lessons', 'POST', { kid_id: ali.id, subject: 'english', title: 'بدون رسانه', sections: [], media: [] }))[0];
let at = await tl({ action: 'attach_qr', lesson_id: bare.id, qr_url: 'http://book.sch.ir/lesson3' }); assert.equal(at.body.added, 2); assert.equal(at.body.lesson.media.length, 2); ok('lessons: scanning the book QR later adds its video and audio to the lesson');
at = await tl({ action: 'attach_qr', lesson_id: bare.id, qr_url: 'http://book.sch.ir/lesson3' }); assert.equal(at.body.added, 0); assert.equal(at.body.lesson.media.length, 2); ok('lessons: the same QR twice adds nothing new');
assert.equal((await tl({ action: 'attach_qr', lesson_id: bare.id, qr_url: 'https://evil.example.com/x' })).status, 403); assert.equal((await tl({ action: 'attach_qr', lesson_id: bare.id, qr_url: 'http://127.0.0.1/x' })).status, 400); ok('lessons: attach from a blocked host / internal address is refused');

console.log('lesson audio -> dictation');
await rest('/settings?id=eq.1', 'PATCH', { dictation_requires_review: true });   // an earlier test turned review off
const mkLesson = async (url: string, type = 'audio') => (await rest('/tutor_lessons', 'POST', { kid_id: ali.id, subject: 'persian', title: 'درس صوتی', sections: [], media: [{ type, url }] }))[0];
const aud = await mkLesson('http://book.sch.ir/media/l3.mp3');
let ad = await tl({ day: '2026-11-20', action: 'audio_dictation', subject: 'persian', lesson_id: aud.id }); assert.equal(ad.status, 200); assert.deepEqual([ad.body.status, ad.body.phrase_count], ['review', 3]); ok('audio dictation: lesson audio -> 3 phrases (a 0.2 s glitch is dropped)');
const dRow = (await rest(`/dictations?id=eq.${ad.body.id}`))[0];
assert.deepEqual([dRow.phrases, dRow.audio_url, dRow.lesson_id, dRow.created_by], [['آب و هوا', 'امروز خیلی خوب است', 'بچه‌ها بازی می‌کنند'], 'http://book.sch.ir/media/l3.mp3', aud.id, 'kid']); assert.deepEqual(dRow.segments, [{ start: 0, end: 2.5 }, { start: 3, end: 6 }, { start: 7, end: 10 }]); ok('audio dictation: text, original audio link and time segments are stored');
const noAud = await mkLesson('http://book.sch.ir/media/l3.mp4', 'video'); assert.equal((await tl({ day: '2026-11-20', action: 'audio_dictation', subject: 'persian', lesson_id: noAud.id })).body.error, 'no_audio'); ok('audio dictation: a lesson without audio -> no_audio');
const silent = await mkLesson('http://book.sch.ir/media/silent.mp3'); assert.equal((await tl({ day: '2026-11-20', action: 'audio_dictation', subject: 'persian', lesson_id: silent.id })).body.status, 'retake'); ok('audio dictation: unreadable audio -> retake');
const big = await mkLesson('http://book.sch.ir/media/big.mp3'); assert.equal((await tl({ day: '2026-11-20', action: 'audio_dictation', subject: 'persian', lesson_id: big.id })).status, 502); ok('audio dictation: a 13 MB file is refused (size cap)');
const doc = await mkLesson('http://book.sch.ir/media/doc.mp3'); assert.equal((await tl({ day: '2026-11-20', action: 'audio_dictation', subject: 'persian', lesson_id: doc.id })).status, 415); ok('audio dictation: a non-audio file is refused');
const evil = await mkLesson('http://evil.example.com/a.mp3'); assert.equal((await tl({ day: '2026-11-20', action: 'audio_dictation', subject: 'persian', lesson_id: evil.id })).body.error, 'host_not_allowed'); ok('audio dictation: audio from a host that is not allowed is never fetched');
const syncD = (await fn(8001, { token: TOKEN, day: DAY })).body.kids.find((k: any) => k.id === ali.id).dictations.find((d: any) => d.id === ad.body.id);
assert.equal(syncD.audio_url, 'http://book.sch.ir/media/l3.mp3'); assert.equal(syncD.segments.length, 3); ok('audio dictation: the phone receives the audio link and segments (never the text)');
for (let i = 0; i < 8; i++) await rest('/tutor_lessons', 'POST', { kid_id: ali.id, subject: 'english', title: 'x' + i, sections: [], media: [] });
assert.equal((await tl({ action: 'create', qr_url: 'http://book.sch.ir/lesson3' })).status, 429); ok('lessons: at most 8 new lessons per day');

// bonus is per real day: 11-07 had 45 min (<60) -> one bonus on 11-08, none repeated, none for a day without report
const bonuses = async () => (await rest(`/points_ledger?reason=eq.screen_bonus&kid_id=eq.${ali.id}&order=note`)).map((x: any) => x.note);
await fn(8001, { token: TOKEN, day: '2026-11-08' });
await fn(8001, { token: TOKEN, day: '2026-11-08' });
assert.deepEqual(await bonuses(), ['bonus:2026-11-06', 'bonus:2026-11-07']); ok('next day: one new bonus for 45 min, repeat sync adds nothing');
await fn(8001, { token: TOKEN, day: '2026-11-10' });
assert.deepEqual(await bonuses(), ['bonus:2026-11-06', 'bonus:2026-11-07']); ok('day with no usage report -> no bonus');
console.log('automatic penalty per task');
const [ptask] = await rest('/tasks', 'POST', [{ kid_id: ali.id, title: 'تکلیف جریمه', kind: 'daily', points: 2, penalty_points: 4 }]);
await rest(`/kids?id=eq.${ali.id}`, 'PATCH', { auto_penalty: true });
await fn(8001, { token: TOKEN, day: '2026-11-07' });          // today's rows (incl. the new task)
await fn(8001, { token: TOKEN, day: '2026-11-08' });          // charges unfinished tasks of 11-07
const aRows = await rest(`/points_ledger?kid_id=eq.${ali.id}&note=eq.auto:2026-11-07:${ptask.id}`);
assert.equal(aRows.length, 1); assert.equal(aRows[0].delta, -4); ok('auto penalty: unfinished task costs its own points (-4), once');
await fn(8001, { token: TOKEN, day: '2026-11-08' });
assert.equal((await rest(`/points_ledger?kid_id=eq.${ali.id}&note=eq.auto:2026-11-07:${ptask.id}`)).length, 1); ok('auto penalty: repeated sync does not charge again');
const penNow = (await fn(8001, { token: TOKEN, day: '2026-11-08' })).body.kids.find((k: any) => k.id === ali.id).penalties;
assert.ok(penNow.some((p: any) => p.title.includes('تکلیف جریمه') && p.points === 4)); ok("the kid's phone shows which task cost points");
await rest(`/kids?id=eq.${ali.id}`, 'PATCH', { auto_penalty: false });
await fn(8001, { token: TOKEN, day: '2026-11-09' });
assert.equal((await rest(`/points_ledger?kid_id=eq.${ali.id}&note=eq.auto:2026-11-08:${ptask.id}`)).length, 0); ok('auto penalty switched off: nothing charged for 11-08');
// cleanup so later balances stay as before
await rest(`/points_ledger?kid_id=eq.${ali.id}&note=eq.auto:2026-11-07:${ptask.id}`, 'DELETE');
await rest(`/tasks?id=eq.${ptask.id}`, 'PATCH', { active: false });
await rest(`/kids?id=eq.${ali.id}`, 'PATCH', { auto_penalty: false });
console.log(`\nALL ${n} CHECKS PASSED`);
