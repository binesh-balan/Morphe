/**
 * Morphe-PDF: single source of truth for how the session token is carried.
 *
 * Upstream kept the JWT in `localStorage` under `stirling_jwt` and attached it as a bearer
 * header. Anything running in the page origin could read it, which turned any script
 * injection into a full session compromise.
 *
 * Web builds now rely on an `HttpOnly` cookie issued by the backend: the browser sends it
 * automatically (every client sets `withCredentials`), and script cannot read it.
 *
 * The desktop client keeps bearer tokens. It is a native webview talking to a possibly
 * remote server, it passes the token across the Tauri bridge, and it cannot depend on
 * browser cookie semantics.
 *
 * Because an HttpOnly cookie is invisible to script, "is there a session?" cannot be
 * answered by looking for the token. A non-sensitive marker is kept instead — it carries no
 * credential and is only a UI hint. Authorisation is always decided by the server.
 */

const TOKEN_KEY = "stirling_jwt";
const SESSION_MARKER_KEY = "stirling_session";

/**
 * True when the session travels as an HttpOnly cookie rather than a bearer token.
 *
 * OPT-IN. Enable with `VITE_AUTH_COOKIE_MODE=true`.
 *
 * It shipped on by default and that was wrong: the migration was merged without the
 * frontend suite ever running against it (build.yml only triggers on PRs targeting main,
 * and the migration PR targeted another branch), and when the suite finally ran it failed
 * 30 authenticated UI tests. The backend half is additive and always active - it sets the
 * HttpOnly cookie AND returns the body token, and accepts either on the way back - so the
 * server side is ready whenever this is switched on.
 *
 * Before enabling: work through docs/security/P0-3-jwt-cookie-migration.md, in particular
 * the Entra ID and SAML round trips, and get the stubbed suite green with the flag set.
 * Until then the token stays in localStorage, which is the residual risk P0 #3 exists to
 * remove - see the note in docs/security/remaining-hardening.md.
 */
export function isCookieMode(): boolean {
  // Tauri exposes this global; desktop always uses bearer tokens.
  if (typeof window !== "undefined" && "__TAURI_INTERNALS__" in window) {
    return false;
  }
  const flag = import.meta.env?.VITE_AUTH_COOKIE_MODE;
  return flag === "true" || flag === true;
}

/** The bearer token, or null in cookie mode (where the browser carries the cookie). */
export function getToken(): string | null {
  if (isCookieMode()) return null;
  try {
    if (typeof localStorage === "undefined") return null;
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

/**
 * Persist the token. In cookie mode the token is deliberately NOT stored — the backend has
 * already set the HttpOnly cookie on the same response — and only the session marker is set.
 */
export function setToken(token: string): void {
  try {
    if (typeof localStorage === "undefined") return;
    if (isCookieMode()) {
      localStorage.setItem(SESSION_MARKER_KEY, "1");
      // Clear any token left over from a previous non-cookie build.
      localStorage.removeItem(TOKEN_KEY);
      return;
    }
    localStorage.setItem(TOKEN_KEY, token);
  } catch {
    // localStorage unavailable (private mode) - fail open
  }
}

/**
 * Clear local session state.
 *
 * In cookie mode this only drops the marker: the cookie itself is HttpOnly and can only be
 * expired by the server, which `POST /api/v1/auth/logout` does.
 */
export function clearToken(): void {
  try {
    if (typeof localStorage === "undefined") return;
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(SESSION_MARKER_KEY);
  } catch {
    // ignore
  }
}

/**
 * Whether the UI should assume a session exists.
 *
 * A hint only — never an authorisation decision. The server is the authority, and
 * `GET /api/v1/auth/me` is the way to confirm.
 */
export function hasSession(): boolean {
  try {
    if (typeof localStorage === "undefined") return false;
    if (isCookieMode()) {
      return localStorage.getItem(SESSION_MARKER_KEY) === "1";
    }
    return Boolean(localStorage.getItem(TOKEN_KEY));
  } catch {
    return false;
  }
}

/** Read the CSRF token that Spring Security publishes as a readable cookie. */
export function getXsrfToken(): string | null {
  try {
    if (typeof document === "undefined") return null;
    for (const cookie of document.cookie.split(";")) {
      const [name, ...rest] = cookie.trim().split("=");
      if (name === "XSRF-TOKEN") {
        return decodeURIComponent(rest.join("="));
      }
    }
    return null;
  } catch {
    return null;
  }
}

/** Headers for raw `fetch()` calls. Empty of auth in cookie mode — the cookie carries it. */
export function buildAuthHeaders(): Record<string, string> {
  const headers: Record<string, string> = {};
  const token = getToken();
  if (token) {
    headers["Authorization"] = `Bearer ${token}`;
  }
  const xsrf = getXsrfToken();
  if (xsrf) {
    headers["X-XSRF-TOKEN"] = xsrf;
  }
  return headers;
}
