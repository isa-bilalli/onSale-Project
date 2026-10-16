create table event (
  id         uuid primary key default gen_random_uuid(),
  name       text        not null,
  starts_at  timestamptz not null,
  status     text        not null check (status in ('ON_SALE','SOLD_OUT')),
  visibility text        not null default 'PUBLIC' check (visibility in ('PUBLIC','BENCHMARK')),
  last_seq   bigint      not null default 0
);
