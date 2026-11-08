-- NOVA 2.2.0: secure owner-issued Premium grants.
-- Apply in Supabase SQL Editor after 20261108_stars_owner.sql.

create table if not exists public.owner_premium_grants (
  user_id uuid primary key references auth.users(id) on delete cascade,
  granted_by uuid not null references auth.users(id),
  granted_at timestamptz not null default now(),
  expires_at timestamptz
);

alter table public.owner_premium_grants enable row level security;
revoke all on public.owner_premium_grants from anon, authenticated;

create or replace function public.grant_premium(
  p_user_id uuid,
  p_days integer
)
returns timestamptz
language plpgsql
security definer
set search_path = public
as $$
declare
  v_existing timestamptz;
  v_new_expiry timestamptz;
  v_has_grant boolean;
begin
  if auth.uid() is null then
    raise exception 'not_authenticated';
  end if;

  if not exists (
    select 1 from public.staff_roles
    where user_id = auth.uid() and role = 'owner'
  ) then
    raise exception 'owner_only' using errcode = '42501';
  end if;

  if p_user_id is null or p_days is null or p_days not in (0, 7, 30, 90, 180, 365) then
    raise exception 'invalid_premium_duration';
  end if;

  select expires_at into v_existing
  from public.owner_premium_grants
  where user_id = p_user_id;
  v_has_grant := found;

  if v_has_grant and v_existing is null then
    v_new_expiry := null; -- already permanent
  elsif p_days = 0 then
    v_new_expiry := null; -- permanent
  else
    v_new_expiry := greatest(coalesce(v_existing, now()), now())
      + make_interval(days => p_days);
  end if;

  insert into public.owner_premium_grants(user_id, granted_by, expires_at)
  values (p_user_id, auth.uid(), v_new_expiry)
  on conflict (user_id) do update
    set granted_by = excluded.granted_by,
        granted_at = now(),
        expires_at = excluded.expires_at;

  update public.profiles
  set is_premium = true,
      premium_until = v_new_expiry
  where id = p_user_id;

  return v_new_expiry;
end;
$$;

revoke all on function public.grant_premium(uuid, integer) from public;
grant execute on function public.grant_premium(uuid, integer) to authenticated;


-- Recalculate the current user's Premium state so expired grants do not stay active.
create or replace function public.sync_my_premium()
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  v_permanent boolean;
  v_until timestamptz;
begin
  if auth.uid() is null then
    raise exception 'not_authenticated';
  end if;

  select exists (
    select 1 from public.owner_premium_grants
    where user_id = auth.uid()
      and (expires_at is null or expires_at > now())
      and expires_at is null
  ) or exists (
    select 1 from public.subscriptions
    where user_id = auth.uid()
      and status = 'active'
      and expires_at is null
  ) into v_permanent;

  select max(expires_at) into v_until
  from (
    select expires_at from public.owner_premium_grants
    where user_id = auth.uid() and expires_at > now()
    union all
    select expires_at from public.subscriptions
    where user_id = auth.uid() and status = 'active' and expires_at > now()
  ) active_expiries;

  update public.profiles
  set is_premium = v_permanent or v_until is not null,
      premium_until = case when v_permanent then null else v_until end
  where id = auth.uid();
end;
$$;

revoke all on function public.sync_my_premium() from public;
grant execute on function public.sync_my_premium() to authenticated;
