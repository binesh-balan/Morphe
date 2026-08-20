# P0 #3 — Move the session JWT out of `localStorage` into an HttpOnly cookie

**Status:** not implemented. Deferred from the P0 remediation commit because it cannot be
verified without running the Gradle build, the frontend test suite, and a real SSO flow.

**Why it was deferred and not dropped:** the change touches authentication end to end —
issuance, extraction, refresh, logout, OAuth/OIDC, SAML, the desktop app, and CSRF. Shipping
it unverified risks locking every user out, or worse, half-applying it so that both the cookie
and the `localStorage` paths are broken. It needs a branch where you can build and test.

## Current state

The backend issues a JWT and returns it in the response body. The frontend stores it in
`localStorage` under the key `stirling_jwt` and attaches it as an `Authorization: Bearer`
header. Any script running in the page origin can read it.

`JwtServiceInterface.extractToken` is documented as *"Extract JWT token from HTTP request
(header or cookie)"*, but the implementation only reads the header — the cookie half was
never built.

## Residual risk while this is outstanding

Lower than it was, but not zero. The P0 commit removed the one concrete script-execution
vector found in review (PDF-embedded JavaScript) and added a CSP that blocks inline script
and restricts `connect-src` to `'self'`. Together those remove the known path to the token
and constrain exfiltration if a new one appears. What remains is the structural weakness:
the token is readable by script, so any future XSS is a full session compromise rather than
a contained defect.

## Target design

Dual-mode extraction, so browsers get a cookie and programmatic clients keep bearer tokens:

| Client | Transport | Rationale |
|---|---|---|
| Browser (web UI) | `HttpOnly` cookie | Not readable by script |
| Desktop app (Tauri) | `Authorization: Bearer` | Native webview; passes the token across the Tauri bridge and cannot rely on browser cookie semantics |
| API clients / MCP | `Authorization: Bearer` or `X-API-KEY` | Unchanged |

Cookie attributes: `HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/`.

> Use `Lax`, not `Strict`. With `Strict`, the browser omits the cookie on the cross-site
> navigation back from your identity provider, so the first request after an Entra ID or
> SAML redirect arrives unauthenticated and bounces the user into a login loop. `Lax` still
> blocks cross-site `POST` and XHR, which is the property that matters here.

## Backend changes

### 1. Extraction — `app/proprietary/.../security/service/JwtService.java`

`extractToken(HttpServletRequest)` (around line 341) currently reads only the
`Authorization` header. Read the cookie **first**, then fall back to the header so
desktop and API clients keep working:

```java
@Override
public String extractToken(HttpServletRequest request) {
    if (request.getCookies() != null) {
        for (Cookie c : request.getCookies()) {
            if (JWT_COOKIE_NAME.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                return c.getValue();
            }
        }
    }
    String authHeader = request.getHeader("Authorization");
    if (authHeader != null && authHeader.startsWith("Bearer ")) {
        return authHeader.substring(7);
    }
    return null;
}
```

Add matching `addTokenToResponse(HttpServletResponse, String, long)` and
`clearTokenCookie(HttpServletResponse)` helpers, and declare them on
`JwtServiceInterface` alongside the existing `extractToken`.

### 2. Issuance — five call sites

Each of these generates a token and returns it in a body or redirect fragment. Each must
also set the cookie. Keep returning the token in the body **only** for the desktop client
path (the `desktopExpiryMinutes` branches already distinguish it).

| File | Lines | Flow |
|---|---|---|
| `security/controller/api/AuthController.java` | 195, 204 | Login |
| `security/controller/api/AuthController.java` | 378, 385 | Refresh |
| `security/CustomAuthenticationSuccessHandler.java` | 58 | Form login |
| `security/oauth2/CustomOAuth2AuthenticationSuccessHandler.java` | 165, 173 | OAuth2 / OIDC — **this is the Entra ID path** |
| `security/saml2/CustomSaml2AuthenticationSuccessHandler.java` | 205, 213 | SAML2 |

> `security/saml2/JwtSaml2AuthenticationRequestRepository.java:52` also calls
> `generateToken`, but that token carries SAML request state, not a user session. Leave it
> alone.

