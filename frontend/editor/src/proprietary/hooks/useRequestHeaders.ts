import { buildAuthHeaders } from "@app/services/authTransport";

/**
 * Headers for raw fetch() calls.
 *
 * Morphe-PDF: returns no Authorization header in cookie mode - the browser sends the
 * HttpOnly session cookie automatically. The CSRF header is included either way.
 */
export function useRequestHeaders(): HeadersInit {
  return buildAuthHeaders();
}
