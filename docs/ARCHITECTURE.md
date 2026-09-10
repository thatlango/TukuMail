# TukuMail architecture

## Principle

TukuMail owns the customer and organisation control plane. RFC-heavy mail protocols sit behind an explicit engine interface so upstream protocol libraries remain replaceable.

## Runtime

```text
ImpactOS / Admin / Clients
          |
      TukuMail API
          |
   Tuku Mail Engine
          |
 SMTP / IMAP / delivery
          |
       storage
```

## Identity

A Tuku Core user, an organisation membership and a mailbox are separate entities. Shared mailboxes and aliases never masquerade as users.

## Client design language

- Material 3 foundations and accessible contrast.
- Quiet surfaces, restrained elevation and generous spacing.
- No persistent oversized brand hero inside operational screens.
- Dense information is progressively disclosed.
- Desktop uses a navigation rail/sidebar; mobile uses a solid bottom navigation bar.
- Compose is a primary action and remains reachable with one interaction.
- Network failures preserve drafts and clearly show sync state.

## Protocol strategy

SMTP and IMAP remain compatibility protocols. The control plane never exposes Apache James implementation details. Web and future clients may gain a Tuku message API/JMAP-compatible facade without changing account provisioning.
