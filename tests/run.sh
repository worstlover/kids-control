cd /home/claude/e2e
pkill -f postgrest; pkill -f mock.ts; pkill -f "functions/device" ; pkill -f "functions/fetch"; pkill -f "functions/dictation"; sleep 1
bash setup.sh || exit 1
../pgrst/postgrest pgrst.conf >pg.log 2>&1 &
deno run -A --no-lock mock.ts >mock.log 2>&1 &
export SUPABASE_URL=http://127.0.0.1:4000 SUPABASE_SERVICE_ROLE_KEY=$(cat svc.jwt) GEMINI_API_KEY=k GEMINI_BASE_URL=http://127.0.0.1:4001
F=/home/claude/repo/supabase/single-file
DENO_SERVE_ADDRESS=tcp:127.0.0.1:8001 deno run -A --no-lock $F/device-sync.ts >f1.log 2>&1 &
DENO_SERVE_ADDRESS=tcp:127.0.0.1:8002 deno run -A --no-lock $F/device-submit.ts >f2.log 2>&1 &
DENO_SERVE_ADDRESS=tcp:127.0.0.1:8003 deno run -A --no-lock $F/fetch-price.ts >f3.log 2>&1 &
DENO_SERVE_ADDRESS=tcp:127.0.0.1:8004 deno run -A --no-lock $F/dictation-create.ts >f4.log 2>&1 &
DENO_SERVE_ADDRESS=tcp:127.0.0.1:8005 deno run -A --no-lock $F/dictation-audio.ts >f5.log 2>&1 &
sleep 7
deno run -A --no-lock e2e.ts 2>&1 | tail -4
[ -n "$SYNCDUMP" ] && curl -s -X POST http://127.0.0.1:8001/ -H "Content-Type: application/json" -d '{"token":"device-token-1234567890","day":"2026-11-07"}' > /tmp/sync.json
[ -n "$WEB" ] && (cd /home/claude/webtest && timeout 120 node web.test.mjs 2>&1 | tail -40)
