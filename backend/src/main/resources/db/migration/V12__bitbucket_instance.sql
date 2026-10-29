-- Databases created before Bitbucket Cloud was supported get its provider instance now (a fresh database is seeded from configuration instead,
-- and only while the table is empty).
insert into provider_instance (id, kind, adapter_type, display_name)
select gen_random_uuid(), 'GIT_HOST', 'BITBUCKET_CLOUD', 'Bitbucket Cloud'
where exists (select 1 from provider_instance)
  and not exists (select 1 from provider_instance where kind = 'GIT_HOST' and adapter_type = 'BITBUCKET_CLOUD');
