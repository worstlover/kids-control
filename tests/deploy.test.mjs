// Runs tools/deploy.mjs against a fake Management API and checks every request.
import http from 'node:http'; import { spawn } from 'node:child_process'; import assert from 'node:assert/strict';
import path from 'node:path'; import { fileURLToPath } from 'node:url';
const script = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'tools', 'deploy.mjs');
const reqs = []; let failWith = 0;
const srv = http.createServer(async (req, res) => {
  const chunks = []; for await (const c of req) chunks.push(c); const buf = Buffer.concat(chunks);
  const r = { method: req.method, url: req.url, auth: req.headers.authorization, ct: req.headers['content-type'] ?? '' };
  if (r.ct.startsWith('multipart/')) { const fd = await new Response(buf, { headers: { 'content-type': r.ct } }).formData(); r.meta = JSON.parse(fd.get('metadata')); r.file = fd.get('file'); r.fileName = r.file.name; r.fileText = await r.file.text(); }
  else if (buf.length) r.json = JSON.parse(buf.toString());
  reqs.push(r);
  if (failWith) { res.writeHead(failWith); return res.end('{"message":"nope"}'); }
  res.writeHead(r.method === 'POST' && r.url.includes('/deploy') ? 201 : 200, { 'content-type': 'application/json' }); res.end('{}');
});
await new Promise(r => srv.listen(4100, r));
// async spawn: the fake API lives in this same process, so a blocking spawnSync would deadlock
const run = a => new Promise(res => {
  const c = spawn('node', [script, ...a], { env: { PATH: process.env.PATH, SUPABASE_API: 'http://127.0.0.1:4100' } });
  let stdout = '', stderr = ''; c.stdout.on('data', d => stdout += d); c.stderr.on('data', d => stderr += d);
  c.on('close', status => res({ status, stdout, stderr }));
});
let n = 0; const ok = m => console.log(`  ✓ ${++n}. ${m}`);

let r = await run([]); assert.equal(r.status, 1); assert.ok(r.stderr.includes('--token')); assert.equal(reqs.length, 0); ok('missing arguments -> usage, exit 1, no requests');

r = await run(['--token', 'sbp_t', '--ref', 'abcd', '--gemini-key', 'G-KEY', '--schema']); assert.equal(r.status, 0, r.stderr + r.stdout);
const q = reqs.find(x => x.url === '/v1/projects/abcd/database/query'); assert.ok(q.json.query.includes('create table if not exists dictations')); ok('--schema sends the real schema.sql');
const sec = reqs.find(x => x.url === '/v1/projects/abcd/secrets'); assert.deepEqual(sec.json, [{ name: 'GEMINI_API_KEY', value: 'G-KEY' }]); ok('Gemini key set as secret');
const dep = reqs.filter(x => x.url.includes('/functions/deploy')); assert.deepEqual(dep.map(x => new URL(x.url, 'http://x').searchParams.get('slug')).sort(), ['device-submit', 'device-sync', 'dictation-audio', 'dictation-create', 'fetch-price', 'kid-pin', 'tutor', 'tutor-lesson', 'wallet', 'wish']); ok('all 10 functions deployed');
assert.ok(dep.every(x => x.auth === 'Bearer sbp_t' && x.fileName === 'index.ts' && x.meta.entrypoint_path === 'index.ts' && x.fileText.length > 5000 && !/from '\.\.?\//.test(x.fileText))); ok('each upload is index.ts, authenticated, self-contained (no relative imports)');
assert.deepEqual(Object.fromEntries(dep.map(x => [x.meta.name, x.meta.verify_jwt])), { 'device-sync': false, 'device-submit': false, 'dictation-create': false, 'dictation-audio': false, 'kid-pin': false, 'tutor': false, 'tutor-lesson': false, 'wallet': false, 'wish': false, 'fetch-price': true }); ok('verify_jwt off only for the 9 phone functions');
const patches = reqs.filter(x => x.method === 'PATCH'); assert.equal(patches.length, 10); assert.equal(patches.find(x => x.url.endsWith('/device-sync')).json.verify_jwt, false); assert.equal(patches.find(x => x.url.endsWith('/fetch-price')).json.verify_jwt, true); ok('verify_jwt also enforced via PATCH');
assert.ok(r.stdout.includes('supabase.co/functions/v1/device-sync')); ok('prints function URLs');

reqs.length = 0; r = await run(['--token', 'sbp_t', '--ref', 'abcd', '--gemini-key', 'G']); assert.ok(!reqs.some(x => x.url.includes('database/query'))); ok('without --schema the database is not touched');
reqs.length = 0; failWith = 401; r = await run(['--token', 'bad', '--ref', 'abcd', '--gemini-key', 'G']); assert.equal(r.status, 1); assert.ok(r.stderr.includes('401')); assert.equal(reqs.length, 1); ok('API error -> stops at the first failure with the status code');
srv.close(); console.log(`\nDEPLOY SCRIPT: ALL ${n} CHECKS PASSED`);
