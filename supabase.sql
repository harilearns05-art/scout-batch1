-- Run in Supabase: SQL Editor -> New query -> paste -> Run. Safe to run again.
create table if not exists potholes (
  id text primary key,
  lat double precision not null,
  lng double precision not null,
  bump int not null default 0,
  pot int not null default 0,
  hits text[] not null default '{}',
  clean text[] not null default '{}'
);
alter table potholes add column if not exists cat text not null default 'road';
alter table potholes enable row level security;
-- TEST ONLY: anyone with the key can read/write. Tighten before a public launch.
drop policy if exists "open test access" on potholes;
create policy "open test access" on potholes for all using (true) with check (true);
