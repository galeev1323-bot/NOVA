-- NOVA 2.2.0: publish a server-controlled owner badge in profiles.
-- Apply once in Supabase SQL Editor after the NOVA 2.1.0 owner/stars migration.

alter table public.profiles
  add column if not exists is_owner boolean not null default false;

create or replace function public.sync_profile_owner_flag()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  new.is_owner := exists (
    select 1 from public.staff_roles
    where user_id = new.id and role = 'owner'
  );
  return new;
end;
$$;

drop trigger if exists profiles_owner_flag_guard on public.profiles;
create trigger profiles_owner_flag_guard
before insert or update on public.profiles
for each row execute function public.sync_profile_owner_flag();

create or replace function public.sync_staff_role_owner_badge()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
  affected_user uuid;
begin
  affected_user := coalesce(new.user_id, old.user_id);
  update public.profiles
  set is_owner = exists (
    select 1 from public.staff_roles
    where user_id = affected_user and role = 'owner'
  )
  where id = affected_user;
  return coalesce(new, old);
end;
$$;

drop trigger if exists staff_roles_owner_badge_sync on public.staff_roles;
create trigger staff_roles_owner_badge_sync
after insert or update or delete on public.staff_roles
for each row execute function public.sync_staff_role_owner_badge();

update public.profiles p
set is_owner = exists (
  select 1 from public.staff_roles s
  where s.user_id = p.id and s.role = 'owner'
);
