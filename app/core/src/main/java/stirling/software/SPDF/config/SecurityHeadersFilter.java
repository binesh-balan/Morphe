package stirling.software.SPDF.config;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Morphe-PDF security hardening: emits a Content-Security-Policy and related response headers.
 *
 * <p>Upstream Stirling-PDF ships no CSP at all, leaving no containment layer behind any
 * script-injection defect. This filter lives in the core module (rather than in the Spring Security
 * configuration, which is part of the optional proprietary module) so the headers apply to every
 * response regardless of whether login is enabled or which modules are compiled in.
 *
 * <p>The policy is deliberately configurable. Validate it against your deployment before enforcing:
 * set {@code morphe.security.csp.report-only=true} to collect violations without blocking anything,
 * confirm the browser console is clean, then switch enforcement on. Swagger UI and any custom HTML
 * templates are the most likely sources of violations.
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    /**
     * Default policy. {@code 'wasm-unsafe-eval'} is required by pdf.js; {@code blob:} is required
     * for worker bootstrapping and rendered page images; {@code 'unsafe-inline'} on style-src
     * covers React inline style attributes. Note there is no {@code 'unsafe-inline'} on script-src.
     */
    private static final String DEFAULT_CSP =
            "default-src 'self'; "
                    + "script-src 'self' 'wasm-unsafe-eval'; "
                    + "style-src 'self' 'unsafe-inline'; "
                    + "img-src 'self' data: blob:; "
                    + "font-src 'self' data:; "
                    + "connect-src 'self'; "
                    + "worker-src 'self' blob:; "
                    + "child-src 'self' blob:; "
                    + "frame-src 'self' blob:; "
                    + "media-src 'self' blob:; "
                    + "object-src 'none'; "
                    + "base-uri 'self'; "
                    + "form-action 'self'; "
                    + "frame-ancestors 'none'";

    /**
     * Blank means "use {@link #DEFAULT_CSP}". The default is resolved in code rather than in the
     * placeholder so the policy string's own colons can never be parsed as placeholder defaults.
     */
    @Value("${morphe.security.csp.policy:}")
    private String cspPolicy;

    @Value("${morphe.security.csp.report-only:false}")
    private boolean reportOnly;

    @Value("${morphe.security.headers.enabled:true}")
    private boolean enabled;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (enabled) {
            String policy =
                    (cspPolicy == null || cspPolicy.isBlank()) ? DEFAULT_CSP : cspPolicy.trim();
            String header =
                    reportOnly ? "Content-Security-Policy-Report-Only" : "Content-Security-Policy";
            response.setHeader(header, policy);

            // Stop MIME sniffing turning an uploaded file into an executable script.
            response.setHeader("X-Content-Type-Options", "nosniff");

            // Never leak document URLs (which can carry file identifiers) to third parties.
            response.setHeader("Referrer-Policy", "same-origin");

            // Deny access to device APIs this application has no use for.
            response.setHeader(
                    "Permissions-Policy",
                    "camera=(), microphone=(), geolocation=(), payment=(), usb=(),"
                            + " interest-cohort=()");

            // Deliberately NOT set: Cross-Origin-Opener-Policy. "same-origin" severs
            // window.opener, which breaks OAuth/SSO popup flows. Set it at the reverse proxy
            // only after confirming your identity provider uses full-page redirects.
        }

        filterChain.doFilter(request, response);
    }
}
