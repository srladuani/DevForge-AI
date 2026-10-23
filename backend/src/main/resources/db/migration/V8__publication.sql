-- One row per change that is (being) published to a Git host. It is the intent record: a retried publication finds its row, so a branch or
-- pull request is never created twice.
create table publication (
    id              uuid primary key,
    run_id          uuid        not null,
    change_id       uuid        not null,
    repository_id   uuid        not null,
    repo_name       text        not null,
    host_type       text        not null,
    remote          text        not null,
    branch          text        not null,
    base_branch     text        not null,
    status          text        not null check (status in ('PENDING', 'PUSHED', 'PR_OPEN', 'FAILED')),
    commit_sha      text,
    pr_number       integer,
    pr_url          text,
    failure_code    text,
    failure_message text,
    connection_id   uuid        not null,
    plan_hash       text        not null,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    unique (run_id, change_id)
);
create index publication_run_idx on publication (run_id);

-- Databases created before GitHub was supported get its instance now. A fresh database is seeded from configuration instead (the seed only
-- runs while the table is empty), so nothing is inserted there.
insert into provider_instance (id, kind, adapter_type, display_name)
select gen_random_uuid(), 'GIT_HOST', 'GITHUB', 'GitHub'
where exists (select 1 from provider_instance)
  and not exists (select 1 from provider_instance where kind = 'GIT_HOST' and adapter_type = 'GITHUB');
