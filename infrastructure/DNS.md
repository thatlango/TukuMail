# DNS contract

Example hostname: `mail.tukutuku.org`.

Production DNS must include:

```text
mail.tukutuku.org.      A      <MAIL_VPS_IPV4>
tukutuku.org.           MX 10  mail.tukutuku.org.
tukutuku.org.           TXT    "v=spf1 mx -all"
_dmarc.tukutuku.org.    TXT    "v=DMARC1; p=quarantine; rua=mailto:dmarc@tukutuku.org"
<selector>._domainkey.tukutuku.org. TXT "<TUKUMAIL_DKIM_PUBLIC_KEY>"
```

The VPS provider must set reverse DNS/PTR for the public IP back to `mail.tukutuku.org`. Forward A and reverse PTR should agree.

Customer domains receive their own MX, SPF, DKIM and DMARC records. Domain verification is a control-plane responsibility; the current v0 trusted provisioning endpoint can mark a domain verified only after operations has checked the records. Self-service TXT challenge verification is a follow-up hardening item.
