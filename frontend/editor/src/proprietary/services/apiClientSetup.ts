import { AxiosInstance, AxiosError, InternalAxiosRequestConfig } from "axios";
import { withBasePath } from "@app/constants/app";
import { getBrowserId } from "@app/utils/browserIdentifier";
import {
  setToken,
  clearToken,
  hasSession,
  buildAuthHeaders,
  isCookieMode as isCookieModeActive,
} from "@app/services/authTransport";

let isRefreshing = false;
let failedQueue: Array<{
  resolve: (token: string) => void;
  reject: (error: Error) => void;
}> = [];

// Morphe-PDF: token access and CSRF reading now come from the shared auth transport.
// In cookie mode getToken() returns null, so no bearer header is attached and the
// HttpOnly session cookie carries the session instead.
const setJwtTokenInStorage = setToken;
const clearJwtTokenFromStorage = clearToken;

function processQueue(error: Error | null, token: string | null = null): void {
  failedQueue.forEach((prom) => {
    if (error) {
      prom.reject(error);
    } else if (token) {
      prom.resolve(token);
    }
  });
  failedQueue = [];
}

async function refreshAuthToken(client: AxiosInstance): Promise<string> {
  console.log("[API Client] Refreshing expired JWT token...");

  try {
    const response = await client.post(
      "/api/v1/auth/refresh",
      {},
      {
        // Don't retry refresh requests to avoid infinite loops
        headers: { "X-Skip-Auth-Refresh": "true" },
      },
    );

    const newToken = response.data?.session?.access_token;
    // Morphe-PDF: in cookie mode the refreshed cookie is already on the response, so an
    // absent body token is not an error.
    if (!newToken && !isCookieModeActive()) {
      throw new Error("No access token in refresh response");
    }

    setJwtTokenInStorage(newToken ?? "");
    console.log("[API Client] ✅ Token refreshed successfully");
    return newToken;
  } catch (error) {
    console.error("[API Client] ❌ Token refresh failed:", error);
    clearJwtTokenFromStorage();

    // Redirect to login
    const loginPath = withBasePath("/login");
    if (window.location.pathname !== loginPath) {
      console.log("[API Client] Redirecting to login page...");
      window.location.href = loginPath;
    }

    throw error;
  }
}

/** Auth headers for raw fetch() calls (SSE streams). Async to match SaaS override. */
export async function getAuthHeaders(): Promise<Record<string, string>> {
  return buildAuthHeaders();
}

export function setupApiInterceptors(client: AxiosInstance): void {
  // Install request interceptor to add JWT token
  client.interceptors.request.use(
    async (config) => {
      const authHeaders = await getAuthHeaders();
      for (const [key, value] of Object.entries(authHeaders)) {
        if (!config.headers[key]) {
          config.headers[key] = value;
        }
      }

      config.headers["X-Browser-Id"] = getBrowserId();

      return config;
    },
    (error) => {
      return Promise.reject(error);
    },
  );

  // Install response interceptor to handle 401 and auto-refresh token
  client.interceptors.response.use(
    (response) => response,
    async (error: AxiosError) => {
      const originalRequest = error.config as InternalAxiosRequestConfig & {
        _retry?: boolean;
      };

      // Skip refresh for auth endpoints or if explicitly disabled
      // Exception: /auth/me should trigger refresh (used by getSession)
      if (
        !originalRequest ||
        (originalRequest.url?.includes("/api/v1/auth/") &&
          !originalRequest.url?.includes("/api/v1/auth/me")) ||
        originalRequest.headers?.["X-Skip-Auth-Refresh"] ||
        originalRequest._retry
      ) {
        return Promise.reject(error);
      }

      // Handle 401 errors by attempting token refresh
      // Morphe-PDF: gate on session presence, not on a readable token - in cookie
      // mode the token is invisible to script, so the old check never fired.
      if (error.response?.status === 401 && hasSession()) {
        console.warn(
          "[API Client] Received 401 error, attempting token refresh...",
        );

        if (isRefreshing) {
          // Already refreshing - queue this request
          return new Promise((resolve, reject) => {
            failedQueue.push({ resolve, reject });
          })
            .then((token) => {
              if (token) originalRequest.headers.Authorization = `Bearer ${token}`;
              return client(originalRequest);
            })
            .catch((err) => {
              return Promise.reject(err);
            });
        }

        originalRequest._retry = true;
        isRefreshing = true;

        try {
          const newToken = await refreshAuthToken(client);
          processQueue(null, newToken);

          // Retry original request with new token
          if (newToken) originalRequest.headers.Authorization = `Bearer ${newToken}`;
          return client(originalRequest);
        } catch (refreshError) {
          processQueue(refreshError as Error, null);
          return Promise.reject(refreshError);
        } finally {
          isRefreshing = false;
        }
      }

      return Promise.reject(error);
    },
  );
}
