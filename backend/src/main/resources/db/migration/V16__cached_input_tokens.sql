-- The part of a model call's input that the provider served from its prompt cache (billed at a fraction of normal input). input_tokens still
-- counts everything the model read, so existing budgets and totals mean the same as before.
alter table model_call add column cached_input_tokens bigint not null default 0;
