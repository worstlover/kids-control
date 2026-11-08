-- Kids Phone Control: Supabase / Postgres schema
-- Safe to run again: creates what is missing, never drops data.
-- Balance is never stored: it is always SUM(points_ledger.delta)

create table if not exists settings (
  id int primary key default 1,
  toman_per_point numeric not null default 1000,   -- the conversion rate you set
  month_start_day int not null default 1,
  dictation_requires_review boolean not null default true,  -- kid-photographed book pages wait for parent OK
  dictation_points int not null default 10,
  dictation_daily_limit int not null default 2,             -- graded dictations per kid per day
  auto_approve_score numeric default 14,           -- AI score (0..20) at/above which points are auto-awarded; null = parent approves all
  check (id = 1)
);
insert into settings default values on conflict do nothing;

-- columns added after the first version (no-ops on a fresh database)
alter table settings add column if not exists auto_approve_score numeric default 14;
alter table settings add column if not exists dictation_requires_review boolean not null default true;
alter table settings add column if not exists dictation_points int not null default 10;
alter table settings add column if not exists dictation_daily_limit int not null default 2;

create table if not exists kids (
  id uuid primary key default gen_random_uuid(),
  name text not null,
  avatar text,
  pin_hash text,                                   -- kid-side PIN on the phone
  daily_limit_min int not null default 120,        -- total daily screen time
  bonus_under_min int,                             -- if usage < this, bonus applies
  bonus_points int not null default 0,
  active boolean not null default true
);

-- Task templates (daily / weekly / one-off)
create table if not exists tasks (
  id uuid primary key default gen_random_uuid(),
  kid_id uuid not null references kids on delete cascade,
  title text not null,
  kind text not null check (kind in ('daily','weekly','once')),
  weekdays int[],                                  -- 0..6 for weekly tasks
  subject text check (subject in ('dictation','math','other')),
  points int not null default 5,
  needs_photo boolean not null default false,      -- true = AI grades the photo
  active boolean not null default true
);

-- One row per task occurrence
create table if not exists task_logs (
  id uuid primary key default gen_random_uuid(),
  task_id uuid not null references tasks on delete cascade,
  kid_id uuid not null references kids on delete cascade,
  due_date date not null,
  status text not null default 'pending'
    check (status in ('pending','submitted','graded','approved','rejected')),
  photo_path text,                                 -- Supabase Storage
  ai_score numeric,                                -- 0..20
  ai_feedback text,
  points_awarded int,
  parent_note text,
  created_at timestamptz default now(),
  unique (task_id, due_date)
);

-- Per-app rules enforced by the Android app
create table if not exists app_rules (
  id uuid primary key default gen_random_uuid(),
  kid_id uuid not null references kids on delete cascade,
  package_name text not null,
  label text,
  category text default 'other' check (category in ('game','social','edu','other')),
  daily_limit_min int,                             -- null = no per-app limit
  locked boolean not null default false,           -- manual lock
  lock_until_tasks_done boolean not null default false,
  allowed_from time, allowed_to time,              -- allowed hours window
  unique (kid_id, package_name)
);

-- Usage reported by the phone
create table if not exists usage_logs (
  kid_id uuid not null references kids on delete cascade,
  day date not null,
  package_name text not null,
  minutes int not null default 0,
  primary key (kid_id, day, package_name)
);

-- All points movements
create table if not exists points_ledger (
  id uuid primary key default gen_random_uuid(),
  kid_id uuid not null references kids on delete cascade,
  delta int not null,                              -- + earn, - spend / payout
  reason text not null
    check (reason in ('task','screen_bonus','penalty','manual','payout')),
  ref_id uuid,                                     -- task_log / closing id
  note text,
  created_at timestamptz default now()
);

-- Wishlist with prices pulled from the web
create table if not exists wishlist (
  id uuid primary key default gen_random_uuid(),
  kid_id uuid not null references kids on delete cascade,
  title text not null,
  source_url text,
  image_url text,
  price_toman numeric,
  price_checked_at timestamptz,
  priority int default 0,
  status text not null default 'wanted' check (status in ('wanted','bought','dropped'))
);

-- Month-end closing: points -> money
create table if not exists month_closings (
  id uuid primary key default gen_random_uuid(),
  kid_id uuid not null references kids on delete cascade,
  month text not null,                             -- '2026-11'
  points_earned int not null,
  toman_per_point numeric not null,                -- snapshot of the rate
  amount_toman numeric generated always as (points_earned * toman_per_point) stored,
  spent_on text,
  closed_at timestamptz default now(),
  unique (kid_id, month)
);

-- Phone devices and commands from parent (lock now, +15 min, ...)
create table if not exists devices (
  id uuid primary key default gen_random_uuid(),
  kid_id uuid references kids,
  token_hash text not null,
  fcm_token text,
  last_seen timestamptz
);
create table if not exists device_commands (
  id uuid primary key default gen_random_uuid(),
  device_id uuid not null references devices on delete cascade,
  command text not null,                           -- lock_all / unlock / extend
  payload jsonb,
  done boolean default false,
  created_at timestamptz default now()
);

-- Views
create or replace view kid_balance as
  select kid_id, coalesce(sum(delta),0) as points
  from points_ledger group by kid_id;

