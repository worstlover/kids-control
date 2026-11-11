import { deepStrictEqual as assertEquals } from 'node:assert';
import * as L from './logic.ts';

Deno.test('weekday/prevDay', () => {
  assertEquals(L.weekdayOf('2026-11-07'), 6);           // Saturday
  assertEquals(L.prevDay('2026-03-01'), '2026-02-28');
  assertEquals(L.prevDay('2027-01-01'), '2026-12-31');
});
Deno.test('dueTasks', () => {
  const t = [
    { id: 'a', kind: 'daily' as const, weekdays: null },
    { id: 'b', kind: 'weekly' as const, weekdays: [6] },
    { id: 'c', kind: 'weekly' as const, weekdays: [1] },
    { id: 'd', kind: 'once' as const, weekdays: null },
    { id: 'e', kind: 'once' as const, weekdays: null },
  ];
  assertEquals(L.dueTasks(t, '2026-11-07', new Set(['e'])).map(x => x.id), ['a', 'b', 'd']);
});
Deno.test('points', () => {
  assertEquals(L.scaledPoints(10, 20), 10);
  assertEquals(L.scaledPoints(10, 15), 8);   // 7.5 rounds to 8
  assertEquals(L.scaledPoints(10, 99), 10);
  assertEquals(L.scaledPoints(10, -4), 0);
  assertEquals(L.shouldAutoApprove(14, 14), true);
  assertEquals(L.shouldAutoApprove(13.5, 14), false);
  assertEquals(L.shouldAutoApprove(20, null), false);
});
Deno.test('bonus', () => {
  const k = { bonus_under_min: 60, bonus_points: 10 };
  assertEquals(L.bonusFor(k, 30, true), 10);
  assertEquals(L.bonusFor(k, 60, true), 0);
  assertEquals(L.bonusFor(k, 0, false), 0);          // no report = no bonus
  assertEquals(L.bonusFor({ bonus_under_min: null, bonus_points: 10 }, 5, true), 0);
});
Deno.test('parseJsonLoose', () => {
  assertEquals(L.parseJsonLoose('بفرمایید ```json\n{"score": 17, "feedback": "آفرین {عالی}"}\n``` تمام'), { score: 17, feedback: 'آفرین {عالی}' });
  assertEquals(L.parseJsonLoose('no json here'), null);
  assertEquals(L.parseJsonLoose('{"a": 1'), null);
});
Deno.test('normalizeGrade', () => {
  assertEquals(L.normalizeGrade({ score: 17.3, feedback: 'ok' }), { readable: true, score: 17.5, feedback: 'ok' });
  assertEquals(L.normalizeGrade({ score: 50, feedback: '' })?.score, 20);
  assertEquals(L.normalizeGrade({ readable: false, feedback: 'تار' }), { readable: false, score: 0, feedback: 'تار' });
  assertEquals(L.normalizeGrade({ score: 'abc' }), null);
  assertEquals(L.normalizeGrade(null), null);
});
Deno.test('parsePrice', () => {
  assertEquals(L.parsePrice('۱٬۲۳۴٬۵۰۰ تومان'.replace(/٬/g, ',')), 1234500);
  assertEquals(L.parsePrice(850000), 850000);
  assertEquals(L.parsePrice('abc'), null);
  assertEquals(L.parsePrice(-5), null);
});
Deno.test('extractMeta + url', () => {
  const html = `<meta property="og:title" content="دوچرخه"><meta content="https://x.ir/a.jpg" property="og:image">`;
  assertEquals(L.extractMeta(html), { title: 'دوچرخه', image: 'https://x.ir/a.jpg' });
  assertEquals(L.isHttpUrl('https://a.b'), true);
  assertEquals(L.isHttpUrl('javascript:alert(1)'), false);
  assertEquals(L.isHttpUrl('file:///etc/passwd'), false);
});

Deno.test('dictation helpers', () => {
  assertEquals(L.normalizePhrases([' سلام   دنیا ', '', 5, 'خوبی؟']), ['سلام دنیا', 'خوبی؟']);
  assertEquals(L.normalizePhrases('x'), []);
  assertEquals(L.normalizePhrases(Array(40).fill('الف')).length, 15);
  assertEquals(L.countDictationDoneToday([{ status: 'done', done_day: 'd1' }, { status: 'graded', done_day: 'd1' }, { status: 'ready', done_day: null }, { status: 'done', done_day: 'd0' }], 'd1'), 2);
  assertEquals(L.findAudio({ steps: [{ type: 'thought' }, { type: 'model_output', content: [{ type: 'text', text: 'x' }, { type: 'audio', data: 'QUJD' }] }] }), 'QUJD');
  assertEquals(L.findAudio({ steps: [] }), null);
});

