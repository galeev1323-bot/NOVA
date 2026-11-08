-- NOVA Messenger: схема Supabase. Выполните целиком в SQL Editor.
create extension if not exists pgcrypto;

create table public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  username text unique not null check (username ~ '^[a-z0-9_]{3,32}$'),
  display_name text not null default '',
  avatar_url text, bio text default '',
  last_seen timestamptz default now(),
  hide_last_seen boolean not null default false,
  is_premium boolean not null default false,
  premium_until timestamptz,
  chat_theme text not null default 'default',
  avatar_frame text not null default 'none',
  profile_effect text not null default 'none',
  created_at timestamptz default now()
);
create table public.chats (
  id uuid primary key default gen_random_uuid(),
  is_group boolean not null default false,
  title text, avatar_url text,
  created_by uuid references auth.users(id),
  created_at timestamptz default now()
);
create table public.chat_members (
  chat_id uuid references public.chats(id) on delete cascade,
  user_id uuid references auth.users(id) on delete cascade,
  role text not null default 'member',
  last_read_at timestamptz not null default now(),
  primary key (chat_id, user_id)
);
create table public.messages (
  id uuid primary key default gen_random_uuid(),
  chat_id uuid not null references public.chats(id) on delete cascade,
  sender_id uuid not null default auth.uid() references auth.users(id),
  body text, media_url text, media_type text, media_name text,
  reply_to uuid, forwarded boolean not null default false,
  edited_at timestamptz, created_at timestamptz not null default now()
);
create index on public.messages (chat_id, created_at desc);
create table public.reactions (
  message_id uuid references public.messages(id) on delete cascade,
  user_id uuid references auth.users(id) on delete cascade,
  emoji text not null,
  primary key (message_id, user_id)
);
create table public.blocks (
  blocker_id uuid references auth.users(id) on delete cascade,
  blocked_id uuid references auth.users(id) on delete cascade,
  primary key (blocker_id, blocked_id)
);
create table public.subscriptions (
  user_id uuid references auth.users(id) on delete cascade,
  source text not null check (source in ('demo','google_play')),
  product_id text, purchase_token text, status text, expires_at timestamptz,
  primary key (user_id, source)
);

-- ===== профиль при регистрации =====
create function public.handle_new_user() returns trigger
language plpgsql security definer set search_path = public as $$
declare u text;
begin
  u := lower(coalesce(new.raw_user_meta_data->>'username',
        'user_' || substr(replace(new.id::text,'-',''),1,10)));
  insert into profiles(id, username, display_name)
  values (new.id, u, coalesce(nullif(new.raw_user_meta_data->>'display_name',''), u));
  return new;
end $$;
create trigger on_auth_user_created after insert on auth.users
  for each row execute function public.handle_new_user();

-- Premium-косметика недоступна бесплатным пользователям
create function public.enforce_premium_cosmetics() returns trigger
language plpgsql as $$
begin
  if not new.is_premium then
    new.chat_theme := 'default'; new.avatar_frame := 'none'; new.profile_effect := 'none';
  end if;
  return new;
end $$;
create trigger profiles_premium_guard before insert or update on public.profiles
  for each row execute function public.enforce_premium_cosmetics();

