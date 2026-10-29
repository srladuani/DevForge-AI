-- Workers only claim jobs of the queue they serve. Single deployments use 'default'; tests and future routing use other names.
alter table job add column queue text not null default 'default';
drop index if exists job_claim_idx;
create index job_claim_idx on job (queue, state, available_at, created_at);
