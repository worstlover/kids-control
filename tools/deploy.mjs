#!/usr/bin/env node
// Deploys all Edge Functions (single-file builds), sets secrets, and optionally runs schema.sql
// through the Supabase Management API. Needs Node 18+ and no packages.
//
//   node tools/deploy.mjs --token sbp_xxx --ref abcdefghijkl --gemini-key AIza... [--schema]
//
// (or set SUPABASE_ACCESS_TOKEN / SUPABASE_PROJECT_REF / GEMINI_API_KEY)
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'supabase');
const API = (process.env.SUPABASE_API ?? 'https://api.supabase.com').replace(/\/$/, '');
const args = process.argv.slice(2);
const opt = n => { const i = args.indexOf('--' + n); return i >= 0 ? args[i + 1] : undefined; };
const token = process.env.SUPABASE_ACCESS_TOKEN || opt('token');
const ref = process.env.SUPABASE_PROJECT_REF || opt('ref');
const gemini = process.env.GEMINI_API_KEY || opt('gemini-key');

if (!token || !ref || !gemini) {
  console.error(`مقدار لازم کم است.

  node tools/deploy.mjs --token sbp_... --ref <project-ref> --gemini-key <کلید Google AI Studio> [--schema]

  token: Supabase ← Account ← Access Tokens ← Generate new token  (با sbp_ شروع می‌شود)
  ref:   Project Settings ← General ← Reference ID
  --schema: schema.sql را هم اجرا می‌کند (فقط بار اول؛ بار دوم چون جدول‌ها هستند خطا می‌دهد)`);
  process.exit(1);
}

// verify_jwt=false: the kid's phone authenticates with its own device token, not a Supabase login
const FUNCTIONS = { 'device-sync': false, 'device-submit': false, 'dictation-create': false, 'dictation-audio': false, 'kid-pin': false, 'tutor': false, 'fetch-price': true };

async function call(method, url, body, form) {
  const res = await fetch(API + url, {
    method,
    headers: { Authorization: `Bearer ${token}`, ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
    body: form ?? (body !== undefined ? JSON.stringify(body) : undefined),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${url} -> ${res.status} ${text.slice(0, 300)}`);
  try { return JSON.parse(text); } catch { return text; }
}

try {
  if (args.includes('--schema')) {
    console.log('• اجرای schema.sql …');
    await call('POST', `/v1/projects/${ref}/database/query`, { query: fs.readFileSync(path.join(root, 'schema.sql'), 'utf8') });
    console.log('  ✓ دیتابیس ساخته شد');
  }

  console.log('• ثبت کلید Gemini …');
  await call('POST', `/v1/projects/${ref}/secrets`, [{ name: 'GEMINI_API_KEY', value: gemini }]);
  console.log('  ✓');

  for (const [slug, verifyJwt] of Object.entries(FUNCTIONS)) {
    const file = path.join(root, 'single-file', `${slug}.ts`);
    if (!fs.existsSync(file)) throw new Error(`فایل ${file} پیدا نشد (اول python3 tools/bundle.py را اجرا کن)`);
    const form = new FormData();
    form.append('metadata', JSON.stringify({ entrypoint_path: 'index.ts', name: slug, verify_jwt: verifyJwt }));
    form.append('file', new Blob([fs.readFileSync(file)], { type: 'application/typescript' }), 'index.ts');
    console.log(`• دیپلوی ${slug} …`);
    await call('POST', `/v1/projects/${ref}/functions/deploy?slug=${slug}`, undefined, form);
    // Some CLI/API versions ignore verify_jwt in the deploy metadata, so set it explicitly too.
    await call('PATCH', `/v1/projects/${ref}/functions/${slug}`, { verify_jwt: verifyJwt });
    console.log(`  ✓ https://${ref}.supabase.co/functions/v1/${slug}  (verify_jwt=${verifyJwt})`);
  }
  console.log('\nتمام شد. حالا SB_URL و کلید anon را در پنل و GitHub Secrets بگذار.');
} catch (e) {
  console.error('\n✗ خطا:', e.message);
  process.exit(1);
}
