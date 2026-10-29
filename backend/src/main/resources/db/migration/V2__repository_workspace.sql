create table repository (
    id             uuid primary key,
    name           text        not null,
    local_path     text        not null unique,
    default_branch text,
    head_sha       text,
    profile        text,       -- JSON; derived data, cached per head_sha
    profile_sha    text,
    created_at     timestamptz not null default now()
);

create table workspace (
    id         uuid primary key,
    name       text        not null,
    kind       text        not null check (kind in ('SUPPORT', 'NEW_PROJECT')),
    policy     text        not null default '{}',
    created_by uuid        not null references app_user (id),
    created_at timestamptz not null default now(),
    version    bigint      not null default 0
);

create table workspace_member (
    workspace_id uuid not null references workspace (id) on delete cascade,
    user_id      uuid not null references app_user (id) on delete cascade,
    role         text not null check (role in ('VIEWER', 'OPERATOR', 'APPROVER', 'ADMIN')),
    primary key (workspace_id, user_id)
);

create table workspace_repo (
    workspace_id  uuid not null references workspace (id) on delete cascade,
    repository_id uuid not null references repository (id),
    role          text not null check (role in ('BACKEND', 'WEB_FRONTEND', 'MOBILE', 'LIBRARY', 'OTHER')),
    hints         text not null default '{}',
    added_at      timestamptz not null default now(),
    primary key (workspace_id, repository_id)
);