Deno.test('week chart helpers', () => {
  assertEquals(L.tehranDay('2026-11-07T20:29:00Z'), '2026-11-07');   // 23:59 in Tehran
  assertEquals(L.tehranDay('2026-11-07T20:31:00Z'), '2026-11-08');   // 00:01 next day in Tehran
  assertEquals(L.lastDays('2026-11-02', 4), ['2026-10-30', '2026-10-31', '2026-11-01', '2026-11-02']);
  const days = L.lastDays('2026-11-07');
  assertEquals(days.length, 7); assertEquals(days[6], '2026-11-07'); assertEquals(days[0], '2026-11-01');
  const rows = [
    { delta: 5, created_at: '2026-11-07T08:00:00Z' }, { delta: 3, created_at: '2026-11-07T09:00:00Z' },
    { delta: -4, created_at: '2026-11-07T10:00:00Z' },        // penalty: not "earned"
    { delta: 7, created_at: '2026-11-05T10:00:00Z' }, { delta: 9, created_at: '2026-10-20T10:00:00Z' }, // outside window
  ];
  assertEquals(L.weekPoints(rows, days), [0, 0, 0, 0, 7, 0, 8]);
});

Deno.test('cleanSvg keeps plain drawings and rejects anything risky', () => {
  assertEquals(L.cleanSvg('<svg viewBox="0 0 400 240"><circle cx="5" cy="5" r="2"/></svg>')?.startsWith('<svg'), true);
  assertEquals(L.cleanSvg('<svg><script>alert(1)</script></svg>'), null);
  assertEquals(L.cleanSvg('<svg><circle onclick="x()"/></svg>'), null);
  assertEquals(L.cleanSvg('<svg><a href="javascript:alert(1)"/></svg>'), null);
  assertEquals(L.cleanSvg('<svg><image href="https://evil.example/x.png"/></svg>'), null);
  assertEquals(L.cleanSvg('<div>not svg</div>'), null);
  assertEquals(L.cleanSvg('<svg><rect/>'), null);
  assertEquals(L.cleanSvg(42), null);
});

Deno.test('schedules: weekdays, monthly, interval, date range, once', () => {
  // 2026-11-07 is a Saturday (6)
  assertEquals(L.isDueOn({ kind: 'daily', weekdays: null, schedule: { type: 'weekdays', weekdays: [6] } }, '2026-11-07', false), true);
  assertEquals(L.isDueOn({ kind: 'daily', weekdays: null, schedule: { type: 'weekdays', weekdays: [1, 3] } }, '2026-11-07', false), false);
  // monthly on the 31st: February falls back to the last day
  assertEquals(L.isDueOn({ kind: 'daily', weekdays: null, schedule: { type: 'monthly', month_day: 31 } }, '2026-02-28', false), true);
  assertEquals(L.isDueOn({ kind: 'daily', weekdays: null, schedule: { type: 'monthly', month_day: 15 } }, '2026-02-28', false), false);
  // every 3 days from Nov 1
  const iv = { kind: 'daily', weekdays: null, schedule: { type: 'interval' as const, every_n: 3, start: '2026-11-01' } };
  assertEquals(['2026-11-01', '2026-11-04', '2026-11-07'].map(d => L.isDueOn(iv, d, false)), [true, true, true]);
  assertEquals(['2026-11-02', '2026-11-05'].map(d => L.isDueOn(iv, d, false)), [false, false]);
  assertEquals(L.isDueOn(iv, '2026-10-29', false), false);   // before start
  // date range
  const r = { kind: 'daily', weekdays: null, schedule: { type: 'daily' as const, start: '2026-11-05', end: '2026-11-06' } };
  assertEquals([L.isDueOn(r, '2026-11-04', false), L.isDueOn(r, '2026-11-05', false), L.isDueOn(r, '2026-11-07', false)], [false, true, false]);
  // once on a date, never again after it was logged
  const o = { kind: 'once', weekdays: null, schedule: { type: 'once' as const, date: '2026-11-07' } };
  assertEquals(L.isDueOn(o, '2026-11-07', false), true); assertEquals(L.isDueOn(o, '2026-11-07', true), false); assertEquals(L.isDueOn(o, '2026-11-08', false), false);
  // old tasks without schedule still work
  assertEquals(L.isDueOn({ kind: 'weekly', weekdays: [6], schedule: null }, '2026-11-07', false), true);
  assertEquals(L.autoPenaltyNote('2026-11-07', 'abc'), 'auto:2026-11-07:abc');
});

