package com.nfv.auth.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.csrf.CsrfException;

import java.io.IOException;

/**
 * Login and registration forms with an outdated CSRF token are not an error the user can do anything about. It
 * happens when the form was submitted twice (the second request carries the token the first login replaced), when
 * the page was restored with the back button after the login, when it stayed open longer than the session lives or
 * when the backend was restarted in between. Instead of a 403 page:
 * <ul>
 *     <li>already signed in: continue in the app (frontend URL), it gets its tokens without a new login</li>
 *     <li>not signed in: show the form again with a note ({@code ?expired})</li>
 * </ul>
 * Every other denied request gets the normal 403 (error page of the app).
 */
class StaleFormAccessDeniedHandler implements AccessDeniedHandler {
    private static final AuthenticationTrustResolver TRUST_RESOLVER = new AuthenticationTrustResolverImpl();

    private final AccessDeniedHandler fallback = new AccessDeniedHandlerImpl();
    private final String appUrl;

    StaleFormAccessDeniedHandler(String appUrl) {
        this.appUrl = appUrl;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException, ServletException {
        String form = request.getRequestURI().substring(request.getContextPath().length());
        boolean staleForm = exception instanceof CsrfException
                && "POST".equals(request.getMethod())
                && ("/login".equals(form) || "/register".equals(form));
        if (!staleForm) {
            fallback.handle(request, response, exception);
            return;
        }
        boolean signedIn = TRUST_RESOLVER.isAuthenticated(SecurityContextHolder.getContext().getAuthentication());
        response.sendRedirect(signedIn ? appUrl : request.getContextPath() + form + "?expired");
    }
}
