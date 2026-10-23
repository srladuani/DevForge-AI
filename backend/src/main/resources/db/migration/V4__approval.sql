-- An approval is bound to the exact artifact (by hash) it was requested for; any change to that artifact invalidates it.
create table approval (
    id            uuid primary key,
    workspace_id  uuid        not null references workspace (id) on delete cascade,
    run_id        uuid        not null,
    kind          text        not null check (kind in ('IMPLEMENT', 'PUSH', 'PR', 'TRACKER_WRITE')),
    artifact_hash text        not null,
    summary       text        not null default '{}',
    status        text        not null default 'PENDING' check (status in ('PENDING', 'APPROVED', 'REJECTED', 'SUPERSEDED', 'EXPIRED')),
    requested_by  uuid        not null references app_user (id),
    decided_by    uuid references app_user (id),
    decided_at    timestamptz,
    comment       text,
    expires_at    timestamptz not null,
    created_at    timestamptz not null default now()
);
create index approval_run_idx on approval (run_id);
create index approval_workspace_idx on approval (workspace_id, status);
