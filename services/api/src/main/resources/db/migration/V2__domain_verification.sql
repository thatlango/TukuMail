alter table mail_domains add column verification_token varchar(128);
alter table mail_domains add column verified_at timestamp with time zone;

update mail_domains
set verification_token = replace(cast(id as varchar), '-', '')
where verification_token is null;

alter table mail_domains alter column verification_token set not null;
