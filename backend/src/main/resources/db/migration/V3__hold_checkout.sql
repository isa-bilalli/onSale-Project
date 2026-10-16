-- How many seats a hold claimed. Checkout must find exactly this many still HELD by the hold;
-- fewer means a seat expired and was taken by another buyer in the meantime.
alter table hold add column seat_count integer;
update hold h set seat_count = (select count(*) from seat s where s.hold_id = h.id);
alter table hold alter column seat_count set not null;

alter table hold add column checkout_key text;
alter table hold add column confirmed_at timestamptz;
alter table hold add constraint hold_confirmed_has_key
  check (status <> 'CONFIRMED' or (checkout_key is not null and confirmed_at is not null));

-- the sweeper only ever looks for active holds past their expiry
create index hold_active_expires_at_idx on hold (expires_at) where status = 'ACTIVE';
