create table hold (
  id         uuid primary key,
  event_id   uuid        not null references event (id),
  status     text        not null default 'ACTIVE' check (status in ('ACTIVE','CONFIRMED','EXPIRED')),
  created_at timestamptz not null default now(),
  expires_at timestamptz not null
);

create table seat (
  id         uuid primary key default gen_random_uuid(),
  event_id   uuid        not null references event (id),
  label      text        not null,
  status     text        not null default 'AVAILABLE' check (status in ('AVAILABLE','HELD','SOLD')),
  hold_id    uuid        references hold (id),
  held_until timestamptz,
  unique (event_id, label),
  -- a HELD seat must always say who holds it and until when
  check (status <> 'HELD' or (hold_id is not null and held_until is not null))
);

create index seat_hold_id_idx on seat (hold_id);