create or replace view wishlist_progress as
  select w.*, b.points,
         ceil(w.price_toman / s.toman_per_point) as points_needed,
         greatest(ceil(w.price_toman / s.toman_per_point) - coalesce(b.points,0), 0) as points_left
  from wishlist w
  left join kid_balance b on b.kid_id = w.kid_id
  cross join settings s
  where w.status = 'wanted';

-- Dictation: a book page -> short phrases -> read aloud by TTS -> kid writes -> photo is graded against the text
create table if not exists dictations (
  id uuid primary key default gen_random_uuid(),
  kid_id uuid not null references kids on delete cascade,
  title text not null default 'دیکته',
  phrases jsonb not null,
  status text not null default 'review' check (status in ('review','ready','graded','done')),
  points int not null default 10,
  created_by text not null default 'kid' check (created_by in ('kid','parent')),
  created_day date,
  done_day date,
  ai_score numeric,
  ai_feedback text,
  points_awarded int,
  answer_photo_path text,
  created_at timestamptz default now()
);

alter table app_rules add column if not exists always_on boolean not null default false;   -- open even when locked / time is up / limit reached

-- Private tutor, one profile per kid and subject
create table if not exists tutor_profiles (
  id uuid primary key default gen_random_uuid(),
  kid_id uuid not null references kids on delete cascade,
  subject text not null,
  goal text,
  level text,                 -- مبتدی / متوسط / پیشرفته, set by the first assessment
  level_notes text,           -- strengths and weaknesses, updated after each check
  created_at timestamptz default now(),
  unique (kid_id, subject)
);
create table if not exists tutor_plans (
  id uuid primary key default gen_random_uuid(),
  kid_id uuid not null references kids on delete cascade,
  subject text not null,
  day date not null,
  items jsonb not null,       -- [{title, type, minutes}]
  unique (kid_id, subject, day)
);
create table if not exists tutor_exercises (
  id uuid primary key default gen_random_uuid(),
  kid_id uuid not null references kids on delete cascade,
  subject text not null,
  day date not null,
  kind text not null check (kind in ('assessment','practice','explain')),
  prompt jsonb not null,      -- what the kid sees
  answer jsonb,               -- what the AI expects; never sent to the phone before checking
  status text not null default 'open' check (status in ('open','checked')),
  result jsonb,
  created_at timestamptz default now()
);

-- Daily penalties: parent defines them once, taps "apply today" (at most once per day each)
create table if not exists penalty_rules (
  id uuid primary key default gen_random_uuid(),
  kid_id uuid not null references kids on delete cascade,
  title text not null,
  points int not null check (points > 0),
  active boolean not null default true
);
create table if not exists penalty_logs (
  id uuid primary key default gen_random_uuid(),
  rule_id uuid not null references penalty_rules on delete cascade,
  kid_id uuid not null references kids on delete cascade,
  day date not null,
  points int not null,
  created_at timestamptz default now(),
  unique (rule_id, day)
);

-- Task schedule and penalty (v3)
alter table tasks add column if not exists schedule jsonb;                 -- {type, weekdays, month_day, every_n, date, start, end}
alter table tasks add column if not exists penalty_points int not null default 0;   -- taken if the task is not done (auto penalty on)
alter table tasks add column if not exists due_time text;                  -- optional "HH:MM"
alter table kids add column if not exists auto_penalty boolean not null default false;   -- automatic penalties on/off per kid
create unique index if not exists one_auto_penalty on points_ledger (kid_id, note) where reason = 'penalty' and note like 'auto:%';

-- Installed apps reported by the phone (parent picks which are allowed)
create table if not exists device_apps (
  device_id uuid not null references devices on delete cascade,
  package_name text not null,
  label text,
  primary key (device_id, package_name)
);

-- Daily penalty: one per kid per day, enforced by a partial unique index
alter table kids add column if not exists daily_penalty int not null default 0;   -- points taken when yesterday's tasks were not all done
create unique index if not exists one_penalty_per_day on points_ledger (kid_id, note) where reason = 'penalty' and note like 'penalty:%';

-- One screen-time bonus per kid per day
create unique index if not exists one_bonus_per_day on points_ledger (kid_id, note) where reason = 'screen_bonus';

-- Make the views respect RLS of the caller
alter view wishlist_progress set (security_invoker = true);
alter view kid_balance set (security_invoker = true);

-- Row Level Security: only the logged-in parent has direct access.
-- The kid phone has NO direct table access; it uses Edge Functions
-- (device-sync, device-submit) with a device token, running as service role.
do $$
declare t text;
begin
  foreach t in array array['settings','kids','tasks','task_logs','app_rules','usage_logs',
    'points_ledger','wishlist','month_closings','devices','device_commands','device_apps','dictations','tutor_profiles','tutor_plans','tutor_exercises','penalty_rules','penalty_logs']
  loop
    execute format('alter table %I enable row level security', t);
    execute format('drop policy if exists parent_all on %I', t);
    execute format('create policy parent_all on %I for all to authenticated using (true) with check (true)', t);
  end loop;
end $$;

-- Private bucket for homework photos (only when running on Supabase)
do $$
begin
  if exists (select 1 from information_schema.schemata where schema_name = 'storage') then
    insert into storage.buckets (id, name, public) values ('homework', 'homework', false)
      on conflict (id) do nothing;
    drop policy if exists homework_parent_read on storage.objects;
    create policy homework_parent_read on storage.objects for select to authenticated
      using (bucket_id = 'homework');
  end if;
end $$;
