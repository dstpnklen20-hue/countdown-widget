-- Tik Tak sync: run once in Supabase → SQL Editor → New query → Run.
-- Safe to run again: every statement checks what already exists.
--
-- All records of all tables live here as JSON (the app's own row format), one row per record.
-- Row-level security gives each account only its own records, so the publishable key can ship
-- inside the app.

create table if not exists public.sync_records (
  user_id uuid not null references auth.users (id) on delete cascade,
  kind text not null,                 -- app table name: tasks, task_lists, habits, ...
  id text not null,                   -- record id in that table
  updated_at bigint not null,         -- when the record was last changed on a device (epoch ms)
  deleted boolean not null default false,
  data jsonb not null,                -- the record itself
  server_updated_at timestamptz not null default clock_timestamp(),
  primary key (user_id, kind, id)
);

-- Devices pull "everything stored after my last pull", in server order.
create index if not exists sync_records_pull on public.sync_records (user_id, server_updated_at);

-- An older version never overwrites a newer one, whatever order devices send them in,
-- and every stored change gets a fresh server time for the next pull.
create or replace function public.sync_records_guard() returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if tg_op = 'UPDATE' and new.updated_at <= old.updated_at then
    return null;
  end if;
  new.server_updated_at := clock_timestamp();
  return new;
end;
$$;

drop trigger if exists sync_records_guard on public.sync_records;
create trigger sync_records_guard
  before insert or update on public.sync_records
  for each row execute function public.sync_records_guard();

alter table public.sync_records enable row level security;

drop policy if exists "own records" on public.sync_records;
create policy "own records" on public.sync_records
  for all to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

grant select, insert, update, delete on public.sync_records to authenticated;