Deno.test('tehranToday uses Iran time, not UTC', () => {
  assertEquals(L.tehranToday(new Date('2026-11-07T20:00:00Z')), '2026-11-07');   // 23:30 in Tehran
  assertEquals(L.tehranToday(new Date('2026-11-07T21:00:00Z')), '2026-11-08');   // 00:30 in Tehran, UTC still says the 7th
});

Deno.test('lesson QR: url safety, host allow-list, media extraction', () => {
  assertEquals(L.isSafePublicUrl('https://book.sch.ir/lesson3'), true);
  for (const bad of ['http://localhost/x', 'http://127.0.0.1/x', 'http://10.0.0.5/x', 'http://169.254.169.254/latest', 'https://[::1]/x', 'file:///etc/passwd',
    'javascript:alert(1)', 'https://user:pw@sch.ir/x', 'https://sch.ir:8443/x', 'http://intranet/x', 'https://x.internal/a', 'http://2130706433/']) assertEquals(L.isSafePublicUrl(bad), false, bad);
  assertEquals(L.hostAllowed('https://book.sch.ir/a', 'sch.ir,roshd.ir'), true);
  assertEquals(L.hostAllowed('https://sch.ir.evil.com/a', 'sch.ir'), false);
  assertEquals(L.hostAllowed('https://notsch.ir/a', 'sch.ir'), false);
  assertEquals(L.hostAllowed('https://x.sch.ir', ''), false);
  const html = `<video src="/v/l3.mp4"></video><audio src="https://cdn.sch.ir/a/l3.mp3"></audio>
    <source src="clip.webm"><a href="/files/read.m4a">خواندن درس</a><iframe src="https://www.aparat.com/video/video/embed/videohash/abc"></iframe>
    <video src="http://127.0.0.1/evil.mp4"></video><a href="/page.html">x</a><meta property="og:video" content="https://cdn.sch.ir/a/l3.mp4">`;
  const m = L.extractMedia(html, 'https://book.sch.ir/lesson3');
  assertEquals(m.map(x => x.type + ':' + x.url), [
    'video:https://book.sch.ir/v/l3.mp4', 'audio:https://cdn.sch.ir/a/l3.mp3', 'video:https://book.sch.ir/clip.webm',
    'video:https://cdn.sch.ir/a/l3.mp4', 'audio:https://book.sch.ir/files/read.m4a', 'page:https://www.aparat.com/video/video/embed/videohash/abc']);
});
Deno.test('lesson progress: sections, practice rounds, resume point', () => {
  const secs = L.normalizeSections(['  آشنایی  ', { title: 'واژه‌ها' }, '', 5, 'جمع‌بندی']);
  assertEquals(secs.map(s => s.title), ['آشنایی', 'واژه‌ها', 'جمع‌بندی']);
  assertEquals(L.nextSection(secs), 0);
  let s2 = L.applyPractice(secs, 0, 3, 5); assertEquals(s2[0].done, false);          // 60%
  s2 = L.applyPractice(s2, 0, 4, 5); assertEquals([s2[0].asked, s2[0].correct, s2[0].done], [10, 7, true]);
  assertEquals(L.nextSection(s2), 1);
  assertEquals(L.nextSection(s2.map(s => ({ ...s, done: true }))), 2);
  assertEquals(L.nextSection([]), 0);
  assertEquals(L.applyPractice(secs, 9, 5, 5), secs);
});
Deno.test('cleanSegments keeps only sensible timestamps', () => {
  const segs = L.cleanSegments([
    { text: 'الف', start: 0, end: 2.4 }, { text: 'ب', start: 2.5, end: 2.6 },           // too short
    { text: 'ج', start: 3, end: 9 }, { text: 'د', start: 1, end: 5 },                    // goes backwards
    { text: '', start: 10, end: 12 }, { text: 'ه', start: 10, end: 80 }, { text: 'و', start: 'x', end: 4 }, { text: 'ز', start: 10, end: 13 }]);
  assertEquals(segs.map(s => s.text), ['الف', 'ج', 'ز']);
  assertEquals(L.cleanSegments('no'), []);
});
Deno.test('tutor tracks: school vs free', () => {
  assertEquals(L.subjectKey('Math', undefined), 'math');
  assertEquals(L.subjectKey('english', 'school'), 'english');
  assertEquals(L.subjectKey('English', 'free'), 'free:english');
  assertEquals(L.subjectKey('free:شطرنج', undefined), 'free:شطرنج');
  assertEquals(L.subjectKey('free:گیتار', 'free'), 'free:گیتار');
  assertEquals(L.subjectKey('  ', 'free'), '');
  assertEquals(L.subjectKey('free:', 'free'), '');
  assertEquals(L.subjectKey(42, 'free'), '');
  assertEquals(L.subjectKey('x'.repeat(60), 'free').length, 45);
  assertEquals(L.splitSubject('free:شطرنج'), { track: 'free', name: 'شطرنج' });
  assertEquals(L.splitSubject('math'), { track: 'school', name: 'math' });
  assertEquals(L.trackOf('anything'), 'school');
});
Deno.test('tutor mastery: spaced review', () => {
  const c0 = { stage: 0, attempts: 0, first_try_correct: 0, next_review: null };
  assertEquals(L.conceptStatus(0, 0), 'new');
  // same-day practice does not promote, but schedules a review tomorrow
  let r = L.applyConceptResult(c0, 1, 1, '2026-11-11', false);
  assertEquals([r.stage, r.status, r.next_review], [0, 'practicing', '2026-11-12']);
  // exit quiz passed (2/3) -> stage 1, review in 3 days
  r = L.applyConceptResult(c0, 2, 3, '2026-11-11', true);
  assertEquals([r.stage, r.status, r.next_review, r.moved], [1, 'practicing', '2026-11-14', 1]);
  // 3-day and 7-day reviews passed -> learned at stage 3
  let c = { ...c0, ...r };
  r = L.applyConceptResult(c, 1, 1, '2026-11-14', L.isDue(c, '2026-11-14')); c = { ...c, ...r };
  assertEquals([r.stage, r.next_review], [2, '2026-11-21']);
  assertEquals(L.isDue(c, '2026-11-20'), false);
  r = L.applyConceptResult(c, 1, 1, '2026-11-21', L.isDue(c, '2026-11-21')); c = { ...c, ...r };
  assertEquals([r.stage, r.status, r.next_review], [3, 'learned', '2026-12-12']);
  r = L.applyConceptResult(c, 1, 1, '2026-12-12', true); c = { ...c, ...r };
  assertEquals([r.stage, r.status], [4, 'solid']);
  // forgot it -> back to practicing, review tomorrow
  r = L.applyConceptResult(c, 0, 1, '2027-02-20', true);
  assertEquals([r.stage, r.status, r.next_review, r.moved], [0, 'practicing', '2027-02-21', -4]);
  // half right: no move, keeps its review day
  r = L.applyConceptResult({ stage: 1, attempts: 3, first_try_correct: 2, next_review: '2026-11-14' }, 1, 2, '2026-11-14', true);
  assertEquals([r.stage, r.next_review], [1, '2026-11-14']);
  assertEquals(L.conceptKey('  Fractions   ADD '), 'fractions add');
  assertEquals(L.addDays('2026-12-30', 3), '2027-01-02');
});
Deno.test('tutor level + points', () => {
  assertEquals(L.nextLevel('متوسط', [1, 0.8]), 'متوسط');               // too few checks
  assertEquals(L.nextLevel('متوسط', [0.8, 1, 0.8, 1]), 'پیشرفته');
  assertEquals(L.nextLevel('پیشرفته', [1, 1, 1]), 'پیشرفته');
  assertEquals(L.nextLevel('متوسط', [0.2, 0.4, 0.6]), 'مبتدی');
  assertEquals(L.nextLevel(null, [1, 1, 1]), null);
  assertEquals(L.answerPoints(true, true, true), 2);
  assertEquals(L.answerPoints(true, false), 0);
  assertEquals(L.answerPoints(false, true), 0);
});
