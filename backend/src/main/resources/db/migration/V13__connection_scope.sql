-- Which repositories a Git-host credential may be used for: null = any repository on that host, 'owner/name' = one repository,
-- 'owner/*' = every repository of that owner or workspace. Lets a project that spans several repositories use a narrowly-scoped token per repository.
alter table connection add column scope text;
