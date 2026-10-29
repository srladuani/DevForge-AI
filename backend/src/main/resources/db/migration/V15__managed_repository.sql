-- A repository DevForge cloned itself from a URL (as opposed to a checkout someone registered by path). One clone per remote, shared by every
-- project that uses it; whoever adds or refreshes it proves access with their own Git-host connection. remote_url is the plain https address,
-- never with a credential in it.
alter table repository add column remote_url text;
alter table repository add column managed boolean not null default false;
create unique index repository_remote_url_idx on repository (lower(remote_url)) where managed;
