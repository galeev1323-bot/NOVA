-- NOVA 2.3.0: asynchronous chess challenges.
create table if not exists public.chess_games (
  id uuid primary key default gen_random_uuid(),
  white_player uuid not null references auth.users(id) on delete cascade,
  black_player uuid not null references auth.users(id) on delete cascade,
  fen text not null default 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1',
  status text not null default 'pending'
    check (status in ('pending','active','white_won','black_won','draw','cancelled')),
  last_move text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check (white_player <> black_player)
);
create index if not exists chess_games_white_idx on public.chess_games(white_player, updated_at desc);
create index if not exists chess_games_black_idx on public.chess_games(black_player, updated_at desc);

alter table public.chess_games enable row level security;
drop policy if exists chess_games_read_players on public.chess_games;
create policy chess_games_read_players on public.chess_games
  for select to authenticated
  using (white_player = auth.uid() or black_player = auth.uid());
revoke all on public.chess_games from anon, authenticated;
grant select on public.chess_games to authenticated;

create or replace function public.create_chess_game(p_username text)
returns uuid language plpgsql security definer set search_path = public as $$
declare v_opponent uuid; v_game uuid;
begin
  if auth.uid() is null then raise exception 'not_authenticated'; end if;
  if lower(trim(coalesce(p_username,''))) !~ '^[a-z0-9_]{3,32}$' then
    raise exception 'invalid_username';
  end if;
  select id into v_opponent from public.profiles where username = lower(trim(p_username));
  if v_opponent is null then raise exception 'opponent_not_found'; end if;
  if v_opponent = auth.uid() then raise exception 'cannot_play_self'; end if;
  insert into public.chess_games(white_player, black_player)
  values(auth.uid(), v_opponent) returning id into v_game;
  return v_game;
end;
$$;
revoke all on function public.create_chess_game(text) from public;
grant execute on function public.create_chess_game(text) to authenticated;

create or replace function public.get_my_chess_games()
returns setof public.chess_games
language sql stable security definer set search_path = public as $$
  select * from public.chess_games
  where white_player = auth.uid() or black_player = auth.uid()
  order by updated_at desc
  limit 50;
$$;
revoke all on function public.get_my_chess_games() from public;
grant execute on function public.get_my_chess_games() to authenticated;

create or replace function public.accept_chess_game(p_game_id uuid)
returns void language plpgsql security definer set search_path = public as $$
begin
  update public.chess_games
  set status = 'active', updated_at = now()
  where id = p_game_id and black_player = auth.uid() and status = 'pending';
  if not found then raise exception 'game_not_available'; end if;
end;
$$;
revoke all on function public.accept_chess_game(uuid) from public;
grant execute on function public.accept_chess_game(uuid) to authenticated;

create or replace function public.submit_chess_move(
  p_game_id uuid,
  p_expected_fen text,
  p_new_fen text,
  p_move_uci text,
  p_result text default 'active'
)
returns void language plpgsql security definer set search_path = public as $$
declare g public.chess_games%rowtype;
begin
  if auth.uid() is null then raise exception 'not_authenticated'; end if;
  if p_expected_fen is null or length(p_expected_fen) > 120
     or p_new_fen is null or length(p_new_fen) > 120
     or p_move_uci !~ '^[a-h][1-8][a-h][1-8][qrbn]?$'
     or p_result not in ('active','white_won','black_won','draw') then
    raise exception 'invalid_move_payload';
  end if;
  select * into g from public.chess_games where id = p_game_id for update;
  if not found or g.status <> 'active' then raise exception 'game_not_active'; end if;
  if g.fen <> p_expected_fen then raise exception 'position_outdated'; end if;
  if (split_part(g.fen, ' ', 2) = 'w' and g.white_player <> auth.uid())
     or (split_part(g.fen, ' ', 2) = 'b' and g.black_player <> auth.uid()) then
    raise exception 'not_your_turn';
  end if;
  -- Move legality is checked on-device with Chesslib. The expected-FEN compare
  -- and row lock prevent stale/duplicate submissions from overwriting a newer turn.
  update public.chess_games
  set fen = p_new_fen, last_move = p_move_uci, status = p_result, updated_at = now()
  where id = p_game_id;
end;
$$;
revoke all on function public.submit_chess_move(uuid,text,text,text,text) from public;
grant execute on function public.submit_chess_move(uuid,text,text,text,text) to authenticated;

create or replace function public.resign_chess_game(p_game_id uuid)
returns void language plpgsql security definer set search_path = public as $$
declare g public.chess_games%rowtype;
begin
  select * into g from public.chess_games where id = p_game_id for update;
  if not found or g.status <> 'active' then raise exception 'game_not_active'; end if;
  if g.white_player = auth.uid() then
    update public.chess_games set status='black_won', updated_at=now() where id=p_game_id;
  elsif g.black_player = auth.uid() then
    update public.chess_games set status='white_won', updated_at=now() where id=p_game_id;
  else
    raise exception 'not_a_player';
  end if;
end;
$$;
revoke all on function public.resign_chess_game(uuid) from public;
grant execute on function public.resign_chess_game(uuid) to authenticated;
