-- Admin-editable provider instances. Code defines adapter *types*; rows here configure *instances* of them.
create table provider_instance (
    id           uuid primary key,
    kind         text        not null check (kind in ('AI_PROVIDER', 'GIT_HOST', 'TRACKER')),
    adapter_type text        not null,
    display_name text        not null,
    base_url     text,
    enabled      boolean     not null default true,
    model_policy text        not null default '{}',
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    unique (kind, adapter_type, display_name)
);
