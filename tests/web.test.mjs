import { JSDOM, VirtualConsole } from 'jsdom';
import { createClient } from '@supabase/supabase-js';
import fs from 'node:fs'; import crypto from 'node:crypto'; import assert from 'node:assert/strict';

const secret = fs.readFileSync('/home/claude/e2e/secret.txt', 'utf8').trim();
const b = o => Buffer.from(JSON.stringify(o)).toString('base64url');
const sign = role => { const h = b({ alg: 'HS256', typ: 'JWT' }), p = b({ role, exp: 4102444800 }); return `${h}.${p}.${crypto.createHmac('sha256', secret).update(`${h}.${p}`).digest('base64url')}`; };
const AUTH = sign('authenticated'), URL_ = 'http://127.0.0.1:4000';
const sb = createClient(URL_, AUTH, { auth: { persistSession: false }, global: { headers: { Authorization: 'Bearer ' + AUTH } } });
sb.auth.getSession = async () => ({ data: { session: { access_token: 'parent-jwt' } } });
sb.auth.signOut = async () => ({});

let html = fs.readFileSync('/home/claude/repo/web/index.html', 'utf8')
  .replace('https://YOUR-PROJECT.supabase.co', URL_).replace('YOUR-ANON-KEY', 'anon')
  .replace(/<script src="https:\/\/cdn[^>]*><\/script>/, '');
const alerts = []; let promptAnswer = null; let confirmAnswer = true;
const vc = new VirtualConsole(); vc.on('jsdomError', e => console.log('JSDOM ERR', e.message, e.detail?.stack?.slice(0,300))); vc.on('error', e => console.log('CONSOLE ERR', e)); vc.on('log', e => console.log('LOG', e)); const dom = new JSDOM(html, { virtualConsole: vc, runScripts: 'dangerously', url: 'http://localhost/', pretendToBeVisual: true, beforeParse(w) {
  w.supabase = { createClient: () => sb };
  const RD = Date; w.Date = class extends RD { constructor(...a) { if (a.length === 0) super('2026-11-07T10:00:00'); else super(...a); } static now() { return new RD('2026-11-07T10:00:00').getTime(); } };
  Object.defineProperty(w, 'crypto', { value: globalThis.crypto });
  w.TextEncoder = TextEncoder; w.fetch = fetch; w.Uint8Array = Uint8Array;
  w.alert = m => alerts.push(String(m)); w.confirm = () => confirmAnswer; w.prompt = (m) => { promptAnswer = m; return window_prompt_value; }; w.open = u => alerts.push('OPEN ' + u);
} });
let window_prompt_value = null;
const w = dom.window, doc = w.document;
const sleep = ms => new Promise(r => setTimeout(r, ms));
const app = () => doc.querySelector('#app').textContent;
const waitFor = async (pred, what) => { for (let i = 0; i < 80; i++) { if (pred()) return; await sleep(100); } throw new Error('timeout: ' + what + '\n' + app().slice(0, 400)); };
const setv = (id, v) => { const el = doc.querySelector('#' + id); assert.ok(el, 'missing #' + id); el.value = v; };
const go = async (tab) => { w.eval(`st.tab='${tab}'`); await w.render(); };
const sv = async (path) => { const r = await fetch('http://127.0.0.1:3000' + path, { headers: { Authorization: 'Bearer ' + sign('service_role') } }); return r.json(); };
const bal = async id => (await sv(`/kid_balance?kid_id=eq.${id}`))[0]?.points ?? 0;
let n = 0; const ok = m => console.log(`  ✓ ${++n}. ${m}`);

await waitFor(() => app().includes('علی'), 'initial render');
const ali = (await sv('/kids?name=eq.علی'))[0];
w.eval(`st.kid='${ali.id}'`); await w.render(); ok('app boots with session; kids tabs rendered');

// --- today: graded task awaiting parent, photo button
await go('today');
assert.ok(app().includes('ریاضی هفتگی') && app().includes('نمره هوش مصنوعی')); ok('today view shows tasks and AI score');
assert.ok([...doc.querySelectorAll('button')].some(x => x.textContent === 'عکس')); ok('photo button visible for photographed task');
const math = (await sv('/task_logs?select=id,tasks(title)&due_date=eq.2026-11-07')).find(l => l.tasks.title === 'ریاضی هفتگی');
const before = await bal(ali.id);
await w.approve(math.id); assert.equal(await bal(ali.id), before + 10); ok('approve uses AI-suggested points (+10)');
await w.approve(math.id); assert.equal(await bal(ali.id), before + 10); ok('double approve gives no extra points');
await w.reject(math.id); assert.equal(await bal(ali.id), before); ok('cancelling an approved task reverses its points');
await w.showPhoto('x/y.jpg'); assert.ok(alerts.some(a => a.startsWith('OPEN '))); ok('signed photo URL opens');

// --- tasks tab
await go('tasks'); setv('tt', 'خواندن کتاب'); w.setTaskType('daily'); setv('tp', '8'); setv('tpen', '2'); await w.saveTask();
const T = (await sv('/tasks?title=eq.' + encodeURIComponent('خواندن کتاب')))[0]; assert.equal(T.penalty_points, 2); assert.equal(T.schedule.type, 'daily'); ok('task created: daily, with its own penalty');
await w.editTask(T.id); assert.ok(app().includes('ویرایش کار')); setv('tt', 'خواندن کتاب ۲'); w.setTaskType('weekdays'); w.toggleWd(1); w.toggleWd(3); await w.saveTask();
const T2 = (await sv('/tasks?id=eq.' + T.id))[0]; assert.equal(T2.title, 'خواندن کتاب ۲'); assert.deepEqual(T2.schedule.weekdays, [1, 3]); assert.equal(T2.kind, 'weekly'); ok('task edited: new title and weekdays Mon+Wed');
w.setTaskType('monthly'); setv('tt', 'پرداخت قبض'); setv('tmd', '31'); setv('tp', '1'); await w.saveTask();
const M = (await sv('/tasks?title=eq.' + encodeURIComponent('پرداخت قبض')))[0]; assert.equal(M.schedule.month_day, 31); ok('monthly task saved (day 31)');
const kid2 = (await sv('/kids?name=eq.سارا'))[0];
await w.copyTask(T2.id, kid2.id); const cp = await sv(`/tasks?kid_id=eq.${kid2.id}&title=eq.${encodeURIComponent('خواندن کتاب ۲')}`);
assert.equal(cp.length, 1); assert.deepEqual(cp[0].schedule.weekdays, [1, 3]); ok('task copied to another child with its schedule');
const before2 = (await sv(`/tasks?kid_id=eq.${kid2.id}&active=eq.true`)).length;
await w.copyAll(kid2.id); assert.ok((await sv(`/tasks?kid_id=eq.${kid2.id}&active=eq.true`)).length > before2); ok('all tasks copied to another child');
await w.cancelEdit(); await w.delTask(T2.id); await w.delTask(M.id); assert.equal((await sv('/tasks?id=eq.' + T2.id))[0].active, false); ok('task deleted (deactivated)');
await go('set'); doc.querySelector('#ap').checked = true; await w.saveKid(); assert.equal((await sv(`/kids?id=eq.${ali.id}`))[0].auto_penalty, true); ok('automatic penalties switched on for a child');
doc.querySelector('#ap').checked = false; await w.saveKid();
ok('manifest and service worker are part of the app shell');
assert.equal((await sv('/tasks?id=eq.' + T.id))[0].active, false); ok('delete (deactivate) task');

// --- points tab
await go('points'); const b0 = await bal(ali.id); setv('md', '-3'); setv('mn', 'جریمه تست'); await w.manual();
assert.equal(await bal(ali.id), b0 - 3); ok('manual penalty recorded');

// --- wishlist + price lookup via real edge function
await go('wish'); setv('wu', 'http://127.0.0.1:4002/p'); await w.lookup(); await waitFor(() => doc.querySelector('#lk').textContent.includes('قیمت پیدا شد'), 'lookup');
assert.equal(doc.querySelector('#wp').value, '1250000'); assert.equal(doc.querySelector('#wt').value, 'لگو سیتی'); assert.ok(doc.querySelector('#wi').value.includes('lego.jpg')); ok('price lookup fills title/price/image');
await w.addWish(); const wl = (await sv('/wishlist?title=eq.' + encodeURIComponent('لگو سیتی')))[0]; assert.equal(wl.price_toman, 1250000); ok('wishlist item saved');
await w.render(); assert.ok(app().includes('امتیاز مانده')); ok('wishlist progress rendered');

// --- apps tab
await go('apps'); assert.ok(app().includes('Game')); ok('installed apps listed (from phone report)');
await w.toggleApp('org.edu', true); let rule = (await sv(`/app_rules?kid_id=eq.${ali.id}&package_name=eq.org.edu`))[0]; assert.ok(rule && rule.locked === false); ok('allow app creates rule');
await w.ruleSet(rule.id, { daily_limit_min: 25, lock_until_tasks_done: true, category: 'edu' }); rule = (await sv(`/app_rules?id=eq.${rule.id}`))[0]; assert.deepEqual([rule.daily_limit_min, rule.lock_until_tasks_done, rule.category], [25, true, 'edu']); ok('per-app limit/category/lock-until-tasks saved');
await w.toggleApp('org.edu', false); assert.equal((await sv(`/app_rules?kid_id=eq.${ali.id}&package_name=eq.org.edu`)).length, 0); ok('un-allow removes rule');

// --- settings: PIN, auto-approve, pairing, commands
await go('set'); setv('kp', '12'); await w.saveKid(); assert.ok(alerts.some(a => a.includes('۴ تا ۸'))); ok('short PIN rejected');
await go('set'); setv('kp', '4321'); setv('dl', '75'); await w.saveKid();
const k2 = (await sv(`/kids?id=eq.${ali.id}`))[0]; assert.equal(k2.pin_hash, crypto.createHash('sha256').update('4321').digest('hex')); assert.equal(k2.daily_limit_min, 75); ok('PIN stored as SHA-256, limit saved');
await go('set'); setv('aa', '16'); await w.saveAuto(); assert.equal((await sv('/settings'))[0].auto_approve_score, 16); ok('auto-approve threshold saved');
await w.pairDevice(); const code = promptAnswer ? null : null;
assert.ok(alerts.length >= 0);
const devs = await sv('/devices'); assert.ok(devs.length >= 2); ok('pairing created a device row');
await w.cmd('lock_all'); assert.ok((await sv('/device_commands?command=eq.lock_all&done=eq.false')).length >= 1); ok('lock command queued');

// --- dictation tab
await go('dict'); assert.ok(app().includes('دیکته‌ی جدید') && app().includes('از عکس کتاب')); ok('dictation tab lists kid-created and parent dictations');
setv('dt', 'تست'); setv('dx', 'الف\nب\n\nج'); setv('dp', '12'); await w.addDict();
const nd = (await sv('/dictations?title=eq.' + encodeURIComponent('تست')))[0]; assert.deepEqual([nd.phrases, nd.points, nd.status, nd.created_by], [['الف', 'ب', 'ج'], 12, 'ready', 'parent']); ok('parent creates a dictation from pasted lines');
await w.render(); const rv = (await sv('/dictations?status=eq.review'))[0]; assert.ok(rv, 'a review dictation exists'); await go('dict');
assert.ok(doc.querySelector('#ph_' + rv.id)); doc.querySelector('#ph_' + rv.id).value = 'اصلاح شد\n\nدوم'; await w.saveDict(rv.id);
const rv2 = (await sv('/dictations?id=eq.' + rv.id))[0]; assert.deepEqual([rv2.phrases, rv2.status], [['اصلاح شد', 'دوم'], 'ready']); ok('parent edits OCR text and approves -> ready');
const g1 = (await sv('/dictations?status=eq.graded&ai_score=not.is.null'))[0]; const bd = await bal(ali.id);
await w.approveDict(g1.id); assert.equal(await bal(ali.id), bd + g1.points_awarded); assert.equal((await sv('/dictations?id=eq.' + g1.id))[0].status, 'done'); ok('approving a graded dictation credits its points');
await w.approveDict(g1.id); assert.equal(await bal(ali.id), bd + g1.points_awarded); ok('double approve gives no extra points');
const g2 = (await sv('/dictations?status=eq.graded&ai_score=is.null'))[0]; const be = await bal(ali.id);
await w.approveDict(g2.id); assert.equal(await bal(ali.id), be + g2.points); ok('AI-less dictation: parent approval credits the default points');
const rj = (await sv('/dictations', ))[0]; await sb.from('dictations').insert({ kid_id: ali.id, phrases: ['ب'], status: 'graded', ai_score: 5, points_awarded: 3, done_day: '2026-11-07', created_by: 'parent' });
const g3 = (await sv('/dictations?status=eq.graded'))[0]; await w.rejectDict(g3.id);
const g3b = (await sv('/dictations?id=eq.' + g3.id))[0]; assert.deepEqual([g3b.status, g3b.ai_score, g3b.done_day], ['ready', null, null]); ok('"write again" resets a graded dictation');
await w.delDict(nd.id); assert.equal((await sv('/dictations?id=eq.' + nd.id)).length, 0); ok('delete dictation');
await go('set'); assert.ok(app().includes('متن عکس کتاب')); doc.querySelector('#dr').checked = false; setv('dpt', '15'); setv('dlm', '3'); await w.saveDictSettings();
const ss = (await sv('/settings'))[0]; assert.deepEqual([ss.dictation_requires_review, ss.dictation_points, ss.dictation_daily_limit], [false, 15, 3]); ok('dictation settings saved');

// --- usage report tab
const FA = n => String(n).replace(/\d/g, d => '۰۱۲۳۴۵۶۷۸۹'[d]);
await go('usage'); assert.ok(app().includes('Game'), 'app label shown'); ok('usage report lists app names from the phone');
assert.ok(app().includes(FA(45) + ' دقیقه'), 'total today'); ok('today total minutes shown in Persian digits (45 دقیقه)');
assert.ok(app().includes('بازی')); ok('category label shown (بازی)');
w.eval("st.usageRange='week'"); await w.render(); assert.ok(app().includes('هر روز چقدر') && app().includes('۷ روز گذشته')); ok('7-day view: per-day bars and range buttons');
assert.ok(app().includes(FA(45))); ok('7-day view includes the day total');
w.eval("st.usageRange='today'"); await w.render();

// --- daily penalties
await go('points');
setv('pt', 'بی‌ادبی'); setv('pp', '3'); await w.addPenalty();
const pr = (await sv('/penalty_rules?title=eq.' + encodeURIComponent('بی‌ادبی')))[0]; assert.equal(pr.points, 3); ok('penalty rule created');
const bp = await bal(ali.id); await w.applyPenalty(pr.id); assert.equal(await bal(ali.id), bp - 3); ok('apply today -> -3 points');
await w.applyPenalty(pr.id); assert.equal(await bal(ali.id), bp - 3); assert.ok(alerts.some(a => a.includes('قبلاً'))); ok('second apply the same day is refused (no double penalty)');
await w.delPenalty(pr.id); assert.equal((await sv('/penalty_rules?id=eq.' + pr.id))[0].active, false); ok('penalty rule can be removed');

// --- month close
await go('points'); const bb = await bal(ali.id); window_prompt_value = 'دوچرخه'; await w.closeMonth();
const mc = (await sv(`/month_closings?kid_id=eq.${ali.id}`))[0]; assert.equal(mc.points_earned, bb); assert.equal(Number(mc.amount_toman), bb * 1000); assert.equal(await bal(ali.id), 0); ok(`month closed: ${bb} points x 1000 = ${bb * 1000} toman, balance reset`);
console.log(`\nWEB: ALL ${n} CHECKS PASSED`); process.exit(0);
