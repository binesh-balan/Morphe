# Remaining hardening

What is still outstanding after the P0 and P1/P2 remediation work, why, and what closing
each one requires. Everything here is deliberate — nothing was silently skipped.

## Requires a build or a running instance

These cannot be done or verified in a static environment.

### Jackson runs with known HIGH advisories on the shipped runtime classpath

Surfaced immediately by the lock state, and invisible before it — the original assessment
queried the *declared* version (`2.22.1`, clean) rather than the *resolved* one.

`:stirling-pdf` (`app/core`) is the shipped application, and its
`productionRuntimeClasspath` resolves:

| Component | Resolved | Advisories | Fixed in |
|---|---|---|---|
| `com.fasterxml.jackson.core:jackson-databind` | **2.21.2** | 2 HIGH, 8 MODERATE | 2.21.4 / 2.22.x |
| `tools.jackson.core:jackson-databind` (Jackson 3) | **3.1.2** | 2 HIGH, 7 MODERATE | 3.1.4 |

The two HIGH issues are `GHSA-j3rv-43j4-c7qm` (PolymorphicTypeValidator bypass via generic
types) and `GHSA-rmj7-2vxq-3g9f` (array subtype allowlist bypass in
`BasicPolymorphicTypeValidator`) — both polymorphic-deserialization escapes.

**The existing `resolutionStrategy.force` for jackson-databind is not taking effect.**
`build.gradle:257` forces `${jackson2Version}` (2.22.1) inside
`subprojects { configurations.configureEach { … } }`, but only `:proprietary` resolves
2.22.1 at runtime — and it does so because `app/proprietary/build.gradle:45` declares
`runtimeOnly` explicitly, not because of the force. `:stirling-pdf` and `:common` resolve
2.21.2 on every configuration.

Fixing it needs two things, and both want a CI run because Spring Boot 4 manages Jackson
through its BOM:

1. Replace the ineffective `force` with a mechanism that actually applies — a
   `resolutionStrategy.eachDependency` rule, or dependency constraints using `strictly`.
2. Bump `tools.jackson.core:jackson-databind` to 3.1.4. It comes from the Spring Boot BOM,
   so check whether a Spring Boot patch release already carries it before forcing it.

Regenerate the lock state afterwards and confirm with OSV.

### Generate Gradle lock state

**Done.** Lock state committed for the root project and all three modules.

Locking runs in `LockMode.LENIENT`, not the default STRICT, because the build has three
flavours that resolve different dependency sets — `core`
(`DISABLE_ADDITIONAL_FEATURES=true`), `proprietary`, and `saas`. Lock state is
per-configuration, not per-flavour, so STRICT fails the `core` flavour with *"Did not
resolve &lt;x&gt; which is part of the dependency lock state"* for every security/SAML/JPA
module the wider flavours pull in.

LENIENT still pins every version the lock records, which is what makes the tree
reproducible and scannable. The trade-off: a **new** dependency absent from the lock state
no longer fails the build. Regenerate and review the lockfile diff whenever dependencies
change.

```bash
./gradlew resolveAndLockAll --write-locks
git add '**/gradle.lockfile' && git commit -m "build: commit Gradle dependency lock state"
```

Re-run and commit whenever a dependency changes. The `sbom` job in `security-scan.yml`
warns if the SBOM comes back with fewer than 50 Maven components.

### Validate the Content-Security-Policy

`SecurityHeadersFilter` ships a strict default policy that has never been exercised against
a browser. Run with `morphe.security.csp.report-only=true`, load every tool page, confirm
the console reports no violations, then set it back to `false`. Swagger UI is the most
likely thing to trip it, since `script-src` carries no `'unsafe-inline'`.

### Runtime network and process observation

Phases 18–19 of the assessment were never run. The air-gap conclusion rests on static
analysis: every runtime outbound call is behind a flag that is off by default, and the
container entrypoint contains no network calls. Confirm it empirically — run the built
image on an isolated network with egress denied, exercise the tools, and capture traffic.

### Pin the Calibre download

`docker/base/Dockerfile` verifies Ghostscript (sha512), QPDF (sha256) and ImageMagick
(sha256), but Calibre is still fetched unverified — its published checksum could not be
retrieved when the pins were added. Calibre ships per-architecture, so this needs two
values. To close it:

```bash
curl -fsSLO https://download.calibre-ebook.com/9.4.0/calibre-9.4.0-x86_64.txz
sha256sum calibre-9.4.0-x86_64.txz     # repeat for the arm64 asset
```

