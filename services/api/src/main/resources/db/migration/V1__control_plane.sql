create table organizations (
  id uuid primary key,
  name varchar(200) not null,
  slug varchar(64) not null unique,
  plan varchar(64) not null,
  mailbox_limit integer not null,
  storage_limit_gb integer not null,
  active boolean not null default true
);
create table mail_domains (
  id uuid primary key,
  organization_id uuid not null references organizations(id),
  name varchar(253) not null unique,
  verified boolean not null default false
);
create table mailboxes (
  id uuid primary key,
  organization_id uuid not null references organizations(id),
  address varchar(320) not null unique,
  display_name varchar(200) not null,
  quota_gb integer not null,
  status varchar(32) not null,
  staff_ref varchar(200),
  created_at timestamp with time zone not null
);
create index idx_mailboxes_org on mailboxes(organization_id);
create index idx_domains_org on mail_domains(organization_id);
