/**
 * Default HTTP transport for the shared auth engine.
 *
 * The editor injects its own richer axios instance (with platform routing,
 * error toasts, credit headers, ...) via {@link configureSpringAuth}. Apps that
 * don't have one - notably the portal - fall back to this minimal client, which
 * attaches the `stirling_jwt` bearer token so the portal and editor share a
 * single same-origin session.
 */
import axios, { type AxiosInstance } from "axios";
import { getToken, setToken, clearToken } from "@app/services/authTransport";

/** localStorage key holding the Spring JWT. Shared so portal + editor agree. */
export const JWT_STORAGE_KEY = "stirling_jwt";

// Morphe-PDF: these now delegate to the shared auth transport, which returns null in
// cookie mode so no bearer header is attached and the HttpOnly cookie is used instead.
export const getStoredToken = getToken;
export const setStoredToken = setToken;
export const clearStoredToken = clearToken;

/**
 * Create the fallback transport. `baseURL` defaults to "/" so it targets the
 * same origin that served the SPA - the backend serves both portal and editor,
 * so the cookie/token domain is shared.
 */
export function createDefaultHttpClient(baseURL = "/"): AxiosInstance {
  const client = axios.create({
    baseURL,
    responseType: "json",
    withCredentials: true,
  });

  client.interceptors.request.use((config) => {
    const token = getStoredToken();
    if (token) {
      config.headers = config.headers ?? {};
      // Respect an explicit Authorization header (e.g. /auth/me passes the
      // candidate token directly); only fill it in when absent.
      if (!config.headers.Authorization) {
        config.headers.Authorization = `Bearer ${token}`;
      }
    }
    return config;
  });

  return client;
}
