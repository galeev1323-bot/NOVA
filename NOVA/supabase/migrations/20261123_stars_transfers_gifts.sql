-- NOVA 2.3.0: secure Stars transfers and gifts.
-- Apply once in Supabase SQL Editor after the existing NOVA 2.1.0/2.2.0 migrations.

create table if not exists public.gift_catalog (
  id text primary key,
  title text not null,
  emoji text not null,
  price_stars bigint not null check (price_stars > 0),
  active boolean not null default true
);

insert into public.gift_catalog(id, title, emoji, price_stars) values
  ('rose', 'Роза', '🌹', 10),
  ('heart', 'Сердце', '💝', 25),
  ('trophy', 'Кубок', '🏆', 75),
  ('rocket', 'Ракета', '🚀', 150),
  ('crown', 'Корона', '👑', 300)
on conflict (id) do update set
  title = excluded.title,
  emoji = excluded.emoji,
  price_stars = excluded.price_stars,
  active = true;

create table if not exists public.gifts (
  id uuid primary key default gen_random_uuid(),
  sender_id uuid not null references auth.users(id) on delete cascade,
  recipient_id uuid not null references auth.users(id) on delete cascade,
  gift_id text not null references public.gift_catalog(id),
  message text not null default '',
  created_at timestamptz not null default now()
);
create index if not exists gifts_recipient_created_idx
  on public.gifts(recipient_id, created_at desc);
create index if not exists gifts_sender_created_idx
  on public.gifts(sender_id, created_at desc);

alter table public.gift_catalog enable row level security;
alter table public.gifts enable row level security;

drop policy if exists gift_catalog_read on public.gift_catalog;
create policy gift_catalog_read on public.gift_catalog
  for select to authenticated using (active);

drop policy if exists gifts_read_participant on public.gifts;
create policy gifts_read_participant on public.gifts
  for select to authenticated
  using (sender_id = auth.uid() or recipient_id = auth.uid());

revoke all on public.gift_catalog from anon, authenticated;
grant select on public.gift_catalog to authenticated;
revoke all on public.gifts from anon, authenticated;
grant select on public.gifts to authenticated;

create or replace function public.transfer_stars(
  p_username text,
  p_amount bigint,
  p_message text default ''
)
returns bigint
language plpgsql
security definer
set search_path = public
as $$
declare
  v_sender uuid := auth.uid();
  v_recipient uuid;
  v_sender_balance bigint;
  v_recipient_balance bigint;
  v_sender_name text;
  v_username text := lower(trim(coalesce(p_username, '')));
begin
  if v_sender is null then
    raise exception 'not_authenticated';
  end if;
  if v_username !~ '^[a-z0-9_]{3,32}$'
     or p_amount is null or p_amount < 1 or p_amount > 100000 then
    raise exception 'invalid_transfer';
  end if;

  select id into v_recipient
  from public.profiles
  where username = v_username;

  if v_recipient is null then
    raise exception 'recipient_not_found';
  end if;
  if v_recipient = v_sender then
    raise exception 'cannot_transfer_to_self';
  end if;
  select username into v_sender_name from public.profiles where id = v_sender;

  insert into public.star_wallets(user_id, balance)
  values (v_sender, 0), (v_recipient, 0)
  on conflict (user_id) do nothing;

  -- Lock both wallets in a stable order to avoid opposing transfers deadlocking.
  perform user_id
  from public.star_wallets
  where user_id in (v_sender, v_recipient)
  order by user_id
  for update;

  select balance into v_sender_balance
  from public.star_wallets where user_id = v_sender;
  if v_sender_balance < p_amount then
    raise exception 'insufficient_stars';
  end if;

  update public.star_wallets
  set balance = balance - p_amount, updated_at = now()
  where user_id = v_sender
  returning balance into v_sender_balance;

  update public.star_wallets
  set balance = balance + p_amount, updated_at = now()
  where user_id = v_recipient
  returning balance into v_recipient_balance;

  insert into public.star_transactions(user_id, actor_id, amount, reason)
  values
    (v_sender, v_sender, -p_amount, 'Перевод пользователю @' || v_username || case when trim(coalesce(p_message, '')) = '' then '' else ': ' || left(trim(p_message), 160) end),
    (v_recipient, v_sender, p_amount, 'Перевод от @' || v_sender_name || case when trim(coalesce(p_message, '')) = '' then '' else ': ' || left(trim(p_message), 160) end);

  return v_sender_balance;
end;
$$;

revoke all on function public.transfer_stars(text, bigint, text) from public;
grant execute on function public.transfer_stars(text, bigint, text) to authenticated;

create or replace function public.send_gift(
  p_username text,
  p_gift_id text,
  p_message text default ''
)
returns uuid
language plpgsql
security definer
set search_path = public
as $$
declare
  v_sender uuid := auth.uid();
  v_recipient uuid;
  v_price bigint;
  v_sender_balance bigint;
  v_gift_uuid uuid;
  v_username text := lower(trim(coalesce(p_username, '')));
begin
  if v_sender is null then
    raise exception 'not_authenticated';
  end if;
  if v_username !~ '^[a-z0-9_]{3,32}$' then
    raise exception 'invalid_recipient';
  end if;

  select id into v_recipient from public.profiles where username = v_username;
  if v_recipient is null then raise exception 'recipient_not_found'; end if;
  if v_recipient = v_sender then raise exception 'cannot_gift_self'; end if;

  select price_stars into v_price
  from public.gift_catalog
  where id = p_gift_id and active = true;
  if v_price is null then raise exception 'gift_not_found'; end if;

  insert into public.star_wallets(user_id, balance)
  values (v_sender, 0)
  on conflict (user_id) do nothing;

  select balance into v_sender_balance
  from public.star_wallets
  where user_id = v_sender
  for update;
  if v_sender_balance < v_price then raise exception 'insufficient_stars'; end if;

  update public.star_wallets
  set balance = balance - v_price, updated_at = now()
  where user_id = v_sender
  returning balance into v_sender_balance;

  insert into public.star_transactions(user_id, actor_id, amount, reason)
  values (v_sender, v_sender, -v_price, 'Подарок @' || v_username || ': ' || p_gift_id);

  insert into public.gifts(sender_id, recipient_id, gift_id, message)
  values (v_sender, v_recipient, p_gift_id, left(coalesce(p_message, ''), 160))
  returning id into v_gift_uuid;

  return v_gift_uuid;
end;
$$;

revoke all on function public.send_gift(text, text, text) from public;
grant execute on function public.send_gift(text, text, text) to authenticated;
