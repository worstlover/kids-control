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
