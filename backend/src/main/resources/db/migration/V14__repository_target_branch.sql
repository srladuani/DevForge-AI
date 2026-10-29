-- The branch pull requests for this repository go into, within this project (empty = the project's target branch, else the repository's default branch on the host).
-- It lives on the project's link to the repository, not on a credential: one token can serve repositories that merge into different branches.
alter table workspace_repo add column target_branch text;
