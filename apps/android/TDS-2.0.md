# TukuMail Android — TDS 2.0 native mapping

Source baseline: `thatlango/tuku-shared-language@33a67d4a2d47520e12ffe41712e1712bcdc89fba`.

TukuMail Android uses Material 3 for Android ergonomics only. TDS 2.0 remains authoritative for visual semantics.

- Product accent: `tukumail`.
- Normal touch target: 48dp.
- Use TDS semantic roles for background, surface, text, border, success, warning, danger and info.
- Do not introduce gradients, glassmorphism, decorative glow or arbitrary product/status colours.
- Use restrained TDS geometry: 4dp / 6dp / 10dp / 14dp / 20dp as appropriate.
- Offline/loading/error/empty/denied states must be explicit.
- Typography maps to IBM Plex Sans / IBM Plex Mono once the app has a bundled font resource layer.
- When Compose theme code is introduced, define one TukuMailTheme mapping rather than styling individual screens directly.

No theme implementation is fabricated here because the current Android tree has no indexed Compose theme implementation to safely replace.
