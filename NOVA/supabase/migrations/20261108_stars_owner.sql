-- NOVA 2.1.0: secure stars wallet and owner/admin grants.
-- Apply this migration in Supabase SQL Editor after schema.sql.
create table if not exists public.staff_roles (
  user_id uuid primary key references auth.users(id) on delete cascade,
  role text not null check (role in ('owner','admin')),
  created_at timestamptz not null default now()
);

-- The project owner account that was designated in the NOVA setup.
insert into public.staff_roles(user_id, role)
values ('e5668300-b9e3-4062-aae4-da32b27003fe'::uuid, 'owner')
on conflict (user_id) do update set role = 'owner';

create table if not exists public.star_wallets (
  user_id uuid primary key references auth.users(id) on delete cascade,
  balance bigint not null default 0 check (balance >= 0),
  updated_at timestamptz not null default now()
);

create table if not exists public.star_transactions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  actor_id uuid references auth.users(id),
  amount bigint not null check (amount <> 0),
  reason text not null default '',
  created_at timestamptz not null default now()
);
create index if not exists star_transactions_user_created_idx
  on public.star_transactions(user_id, created_at desc);

alter table public.staff_roles enable row level security;
alter table public.star_wallets enable row level security;
alter table public.star_transactions enable row level security;

drop policy if exists staff_roles_read_self on public.staff_roles;
create policy staff_roles_read_self on public.staff_roles
  for select to authenticated using (user_id = auth.uid());

drop policy if exists star_wallets_read_self on public.star_wallets;
create policy star_wallets_read_self on public.star_wallets
  for select to authenticated using (user_id = auth.uid());

drop policy if exists star_transactions_read_self on public.star_transactions;
create policy star_transactions_read_self on public.star_transactions
  for select to authenticated using (user_id = auth.uid());

revoke all on public.staff_roles from anon, authenticated;
grant select on public.staff_roles to authenticated;
revoke insert, update, delete on public.star_wallets from anon, authenticated;
grant select on public.star_wallets to authenticated;
revoke insert, update, delete on public.star_transactions from anon, authenticated;
grant select on public.star_transactions to authenticated;

create or replace function public.is_owner_or_admin()
returns boolean
language sql stable security definer
set search_path = public
as $$
  select exists (
    select 1 from public.staff_roles
    where user_id = auth.uid() and role in ('owner','admin')
  );
$$;
revoke all on function public.is_owner_or_admin() from public;
grant execute on function public.is_owner_or_admin() to authenticated;

create or replace function public.get_my_stars()
returns bigint
language plpgsql stable security definer
set search_path = public
as $$
declare v_balance bigint;
begin
  if auth.uid() is null then raise exception 'not_authenticated'; end if;
  select balance into v_balance from public.star_wallets where user_id = auth.uid();
  return coalesce(v_balance, 0);
end;
$$;
revoke all on function public.get_my_stars() from public;
grant execute on function public.get_my_stars() to authenticated;

create or replace function public.grant_stars(
  p_user_id uuid,
  p_amount bigint,
  p_reason text default 'Начисление владельцем'
)
returns bigint
language plpgsql security definer
set search_path = public
as $$
declare v_balance bigint;
begin
  if auth.uid() is null or not public.is_owner_or_admin() then
    raise exception 'not_authorized' using errcode = '42501';
  end if;
  if p_user_id is null or p_amount is null or p_amount = 0 or abs(p_amount) > 1000000 then
    raise exception 'invalid_amount';
  end if;
  insert into public.star_wallets(user_id, balance)
  values (p_user_id, 0)
  on conflict (user_id) do nothing;

  update public.star_wallets
  set balance = balance + p_amount, updated_at = now()
  where user_id = p_user_id and balance + p_amount >= 0
  returning balance into v_balance;

  if v_balance is null then
    raise exception 'insufficient_stars_or_wallet_error';
  end if;

  insert into public.star_transactions(user_id, actor_id, amount, reason)
  values (p_user_id, auth.uid(), p_amount, coalesce(nullif(trim(p_reason),''), 'Операция владельца'));
  return v_balance;
end;
$$;
revoke all on function public.grant_stars(uuid,bigint,text) from public;
grant execute on function public.grant_stars(uuid,bigint,text) to authenticated;
