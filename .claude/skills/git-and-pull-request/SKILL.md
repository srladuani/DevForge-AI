---
name: git-and-pull-request
description: Prepare a safe working branch, meaningful commits, a patch, and a review-ready, traceable change description for one or more repositories; open a pull request only if a Git-host adapter is configured. Only on explicit user request; pushing and opening PRs are external writes needing confirmation.
argument-hint: "[branch-name or ticket key]"
disable-model-invocation: true
---

# Git & Pull Request

## Purpose
Package validated changes for review with a traceable history. Local git work is routine; **push / PR creation are external writes and require explicit user approval each time.** The Git host is not assumed: the always-available output is a local branch + patch file. A PR is created only through a configured `PullRequestProvider` adapter (Bitbucket, GitHub, GitLab, … — whichever the project has set up).

## Preconditions (verify, don't assume)
- `diff-review` completed with no open BLOCKER/MAJOR.
- `test-and-validation` report exists for the current HEAD of each repo.
- Working tree contains only intended files (`git status`, `git diff --stat`).

## Workflow (repeat per changed repository)
1. **Branch** from the up-to-date base; never commit to protected branches (`main`, `master`, `develop`, `release/*`). Name: `devforge/<ticket-key-or-slug>-<short-desc>`; use the same name across repos in a multi-repo change.
2. **Stage deliberately** by path; no `git add -A`. Exclude secrets, build output, unrelated lockfile churn.
3. **Commit**: imperative subject ≤72 chars; body = why, not what; reference the ticket; one logical change per commit. Include the attribution trailer configured for the session. No `--no-verify`, no force-push, no history rewrite of shared branches.
4. **Patch**: produce `git format-patch`/unified diff as the host-independent deliverable.
5. **Secret scan** the diff before any push (gitleaks if available, else pattern grep) and report the result honestly.
6. **Change description** (draft, show the user):
   - Summary & motivation (link to ticket)
   - Root cause with evidence refs (labelled VERIFIED/SUPPORTED)
   - Changes by file/area; for multi-repo, list sibling changes and required merge order
   - Tests: exact commands and real results; "not run" where true
   - Risks, rollback, follow-ups
   - Reviewer notes: AI-assisted change (provider/model used), areas needing human attention
7. **If a Git-host adapter is configured:** ask approval naming host, remote, source and target branch; on yes push and open the PR via the adapter; cross-link sibling PRs. If none is configured, stop at branch + patch and say so.
8. Report URLs/ids exactly as returned; if a provider call failed, say so.

## Done when
Branch/commits/patch are clean and traceable per repo, the description matches the diff and real test results, and any external action was explicitly approved and verified.

## Security
Never print or embed credentials/tokens. Use the configured credential helper/SSH agent only. Do not alter git config or hooks without being asked. Never target a protected branch or force-push.
