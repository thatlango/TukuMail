# DNS contract

Example production hostname: `mail.tukutuku.org`.

## Mail host

```text
mail.tukutuku.org.      A      <MAIL_VPS_IPV4>
```

The VPS provider must set reverse DNS/PTR for the public IPv4 back to `mail.tukutuku.org`. Forward A and reverse PTR should agree.

## Domain ownership

TukuMail no longer trusts a client-supplied `verified=true` flag. Registering a domain returns a `verificationToken`. Publish:

```text
_tukumail-verification.<domain>. TXT "tukumail-verification=<verificationToken>"
```

Then call the domain verify endpoint. TukuMail resolves that TXT record itself and only then activates the domain in the mail engine.

## Mail delivery records

For the first `tukutuku.org` pilot:

```text
tukutuku.org.           MX 10  mail.tukutuku.org.
tukutuku.org.           TXT    "v=spf1 mx -all"
_dmarc.tukutuku.org.    TXT    "v=DMARC1; p=quarantine; rua=mailto:dmarc@tukutuku.org"
<selector>._domainkey.tukutuku.org. TXT "v=DKIM1; k=rsa; p=<PUBLIC_KEY>"
```

Every customer domain gets its own ownership TXT challenge plus MX, SPF, DKIM and DMARC records. Do not activate outbound production sending for a customer domain until those records and the mail-host PTR/TLS checks are green.
