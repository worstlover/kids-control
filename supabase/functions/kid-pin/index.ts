import { admin, authDevice, cors, json, sha256 } from '../_shared/common.ts';
import { isValidPin } from '../_shared/logic.ts';

// deno-lint-ignore-file no-explicit-any
// A kid sets or changes their own PIN from the phone.
// - no PIN yet: anyone with the paired device can set the first one
// - PIN exists: the current PIN must be sent and match, otherwise 403
Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') return new Response('ok', { headers: cors });
  if (req.method !== 'POST') return json({ error: 'method_not_allowed' }, 405);
  let body: any;
  try { body = await req.json(); } catch { return json({ error: 'bad_json' }, 400); }

  const db = admin();
  if (!(await authDevice(db, body.token))) return json({ error: 'unauthorized' }, 401);
  if (!isValidPin(body.new_pin)) return json({ error: 'bad_pin' }, 400);

  const { data: kid } = await db.from('kids').select('id,pin_hash').eq('id', body.kid_id).eq('active', true).maybeSingle();
  if (!kid) return json({ error: 'kid_not_found' }, 404);

  if (kid.pin_hash) {
    if (typeof body.old_pin !== 'string' || (await sha256(body.old_pin)) !== kid.pin_hash) {
      return json({ error: 'wrong_pin' }, 403);
    }
  }
  const { error } = await db.from('kids').update({ pin_hash: await sha256(body.new_pin) }).eq('id', kid.id);
  if (error) return json({ error: 'db_error' }, 500);
  return json({ ok: true });
});
