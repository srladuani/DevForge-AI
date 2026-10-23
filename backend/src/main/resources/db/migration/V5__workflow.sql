create table run (
    id               uuid primary key,
    workspace_id     uuid        not null references workspace (id) on delete cascade,
    type             text        not null,
    mode             text        not null check (mode in ('INVESTIGATE_ONLY', 'PROPOSE_FIX', 'FIX_AND_PATCH')),
    status           text        not null check (status in ('CREATED', 'INPUT_RETRIEVAL', 'AWAITING_INPUT', 'CONTEXT_GATHERING', 'INVESTIGATION', 'FINDINGS_AVAILABLE',
                                                            'PROPOSING_FIX', 'FIX_PROPOSAL_AVAILABLE', 'AWAITING_APPROVAL', 'IMPLEMENTATION', 'VALIDATION',
                                                            'AWAITING_REVIEW', 'PR_PREPARATION', 'COMPLETED', 'FAILED', 'CANCELLED')),
    owner_user_id    uuid        not null references app_user (id),
    title            text        not null,
    notes            text,
    idempotency_key  text,
    request_hash     text        not null,
    agent_provider   text        not null,
    agent_instance_id uuid,
    agent_model      text        not null,
    agent_fast_model text,
    connection_id    uuid,
    simulated        boolean     not null default true,
    await_reason     text check (await_reason in ('CLARIFICATION', 'CREDENTIAL', 'APPROVAL')),
    resume_state     text,
    failure_code     text,
    failure_step     text,
    failure_message  text,
    failure_retryable boolean,
    cancel_requested boolean     not null default false,
    incomplete       boolean     not null default false,
    input_tokens     bigint      not null default 0,
    output_tokens    bigint      not null default 0,
    cost_usd         numeric,
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    version          bigint      not null default 0
);
create unique index run_idempotency_idx on run (workspace_id, idempotency_key) where idempotency_key is not null;
create index run_workspace_status_idx on run (workspace_id, status);
create index run_owner_idx on run (owner_user_id);

create table run_input (
    id         uuid primary key,
    run_id     uuid        not null references run (id) on delete cascade,
    kind       text        not null,
    source_ref text,
    raw_text   text        not null,
    normalized text,
    raw_hash   text        not null,
    created_at timestamptz not null default now()
);
create index run_input_run_idx on run_input (run_id);

create table run_repo (
    run_id        uuid not null references run (id) on delete cascade,
    repository_id uuid not null references repository (id),
    repo_name     text not null,
    repo_role     text not null,
    base_sha      text not null,
    primary key (run_id, repository_id)
);

create table run_step (
    id          uuid primary key,
    run_id      uuid        not null references run (id) on delete cascade,
    name        text        not null,
    attempt     int         not null,
    status      text        not null check (status in ('RUNNING', 'SUCCEEDED', 'FAILED', 'PAUSED', 'CANCELLED')),
    started_at  timestamptz not null default now(),
    finished_at timestamptz,
    detail      text
);
create index run_step_run_idx on run_step (run_id, started_at);

create table run_event (
    id         bigserial primary key,
    run_id     uuid        not null references run (id) on delete cascade,
    seq        int         not null,
    type       text        not null,
    payload    text        not null default '{}',
    created_at timestamptz not null default now(),
    unique (run_id, seq)
);

create table job (
    id          uuid primary key,
    run_id      uuid        not null references run (id) on delete cascade,
    step        text        not null,
    state       text        not null default 'QUEUED' check (state in ('QUEUED', 'LEASED', 'DONE', 'FAILED')),
    attempts    int         not null default 0,
    available_at timestamptz not null default now(),
    lease_until timestamptz,
    locked_by   text,
    created_at  timestamptz not null default now()
);
create index job_claim_idx on job (state, available_at, created_at);

create table evidence (
    id           uuid primary key,
    run_id       uuid        not null references run (id) on delete cascade,
    repository_id uuid       not null,
    repo_name    text        not null,
    sha          text        not null,
    path         text        not null,
    start_line   int         not null,
    end_line     int         not null,
    content_hash text,
    excerpt      text,
    verified     boolean     not null,
    problem      text,
    created_at   timestamptz not null default now()
);
create index evidence_run_idx on evidence (run_id);

create table finding (
    id              uuid primary key,
    run_id          uuid        not null references run (id) on delete cascade,
    step            text        not null,
    label           text        not null check (label in ('VERIFIED', 'SUPPORTED', 'HYPOTHESIS', 'UNKNOWN')),
    requested_label text        not null,
    downgrade_reason text,
    text            text        not null,
    confidence      text        not null,
    created_at      timestamptz not null default now()
);
create index finding_run_idx on finding (run_id);

create table finding_evidence (
    finding_id  uuid not null references finding (id) on delete cascade,
    evidence_id uuid not null references evidence (id) on delete cascade,
    primary key (finding_id, evidence_id)
);

create table run_artifact (
    id           uuid primary key,
    run_id       uuid        not null references run (id) on delete cascade,
    type         text        not null,
    content      text        not null,
    content_hash text        not null,
    current      boolean     not null default true,
    created_at   timestamptz not null default now()
);
create index run_artifact_run_idx on run_artifact (run_id, type);

create table code_change (
    id            uuid primary key,
    run_id        uuid        not null references run (id) on delete cascade,
    repository_id uuid        not null,
    repo_name     text        not null,
    base_sha      text        not null,
    patch         text        not null,
    patch_hash    text        not null,
    files         text        not null,
    simulated     boolean     not null,
    attempt       int         not null,
    created_at    timestamptz not null default now()
);
create index code_change_run_idx on code_change (run_id);

create table validation_run (
    id             uuid primary key,
    run_id         uuid        not null references run (id) on delete cascade,
    code_change_id uuid        not null references code_change (id) on delete cascade,
    repo_name      text        not null,
    command        text,
    status         text        not null check (status in ('PASSED', 'FAILED', 'NOT_RUN', 'SIMULATED')),
    summary        text        not null,
    attempt        int         not null,
    created_at     timestamptz not null default now()
);

create table model_call (
    id            uuid primary key,
    run_id        uuid        not null references run (id) on delete cascade,
    step          text        not null,
    user_id       uuid        not null,
    provider      text        not null,
    model         text        not null,
    input_tokens  bigint      not null,
    output_tokens bigint      not null,
    cost_usd      numeric,
    simulated     boolean     not null,
    created_at    timestamptz not null default now()
);
