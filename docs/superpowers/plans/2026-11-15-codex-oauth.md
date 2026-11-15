# Codex OAuth Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Reliable Codex subscription queries with browser OAuth renewal and remaining reset-card counts.

**Architecture:** Typed local OAuth credentials, official device flow, serialized renewal and guarded Store updates. Existing Compose configuration/login overlays and parser extras provide the presentation.

**Tech Stack:** Kotlin, Compose, coroutines, HttpURLConnection, JUnit/Robolectric, GitHub Actions.

**Spec:** ../specs/2026-11-15-codex-oauth-design.md

## Global Constraints

- Android 8+, no new hosted service or client secret.
- Preserve API/webhook accounts and account HTTP/SOCKS5 proxy.
- Never treat absent reset-card data as zero, or expose OAuth secrets in errors.
- Version 1.4.0 / 37, original signing certificate and APK SHA-256.

## Review Focus

- Foreground/widget overlap must not reuse rotating refresh tokens.
- Token rotation must persist even if quota fetch fails.
- Delete/edit/import during a query must reject stale result and credential writes.
- Cancellation and deadline must stop device polling and release connections.
- Missing/null/negative/fractional reset counts must never display a false zero.

### Task 1: Reset-card parsing

- [x] Add parser regressions for available_count 3, 0, missing, null, negative and fractional; run and observe failure.
- [x] Parse into Extra("reset_cards", count); localize the details label and unknown state; retain widget handling.
- [x] Run parser tests.

### Task 2: OAuth protocol and persistence

- [x] Add CodexOAuth tests for request, pending 403/404, PKCE exchange, timeout, cancellation, renewal and redacted failures; run RED.
- [x] Implement CodexOAuth with injectable HTTP transport, fixed official endpoints, bounded reads, cancellation, deadline and proxy.
- [x] Add typed CodexCredentials and Store guarded rotation tests; implement persistent refresh with shared Mutex and one 401 retry.
- [x] Integrate QueryEngine and session-aware result comparison; test rotation despite failure, concurrent use, stale edits and backup.
- [x] Run full unit suite.

### Task 3: UI and release

- [x] Add CodexLoginScreen using the existing login overlay; default new Codex accounts to OAuth LOGIN and retain manual mode.
- [x] Add localized code/copy/browser/retry/reconnect UI and proxy configuration.
- [x] Update README, CHANGELOG and version 1.4.0 / 37.
- [x] Run testDebugUnitTest lintDebug assembleDebug lintRelease assembleRelease; request independent review and fix important findings.
- [ ] Push branch, merge after CI, tag v1.4.0, wait for signed release, verify public APK/checksum and tag target.

## Local verification

- 102 unit tests passed (18 suites, no failures or skips).
- `testDebugUnitTest lintDebug assembleDebug lintRelease assembleRelease --no-daemon` passed. Both lint reports have zero errors/fatal issues and 74 warnings.
- Independent review identified the snapshot-to-engine restore epoch race; its regression failed before the fix and passed in the full suite. Renewal JSON encoding and OAuth JSON-result application also have RED-to-GREEN regressions.
- Local Release uses the development signing fallback; only the existing GitHub CI pipeline will produce the distributable signed release. Real account/device acceptance remains unverified.