The OIDC and SAML handlers currently hand the token to the frontend by redirecting to a
callback URL that carries it (consumed by `proprietary/routes/AuthCallback.tsx`). Once the
cookie is set on the redirect response, drop the token from the redirect URL entirely —
tokens in URLs leak through browser history, `Referer`, and proxy logs.

### 3. Logout

Find the logout handler and call `clearTokenCookie` — expire the cookie server-side with
`Max-Age=0` and the same `Path`/`Secure`/`SameSite` attributes. Clearing only client state
leaves a valid cookie in the browser.

### 4. Re-enable CSRF — `security/configuration/SecurityConfiguration.java:269`

```java
http.csrf(CsrfConfigurer::disable);
```

Cookie-borne credentials are sent automatically by the browser, which reintroduces CSRF
exposure that bearer tokens did not have. **This is not optional once cookies carry auth.**
Use `CookieCsrfTokenRepository.withHttpOnlyFalse()` and have the frontend echo the
`XSRF-TOKEN` value in the `X-XSRF-TOKEN` header. The CORS config already allows that header.
Exempt only the stateless API-key and MCP filter chains, which do not use cookies.

## Frontend changes

35 non-test references to `stirling_jwt` across 15 files. Centralise rather than editing
each one: `proprietary/auth/httpClient.ts:13` already defines `JWT_STORAGE_KEY`, so route
every access through one accessor module and change it in one place.

Set `credentials: "include"` on the fetch/axios client so the cookie is sent, and stop
attaching the `Authorization` header in web builds.

**Files to change:**

- `proprietary/auth/httpClient.ts` — the shared accessor; make this the only module that
  knows how the token is transported
- `proprietary/services/apiClientSetup.ts` (lines 13, 22, 31) — get/set/clear
- `proprietary/auth/spring/springAuthClient.ts` (14 references) — the bulk of the work,
  including refresh and logout
- `proprietary/routes/AuthCallback.tsx` (75, 84) — stop reading the token from the callback
  URL once the backend sets the cookie
- `proprietary/hooks/useRequestHeaders.ts` (2) — drop the `Authorization` header for web
- `core/services/httpErrorHandler.ts` (133), `core/components/shared/ErrorBoundary.tsx` (57),
  `core/components/shared/LoginAgreementModal.tsx` (33),
  `core/components/onboarding/orchestrator/useOnboardingOrchestrator.ts` (30) — these use
  token presence as a proxy for "logged in". A cookie is invisible to script, so replace
  these with a `GET /api/v1/auth/me`-style session check or an existing auth-context flag.

**Leave the desktop path on bearer tokens:** `desktop/services/authService.ts`,
`desktop/services/authTokenStore.ts`, `desktop/services/connectionModeService.ts`,
`desktop/extensions/platformSessionBridge.ts`, `desktop/extensions/authSessionCleanup.ts`.
Gate the behaviour on the existing desktop/Tauri build flag.

## Test plan

Nothing here is verifiable by inspection — run all of it:

1. Form login → confirm `Set-Cookie` carries `HttpOnly; Secure; SameSite=Lax`, and that
   `localStorage.getItem("stirling_jwt")` returns `null` in a web build.
2. Confirm authenticated API calls succeed with **no** `Authorization` header present.
3. Token refresh across expiry, and logout → confirm the cookie is expired server-side and
   the session is genuinely dead (replay the old cookie and expect 401).
4. Entra ID OIDC round trip, and SAML round trip — the `SameSite` trap surfaces here.
   Confirm no token appears in any redirect URL.
5. CSRF: a cross-site `POST` without the `X-XSRF-TOKEN` header must be rejected; the app's
   own requests must still succeed.
6. Desktop app against a remote server — confirm bearer auth still works unchanged.
7. API key and MCP chains — confirm unaffected.
8. Run the existing suites: `springAuthClient.test.ts`, `api-keys-ui.spec.ts`, and the
   stubbed auth helpers in `core/tests/helpers/stub-test-base.ts` (line 72 seeds
   `stirling_jwt` directly and will need updating).

## Definition of done

- No web-build code path reads or writes `stirling_jwt` in `localStorage`.
- CSRF protection is enabled for all cookie-authenticated routes.
- OIDC and SAML logins complete without a token appearing in any URL.
- Desktop and API-key authentication are unchanged.
