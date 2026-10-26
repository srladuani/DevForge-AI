-- A sign-in with an external provider that has been started and not yet completed. The state value is the only thing the browser carries
-- back, so it is random, single-use and short-lived, and it is bound to the person who started the flow.
create table oauth_flow (
    state          text primary key,
    user_id        uuid        not null references app_user (id) on delete cascade,
    kind           text        not null,
    provider_type  text        not null,
    label          text        not null,
    code_verifier  text        not null,
    client_id      text        not null,
    token_endpoint text        not null,
    resource       text        not null,
    redirect_uri   text        not null,
    expires_at     timestamptz not null,
    created_at     timestamptz not null default now()
);
create index oauth_flow_expiry_idx on oauth_flow (expires_at);
