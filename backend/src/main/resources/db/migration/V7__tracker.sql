-- Which issue tracker a project uses (the credential is each user's own connection).
create table workspace_tracker (
    workspace_id  uuid primary key references workspace (id) on delete cascade,
    provider_type text        not null,
    site_hint     text,
    updated_by    uuid        not null references app_user (id),
    updated_at    timestamptz not null default now()
);

-- A run that fetches its ticket remembers which of its owner's tracker connections it used.
alter table run add column tracker_provider text;
alter table run add column tracker_connection_id uuid;
