# P0 #3 — Session JWT moved out of `localStorage` into an HttpOnly cookie

**Status: implemented, not verified.** The code is written and parses; none of it has been
exercised against a browser, an identity provider, or the test suite. Treat this document as
the test plan, not as a record of something already proven.

## What changed

The backend now issues the session JWT as an `HttpOnly; Secure; SameSite=Lax` cookie and
accepts it on the way back in. Web builds no longer keep the token in `localStorage`. The
desktop client and programmatic API callers keep using bearer tokens.

`SameSite` is `Lax`, not `Strict`, deliberately: `Strict` omits the cookie on the cross-site
navigation back from an identity provider, so the first request after an Entra ID or SAML
redirect arrives unauthenticated and loops the login. `Lax` still withholds the cookie from
cross-site POST and XHR, which is the property that matters.

### Backend

| File | Change |
|---|---|
| `common/constants/JwtConstants.java` | `JWT_COOKIE_NAME` |
| `security/service/JwtServiceInterface.java` | Declares `addTokenToResponse` / `clearTokenCookie` |
| `security/service/JwtService.java` | Reads the cookie first, then falls back to the `Authorization` header; builds the cookie via `ResponseCookie`; `Secure` overridable through `morphe.security.jwt.cookie.secure` |
| `security/controller/api/AuthController.java` | Cookie set on login and refresh, expired on logout |
| `security/CustomAuthenticationSuccessHandler.java` | Form login previously minted a JWT and discarded it; it is now delivered as a cookie |
| `security/oauth2/CustomOAuth2AuthenticationSuccessHandler.java` | Cookie set before the redirect |
| `security/saml2/CustomSaml2AuthenticationSuccessHandler.java` | Cookie set before the redirect |
| `security/configuration/SecurityConfiguration.java` | CSRF configuration added behind a flag (see below) |

The change is **additive**: `access_token` still appears in the login and refresh response
bodies, and the OAuth/SAML redirect still carries the token in its URL fragment. Nothing that
worked before stops working, which is what makes it safe to deploy ahead of the frontend
flip. Fragments are not sent to servers and do not appear in proxy logs, though they do enter
browser history — remove the fragment once you have confirmed no client depends on it.

### Frontend

New `core/services/authTransport.ts` is the single place that knows how the session is
carried. `isCookieMode()` is true for web builds and false for desktop (detected via
`__TAURI_INTERNALS__`); override with `VITE_AUTH_COOKIE_MODE=false` to fall back to the old
behaviour.

In cookie mode `getToken()` returns `null`, so no `Authorization` header is attached and the
browser's cookie carries the session. Every client already sets `withCredentials`.

Because an HttpOnly cookie is invisible to script, "is the user logged in?" can no longer be
answered by looking for the token. A non-sensitive `stirling_session` marker replaces it —
it carries no credential and is a UI hint only. The server remains the authority.

Rewired: `httpClient.ts`, `apiClientSetup.ts`, `useRequestHeaders.ts`, `springAuthClient.ts`
(13 sites), `AuthCallback.tsx`, `ErrorBoundary.tsx`, `LoginAgreementModal.tsx`,
`useOnboardingOrchestrator.ts`, `httpErrorHandler.ts`. Desktop modules are untouched.

Two behavioural traps handled while rewiring:

- The 401 auto-refresh path was gated on a readable token, so in cookie mode it would never
  have fired. It now gates on `hasSession()`.
- The refresh handler treated a missing body token as an error. In cookie mode the refreshed
  cookie is already on the response, so an absent body token is no longer fatal.

### CSRF — added but **off by default**

`morphe.security.csrf.enabled` (default `false`). Cookie-borne credentials are sent
automatically by the browser, which reintroduces CSRF exposure that bearer tokens did not
have. `SameSite=Lax` already blocks the cross-site POST and XHR vector; token-based CSRF is
defence in depth on top of that.

It ships disabled because enabling it is not a safe unattended change:

- the SAML assertion-consumer endpoint receives a legitimate **cross-site POST** from the
  identity provider and must stay exempt (`/login/saml2/sso/**` and `/saml2/**` are already
  in the ignore list);
- any client authenticating with `X-API-KEY` cannot present a CSRF token.

The frontend side already works — `apiClientSetup.ts` reads the `XSRF-TOKEN` cookie and sends
`X-XSRF-TOKEN`, and that header is already in the CORS allow-list. Turn the flag on, then run
the SAML and API-key checks below.

## Test plan

Nothing below is verifiable by inspection. Run all of it.

1. **Form login.** Confirm `Set-Cookie` carries `HttpOnly; Secure; SameSite=Lax`, and that
   `localStorage.getItem("stirling_jwt")` is `null` in a web build.
2. **Authenticated calls.** Confirm requests succeed with **no** `Authorization` header.
3. **Refresh.** Let the token expire; confirm auto-refresh fires and the cookie is reissued.
4. **Logout.** Confirm the cookie is expired server-side, then replay the old cookie and
   expect `401`. Clearing client state alone is not sufficient.
5. **Entra ID (OIDC) round trip.** This is where the `SameSite` trap surfaces. Confirm login
   completes and lands authenticated.
6. **SAML round trip.** Same, and confirm the IdP's cross-site POST to the ACS endpoint is
   not rejected.
7. **Desktop app** against a remote server — bearer auth must be unchanged.
8. **API key and MCP chains** — must be unaffected.
9. **With `morphe.security.csrf.enabled=true`:** a cross-site POST without `X-XSRF-TOKEN` is
   rejected; the app's own requests still succeed; SAML login still completes; API-key callers
   still work.
10. **Existing suites.** `springAuthClient.test.ts`, `api-keys-ui.spec.ts`, and
    `core/tests/helpers/stub-test-base.ts` — line 72 seeds `stirling_jwt` directly to
    authenticate. That no longer authenticates a web build in cookie mode; the helper needs
    to either set the `stirling_session` marker and a real cookie, or force
    `VITE_AUTH_COOKIE_MODE=false` for stubbed runs. **Expect this to fail until updated.**

## Rollback

Set `VITE_AUTH_COOKIE_MODE=false` and rebuild the frontend. The backend change is additive
and needs no rollback — it keeps returning `access_token` in the body and accepting bearer
headers.

## Definition of done

- No web-build code path reads or writes `stirling_jwt` in `localStorage` — **done in code**,
  confirm at runtime.
- OIDC and SAML logins complete — **unverified**.
- Desktop and API-key authentication unchanged — **unverified**.
- CSRF enabled for cookie-authenticated routes — **not done**; flag exists, still off.
- Token removed from the OAuth/SAML redirect fragment — **not done**; deliberate, pending
  confirmation that no client depends on it.
