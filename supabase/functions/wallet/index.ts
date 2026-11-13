import { admin, authDevice, cors, json } from '../_shared/common.ts';
import { ledgerSource, SPEND_CATEGORIES, spendCost, walletBreakdown, type SpendCategory } from '../_shared/logic.ts';

// deno-lint-ignore-file no-explicit-any
// The kid's wallet, from the phone: where the points came from, and spending them.
// Actions: summary, spend. A spend shows up at once in the parent panel (Points tab), where a parent can undo it.
const MAX_SPENDS_PER_DAY = 20;
const MAX_TOMAN = 50_000_000;

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') return new Response('ok', { headers: cors });
  if (req.method !== 'POST') return json({ error: 'method_not_allowed' }, 405);
  let body: any;
  try { body = await req.json(); } catch { return json({ error: 'bad_json' }, 400); }
  const db = admin();
  if (!(await authDevice(db, body.token))) return json({ error: 'unauthorized' }, 401);
  const { data: kid } = await db.from('kids').select('id,name').eq('id', body.kid_id).eq('active', true).maybeSingle();
  if (!kid) return json({ error: 'kid_not_found' }, 404);
  const { data: st } = await db.from('settings').select('toman_per_point').single();
  const rate = Number(st?.toman_per_point) || 1000;
  try {
    if (body.action === 'summary') return await summary(db, kid, rate);
    if (body.action === 'spend') return await spend(db, kid, rate, body);
    return json({ error: 'bad_action' }, 400);
  } catch (e) {
    console.error(e);
    return json({ error: 'wallet_unavailable' }, 502);
  }
});

async function rowsOf(db: any, kidId: string) {
  const { data } = await db.from('points_ledger').select('id,delta,reason,note,category,amount_toman,ref_id,created_at').eq('kid_id', kidId).order('created_at', { ascending: false }).limit(5000);
  return data ?? [];
}

async function summary(db: any, kid: any, rate: number) {
  const rows = await rowsOf(db, kid.id);
  const since = new Date(Date.now() - 30 * 86400_000).toISOString();
  const all = walletBreakdown(rows), month = walletBreakdown(rows.filter((r: any) => r.created_at >= since));
  const gifts: Record<string, number> = {};
  for (const r of rows) if (r.reason === 'invest_gift' && r.ref_id) gifts[r.ref_id] = r.delta;
  return json({
    balance: all.balance, toman_per_point: rate,
    earned: all.earned, spent: all.spent, spent_toman: all.spentToman, penalties: all.penalties, payouts: all.payouts,
    month: { earned: month.earned, spent: month.spent, spent_toman: month.spentToman, penalties: month.penalties },
    spends: rows.filter((r: any) => r.reason === 'spend').slice(0, 30).map((r: any) => ({
      id: r.id, title: r.note, category: r.category, points: -r.delta, gift: gifts[r.ref_id] ?? 0, amount_toman: r.amount_toman, at: r.created_at,
    })),
    recent: rows.filter((r: any) => r.reason !== 'spend').slice(0, 25).map((r: any) => ({ delta: r.delta, source: ledgerSource(r), note: r.note, at: r.created_at })),
  });
}

async function spend(db: any, kid: any, rate: number, body: any) {
  const category = body.category as SpendCategory;
  if (!SPEND_CATEGORIES.includes(category)) return json({ error: 'bad_category' }, 400);
  const amount = Math.round(Number(body.amount_toman));
  if (!Number.isFinite(amount) || amount < 100 || amount > MAX_TOMAN) return json({ error: 'bad_amount' }, 400);
  const title = typeof body.title === 'string' ? body.title.replace(/\s+/g, ' ').trim().slice(0, 60) : '';
  if (!title) return json({ error: 'title_required' }, 400);

  const today = new Date(Date.now() - 86400_000).toISOString();
  const { count } = await db.from('points_ledger').select('id', { count: 'exact', head: true }).eq('kid_id', kid.id).eq('reason', 'spend').gte('created_at', today);
  if ((count ?? 0) >= MAX_SPENDS_PER_DAY) return json({ error: 'daily_limit' }, 429);

  const cost = spendCost(amount, rate, category);
  const rows = await rowsOf(db, kid.id);
  const balance = rows.reduce((a: number, r: any) => a + r.delta, 0);
  if (balance < cost.net) return json({ error: 'not_enough_points', balance, ...cost }, 409);

  const ref = crypto.randomUUID();          // both rows of one spend share it, so the parent can undo the pair
  if (cost.gift > 0) {
    const { error } = await db.from('points_ledger').insert({ kid_id: kid.id, delta: cost.gift, reason: 'invest_gift', ref_id: ref, note: 'هدیه‌ی ۲۰٪ والدین برای سرمایه‌گذاری: ' + title });
    if (error) return json({ error: 'db_error' }, 500);
  }
  const { error } = await db.from('points_ledger').insert({ kid_id: kid.id, delta: -cost.points, reason: 'spend', category, amount_toman: amount, ref_id: ref, note: title });
  if (error) {
    if (cost.gift > 0) await db.from('points_ledger').delete().eq('ref_id', ref);
    return json({ error: 'db_error' }, 500);
  }
  return json({ ok: true, ...cost, amount_toman: amount, balance: balance - cost.net });
}