-- ===== вспомогательные функции =====
create function public.is_member(c uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select exists(select 1 from chat_members where chat_id = c and user_id = auth.uid())
$$;
create function public.dm_blocked(c uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select exists(
    select 1 from chats ch
    join chat_members o on o.chat_id = ch.id and o.user_id <> auth.uid()
    join blocks b on (b.blocker_id = o.user_id and b.blocked_id = auth.uid())
                  or (b.blocker_id = auth.uid() and b.blocked_id = o.user_id)
    where ch.id = c and not ch.is_group)
$$;
create function public.username_available(u text) returns boolean
language sql stable security definer set search_path = public as $$
  select not exists(select 1 from profiles where username = lower(u))
$$;
grant execute on function public.username_available(text) to anon, authenticated;

create function public.get_or_create_dm(other uuid) returns uuid
language plpgsql security definer set search_path = public as $$
declare cid uuid;
begin
  if auth.uid() is null or other = auth.uid() then raise exception 'invalid'; end if;
  select c.id into cid from chats c
   where not c.is_group
     and exists(select 1 from chat_members where chat_id = c.id and user_id = auth.uid())
     and exists(select 1 from chat_members where chat_id = c.id and user_id = other) limit 1;
  if cid is null then
    insert into chats(is_group, created_by) values (false, auth.uid()) returning id into cid;
    insert into chat_members(chat_id, user_id) values (cid, auth.uid()), (cid, other);
  end if;
  return cid;
end $$;

create function public.create_group(title text, members uuid[]) returns uuid
language plpgsql security definer set search_path = public as $$
declare cid uuid; m uuid;
begin
  if auth.uid() is null or coalesce(trim(title),'') = '' then raise exception 'invalid'; end if;
  insert into chats(is_group, title, created_by) values (true, trim(title), auth.uid()) returning id into cid;
  insert into chat_members(chat_id, user_id, role) values (cid, auth.uid(), 'owner');
  foreach m in array members loop
    if m <> auth.uid() then
      insert into chat_members(chat_id, user_id) values (cid, m) on conflict do nothing;
    end if;
  end loop;
  return cid;
end $$;

create function public.get_my_chats() returns table(
  id uuid, is_group boolean, title text, avatar_url text, other_user_id uuid,
  last_body text, last_at timestamptz, unread bigint)
language sql stable security definer set search_path = public as $$
  select c.id, c.is_group,
    case when c.is_group then c.title else p.display_name end,
    case when c.is_group then c.avatar_url else p.avatar_url end,
    p.id,
    coalesce(lm.body, case when lm.media_type is not null then '[' || lm.media_type || ']' end),
    coalesce(lm.created_at, c.created_at),
    (select count(*) from messages m where m.chat_id = c.id and m.sender_id <> auth.uid()
        and m.created_at > me.last_read_at)
  from chat_members me
  join chats c on c.id = me.chat_id
  left join lateral (select o.user_id from chat_members o
        where o.chat_id = c.id and o.user_id <> auth.uid() and not c.is_group limit 1) o on true
  left join profiles p on p.id = o.user_id
  left join lateral (select m2.body, m2.media_type, m2.created_at from messages m2
        where m2.chat_id = c.id order by m2.created_at desc limit 1) lm on true
  where me.user_id = auth.uid()
  order by 7 desc
$$;

-- ТЕСТОВЫЙ Premium (демо, НЕ оплата). Для продакшна удалите эти функции.
create function public.activate_demo_premium() returns void
language plpgsql security definer set search_path = public as $$
begin
  insert into subscriptions(user_id, source, status, expires_at)
  values (auth.uid(), 'demo', 'active', now() + interval '7 days')
  on conflict (user_id, source) do update set status='active', expires_at = now() + interval '7 days';
  update profiles set is_premium = true, premium_until = now() + interval '7 days' where id = auth.uid();
end $$;
create function public.deactivate_demo_premium() returns void
language plpgsql security definer set search_path = public as $$
begin
  delete from subscriptions where user_id = auth.uid() and source = 'demo';
  update profiles set is_premium = exists(
      select 1 from subscriptions s where s.user_id = auth.uid() and s.status = 'active'
        and s.expires_at > now()),
    premium_until = null
  where id = auth.uid();
end $$;

-- ===== права на колонки =====
revoke update on public.profiles from authenticated, anon;
grant update (username, display_name, avatar_url, bio, last_seen, hide_last_seen,
              chat_theme, avatar_frame, profile_effect) on public.profiles to authenticated;
revoke update on public.messages from authenticated, anon;
grant update (body, edited_at) on public.messages to authenticated;
revoke update on public.chat_members from authenticated, anon;
grant update (last_read_at) on public.chat_members to authenticated;

-- ===== RLS =====
alter table public.profiles enable row level security;
alter table public.chats enable row level security;
alter table public.chat_members enable row level security;
alter table public.messages enable row level security;
alter table public.reactions enable row level security;
alter table public.blocks enable row level security;
alter table public.subscriptions enable row level security;

create policy profiles_read on public.profiles for select to authenticated using (true);
create policy profiles_upd on public.profiles for update to authenticated using (id = auth.uid());
create policy chats_read on public.chats for select to authenticated using (public.is_member(id));
create policy members_read on public.chat_members for select to authenticated using (public.is_member(chat_id));
create policy members_upd on public.chat_members for update to authenticated using (user_id = auth.uid());
create policy msg_read on public.messages for select to authenticated using (public.is_member(chat_id));
create policy msg_ins on public.messages for insert to authenticated
  with check (sender_id = auth.uid() and public.is_member(chat_id) and not public.dm_blocked(chat_id));
create policy msg_upd on public.messages for update to authenticated
  using (sender_id = auth.uid()) with check (sender_id = auth.uid());
create policy msg_del on public.messages for delete to authenticated using (sender_id = auth.uid());
create policy react_read on public.reactions for select to authenticated
  using (exists(select 1 from messages m where m.id = message_id and public.is_member(m.chat_id)));
create policy react_ins on public.reactions for insert to authenticated
  with check (user_id = auth.uid() and exists(select 1 from messages m where m.id = message_id and public.is_member(m.chat_id)));
create policy react_upd on public.reactions for update to authenticated using (user_id = auth.uid());
create policy react_del on public.reactions for delete to authenticated using (user_id = auth.uid());
create policy blocks_all on public.blocks for all to authenticated
  using (blocker_id = auth.uid()) with check (blocker_id = auth.uid());
create policy subs_read on public.subscriptions for select to authenticated using (user_id = auth.uid());

-- ===== Realtime =====
alter publication supabase_realtime add table public.messages, public.reactions, public.chat_members;

-- ===== Storage =====
insert into storage.buckets (id, name, public, file_size_limit)
values ('media', 'media', true, 52428800) on conflict (id) do nothing;
create policy media_upload on storage.objects for insert to authenticated
  with check (bucket_id = 'media' and (storage.foldername(name))[1] = auth.uid()::text);
create policy media_delete on storage.objects for delete to authenticated
  using (bucket_id = 'media' and (storage.foldername(name))[1] = auth.uid()::text);
