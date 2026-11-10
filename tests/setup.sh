set -e
service postgresql start >/dev/null 2>&1; sleep 2
su postgres -c "psql -q -c 'drop database if exists t2' -c 'create database t2'"
su postgres -c "psql -q -d t2 -f /home/claude/e2e/roles.sql"
su postgres -c "psql -q -v ON_ERROR_STOP=1 -d t2 -f /home/claude/repo/supabase/schema.sql"
su postgres -c "psql -q -d t2 -c 'grant usage on schema public to authenticated, service_role, anon; grant all on all tables in schema public to authenticated, service_role; grant all on all sequences in schema public to service_role;'"
