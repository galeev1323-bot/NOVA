-- NOVA 2.3.0: Stories and channels base schema
create table if not exists public.stories (
 id uuid primary key default gen_random_uuid(),
 user_id uuid not null references auth.users(id) on delete cascade,
 media_url text not null,
 media_type text not null check (media_type in ('image','video')),
 caption text not null default '',
 created_at timestamptz not null default now(),
 expires_at timestamptz not null default (now()+interval '24 hours')
);
create index if not exists stories_active_idx on public.stories(expires_at desc);
alter table public.stories enable row level security;
drop policy if exists stories_read on public.stories;
create policy stories_read on public.stories for select to authenticated using (expires_at>now());
drop policy if exists stories_insert on public.stories;
create policy stories_insert on public.stories for insert to authenticated with check (user_id=auth.uid() and expires_at<=now()+interval '24 hours' and expires_at>now());
drop policy if exists stories_delete on public.stories;
create policy stories_delete on public.stories for delete to authenticated using (user_id=auth.uid());

create table if not exists public.channels (
 id uuid primary key default gen_random_uuid(),
 owner_id uuid not null references auth.users(id) on delete cascade,
 title text not null check (length(trim(title)) between 1 and 80),
 username text unique check (username is null or username ~ '^[a-z0-9_]{3,32}$'),
 description text not null default '',
 created_at timestamptz not null default now()
);
create table if not exists public.channel_members (
 channel_id uuid not null references public.channels(id) on delete cascade,
 user_id uuid not null references auth.users(id) on delete cascade,
 role text not null default 'subscriber' check(role in ('owner','subscriber')),
 created_at timestamptz not null default now(),
 primary key(channel_id,user_id)
);
create table if not exists public.channel_posts (
 id uuid primary key default gen_random_uuid(),
 channel_id uuid not null references public.channels(id) on delete cascade,
 author_id uuid not null references auth.users(id) on delete cascade,
 body text not null default '',
 media_url text,
 media_type text,
 created_at timestamptz not null default now()
);
create index if not exists channel_posts_order_idx on public.channel_posts(channel_id,created_at desc);
alter table public.channels enable row level security;
alter table public.channel_members enable row level security;
alter table public.channel_posts enable row level security;
drop policy if exists channels_read on public.channels;
create policy channels_read on public.channels for select to authenticated using (true);
drop policy if exists channels_create on public.channels;
create policy channels_create on public.channels for insert to authenticated with check (owner_id=auth.uid());
drop policy if exists channels_owner_update on public.channels;
create policy channels_owner_update on public.channels for update to authenticated using (owner_id=auth.uid());
drop policy if exists channel_members_read on public.channel_members;
create policy channel_members_read on public.channel_members for select to authenticated using (user_id=auth.uid() or exists(select 1 from channels c where c.id=channel_id and c.owner_id=auth.uid()));
drop policy if exists channel_members_join on public.channel_members;
create policy channel_members_join on public.channel_members for insert to authenticated with check (user_id=auth.uid() and role='subscriber');
drop policy if exists channel_members_owner on public.channel_members;
create policy channel_members_owner on public.channel_members for insert to authenticated with check (role='owner' and exists(select 1 from channels c where c.id=channel_id and c.owner_id=auth.uid()));
drop policy if exists channel_members_leave on public.channel_members;
create policy channel_members_leave on public.channel_members for delete to authenticated using (user_id=auth.uid() and role='subscriber');
drop policy if exists channel_posts_read on public.channel_posts;
create policy channel_posts_read on public.channel_posts for select to authenticated using (exists(select 1 from channel_members cm where cm.channel_id=channel_id and cm.user_id=auth.uid()));
drop policy if exists channel_posts_create on public.channel_posts;
create policy channel_posts_create on public.channel_posts for insert to authenticated with check (author_id=auth.uid() and exists(select 1 from channels c where c.id=channel_id and c.owner_id=auth.uid()));
drop policy if exists channel_posts_update on public.channel_posts;
create policy channel_posts_update on public.channel_posts for update to authenticated using (author_id=auth.uid() and exists(select 1 from channels c where c.id=channel_id and c.owner_id=auth.uid()));
drop policy if exists channel_posts_delete on public.channel_posts;
create policy channel_posts_delete on public.channel_posts for delete to authenticated using (author_id=auth.uid() and exists(select 1 from channels c where c.id=channel_id and c.owner_id=auth.uid()));

create or replace function public.create_channel(p_title text,p_username text default null,p_description text default '')
returns uuid language plpgsql security definer set search_path=public as $$
declare cid uuid;
begin
 if auth.uid() is null or length(trim(p_title)) not between 1 and 80 then raise exception 'invalid channel title'; end if;
 if p_username is not null and lower(p_username) !~ '^[a-z0-9_]{3,32}$' then raise exception 'invalid username'; end if;
 insert into channels(owner_id,title,username,description) values(auth.uid(),trim(p_title),lower(p_username),left(p_description,500)) returning id into cid;
 insert into channel_members(channel_id,user_id,role) values(cid,auth.uid(),'owner');
 return cid;
end $$;
grant execute on function public.create_channel(text,text,text) to authenticated;
