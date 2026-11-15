# Codex OAuth and reset cards

The requested release replaces fragile manual-token setup with browser device OAuth and automatic renewal, and displays the remaining Codex reset cards. Keep Kotlin/Compose, Android 8+, the existing HTTP/SOCKS5 account proxy, and existing manual/API and webhook accounts.

## Approach

Use the official open-source Codex device authorization protocol rather than a mobile loopback callback or WebView Cookie capture. The app shows a short-lived code, opens the fixed official verification URL in the system browser, polls with cancellation and a 15-minute deadline, then exchanges the authorization code using its PKCE verifier. No client secret or developer server is needed.

Store a typed OAuth credential in QueryConfig (session ID, access/refresh tokens, account ID and expiry), preserving it in the existing local storage and explicit credential-bearing backup format. Serialize refreshes across foreground/widget clients. Reload the latest session before refreshing; persist rotating tokens immediately, even when the subsequent quota query fails. Compare query configurations by session identity when accepting results so token renewal does not discard successful queries. Edits, deletion, restore and reauthorization must still reject stale writes.

Read-only usage requests retry once after HTTP 401 with a refreshed credential. Refresh failures require reconnecting; do not expose response bodies or tokens. OAuth uses fixed HTTPS endpoints and disables redirects. Account proxies apply to device code, exchange, refresh and usage. Legacy API mode remains available, without automatic renewal for a bare access token.

Implementation review clarified that the restore epoch travels with the atomic query snapshot as a nonserialized lease; acquiring it only inside QueryEngine leaves a scheduling race. A bounded, cancellation-safe refresh section synchronously commits rotating credentials. Ordinary edits preserve the latest credential for the same session; explicit reauthorization creates a new session. Code exchange uses a form and renewal uses JSON, matching the official client.

Parse only rate_limit_reset_credits.available_count as a nonnegative integer, retain explicit zero and distinguish missing/invalid values. Show the count in account details and the existing wide-widget badge. Do not consume reset cards.

## Verification and release

Cover device polling, timeout/cancel/denial, PKCE exchange, renewal/rotation, 401 recovery, proxy transport, concurrent foreground/widget refresh, backup round-trip and reset-count parsing. Run the complete unit suite, Debug/Release Lint and APK builds. Review before merging. Bump to 1.4.0 (code 37); push, tag and use the existing CI signing/certificate/checksum release pipeline. A real user login and physical Android acceptance remain environment-dependent and must be reported accurately.

Sources: [device OAuth](https://github.com/openai/codex/blob/main/codex-rs/login/src/device_code_auth.rs), [token renewal](https://github.com/openai/codex/blob/main/codex-rs/login/src/auth/manager.rs), [reset credits](https://github.com/openai/codex/blob/main/codex-rs/backend-client/src/client/rate_limit_resets.rs).
