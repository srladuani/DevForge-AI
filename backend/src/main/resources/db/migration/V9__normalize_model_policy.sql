-- Rows inserted with the column default '{}' (the GitHub instance added for existing databases) get the full empty policy, so every
-- client sees the same shape.
update provider_instance
set model_policy = '{"allow":[],"deny":[],"tierOverrides":{},"customModels":[]}'
where model_policy = '{}';

alter table provider_instance alter column model_policy set default '{"allow":[],"deny":[],"tierOverrides":{},"customModels":[]}';
