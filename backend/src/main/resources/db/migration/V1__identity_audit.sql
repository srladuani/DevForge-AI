create table app_user (
    id            uuid primary key,
    email         text        not null unique,
    display_name  text        not null,
    admin         boolean     not null default false,
    created_at    timestamptz not null default now()
);

-- A user's own external credential (AI provider, Git host, tracker). The secret is stored only as AES-GCM ciphertext.
create table connection (
    id               uuid primary key,
    user_id          uuid        not null references app_user (id) on delete cascade,
    kind             text        not null check (kind in ('AI_PROVIDER', 'GIT_HOST', 'TRACKER')),
    provider_type    text        not null,
    auth_method      text        not null check (auth_method in ('API_KEY', 'PAT', 'OAUTH')),
    label            text        not null,
    encrypted_secret bytea       not null,
    key_version      int         not null,
    status           text        not null default 'UNVERIFIED' check (status in ('UNVERIFIED', 'VALID', 'INVALID', 'QUOTA')),
    status_message   text,
    last_verified_at timestamptz,
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now()
);
create index connection_user_idx on connection (user_id);

create table audit_event (
    id          bigserial primary key,
    ts          timestamptz not null default now(),
    actor       text        not null,
    action      text        not null,
    target_type text,
    target_id   text,
    details     text
);
create index audit_event_ts_idx on audit_event (ts desc);

create function audit_event_immutable() returns trigger as $$
begin
    raise exception 'audit_event is append-only';
end;
$$ language plpgsql;

create trigger audit_event_no_update before update or delete on audit_event
    for each row execute function audit_event_immutable();
