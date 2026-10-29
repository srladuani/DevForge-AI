-- How a project's changes are published: the branch pull requests target (empty = the repository's default branch), the prefix of the branch
-- DevForge creates for a ticket, and whether the pull request is opened as a draft.
create table workspace_publish (
    workspace_id  uuid primary key references workspace (id) on delete cascade,
    target_branch text,
    branch_prefix text        not null default 'devforge/',
    draft         boolean     not null default true,
    updated_by    uuid        not null references app_user (id),
    updated_at    timestamptz not null default now()
);
