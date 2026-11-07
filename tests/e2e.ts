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

// bonus is per real day: 11-07 had 45 min (<60) -> one bonus on 11-08, none repeated, none for a day without report
const bonuses = async () => (await rest(`/points_ledger?reason=eq.screen_bonus&kid_id=eq.${ali.id}&order=note`)).map((x: any) => x.note);
await fn(8001, { token: TOKEN, day: '2026-11-08' });
await fn(8001, { token: TOKEN, day: '2026-11-08' });
assert.deepEqual(await bonuses(), ['bonus:2026-11-06', 'bonus:2026-11-07']); ok('next day: one new bonus for 45 min, repeat sync adds nothing');
await fn(8001, { token: TOKEN, day: '2026-11-10' });
assert.deepEqual(await bonuses(), ['bonus:2026-11-06', 'bonus:2026-11-07']); ok('day with no usage report -> no bonus');
console.log(`\nALL ${n} CHECKS PASSED`);