Then add `CALIBRE_SHA256_X86_64` / `CALIBRE_SHA256_ARM64` build args and a `sha256sum -c`
step, matching the pattern already used for the other three. Prefer verifying Calibre's
GPG signature if you can establish the signing key out of band.

Worth checking first whether you need Calibre at all — the entrypoint currently logs
`"issue with calibre in current version, feature currently disabled"`, so the advanced
HTML/ebook path it supports may be dead weight. Dropping it removes a large parser from
the attack surface entirely.

## Cannot be fixed upstream yet

### `extract-zip` 2.0.1 — GHSA-jmr9-qjv8-65gv (High)

Symlink path traversal. **2.0.1 is the latest published version — no fix exists.** Reached
only through `@puppeteer/browsers`, a development dependency; Trivy's production scan does
not report it. It never ships. Re-check when upstream publishes a fix.

### `fast-uri` 3.1.4 — GHSA-7p8r-x3mc-p8w7 (High)

Host confusion via backslash authority. Fixed in 4.x, but it is pulled in as `^3.0.1` by
`table` → `ajv` (ESLint tooling). Forcing 4.x through an `overrides` entry crosses a major
version with API changes, to patch a dev-only lint dependency. Not worth the breakage.
Re-check when `ajv` widens its range.

Both are dev-only. The two advisories that reached production (`nanoid` CVE-2026-67213 and
`react-router` GHSA-qwww-vcr4-c8h2) are fixed.

## Deliberate design decisions

### P0 #3 — session JWT still in `localStorage`

The largest open item. Full plan in
[`P0-3-jwt-cookie-migration.md`](./P0-3-jwt-cookie-migration.md). Deferred because it needs
a working build and real SSO round trips to verify. Mitigated but not closed: the PDF
JavaScript execution path (the one known route to the token) is gone, and CSP restricts
`connect-src` to `'self'`.

### Cross-Origin-Opener-Policy is not set

`same-origin` severs `window.opener` and breaks OAuth/SSO popup flows. Set it at the
reverse proxy only after confirming your identity provider uses full-page redirects rather
than a popup.

### Committed test certificates and H2 fixtures are kept

The assessment suggested deleting them. That was over-reach — they are throwaway fixtures
under `src/test/resources` that real tests depend on, they are referenced only from `.spec`
and `.test` files, and removing them breaks the suite for no security gain.

### GitHub Actions are currently disabled on the fork

Disabled to stop the inherited `push-docker.yml` publishing a container image to ghcr.io on
push to `main`. That workflow and 16 other publish/deploy/sync workflows have since been
removed, so Actions can be re-enabled:

```bash
gh api -X PUT repos/binesh-balan/Morphe-PDF/actions/permissions -F enabled=true
```

### Enable GitHub's Dependency graph

`dependency-review` is currently non-blocking because the action fails outright with
*"Dependency review is not supported on this repository"* — Dependency graph is off by
default on forks and can only be enabled in the UI
(**Settings > Code security > Dependency graph**); there is no API for it.

Dependency vulnerability coverage does not depend on this — `security-scan.yml` runs
OSV-Scanner across every lockfile and needs no repo setting. But once Dependency graph is
on, drop `continue-on-error` from `.github/workflows/dependency-review.yml` so a PR that
introduces a vulnerable dependency is blocked at review time.

Enabling it also unlocks Dependabot security updates, currently `disabled`.

## Not yet started

- **Entra ID wiring.** OIDC and SAML2 are both supported by the application; nothing is
  configured. Enforce MFA at the IdP — Morphe-PDF has none of its own.
- **Parser sandboxing.** Ghostscript, LibreOffice, ImageMagick and Calibre are the largest
  inherent attack surface, and it is inherent rather than a code defect. Run them in a
  network-less sidecar with a tailored seccomp profile.
- **Image signing and admission control.** Sign built images (cosign) and require valid
  signatures at deploy time.
- **Branch protection** on `main`, requiring the `security-scan` and `all-checks-passed`
  gates.
- **Make the secret-scan gate blocking.** `security-scan.yml` runs gitleaks with
  `--exit-code 0`, so it reports without failing. A full-history scan surfaces ~84 findings,
  all of the false-positive classes verified during the assessment (interactive
  "enter the password:" prompts, PostHog's public `phc_` key, test fixtures) — failing every
  run would train people to ignore the job. Generate a baseline of the accepted findings,
  commit it, then add `--baseline-path` with `--exit-code 1` so only *new* secrets fail.
- **Turn the dependency gate hard.** `security-scan.yml` currently reports high/critical
  advisories without failing. Once the two dev-only advisories above are resolved or
  formally accepted, uncomment the `raise SystemExit` so new ones cannot land unnoticed.
