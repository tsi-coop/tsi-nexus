package org.tsicoop.nexus.framework;

import jakarta.servlet.*;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Server-side gate for the static console pages (/admin, /onboard, /seed, /liquid).
 *
 * These pages used to be served unconditionally to any HTTP client; the only login check
 * was client-side JS reading localStorage after the full page source had been delivered.
 * This filter requires a valid JWT, carried in the HttpOnly session
 * cookie set at login, before the page is dispatched. /admin, /onboard and /seed also
 * require the admin role (as their client-side checks already did); /liquid accepts any
 * valid user. The login pages themselves stay public.
 *
 * The API is unaffected: /api/* calls are still authenticated per request by InterceptingFilter.
 */
public class ConsoleAuthFilter implements Filter {

    private static final Set<String> PUBLIC_PAGES = new HashSet<>(Arrays.asList(
        "/admin", "/admin/", "/admin/index.html",
        "/liquid", "/liquid/", "/liquid/index.html"
    ));

    @Override
    public void init(FilterConfig filterConfig) {
    }

    @Override
    public void destroy() {
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        String path = req.getServletPath() + (req.getPathInfo() != null ? req.getPathInfo() : "");
        if (PUBLIC_PAGES.contains(path)) {
            chain.doFilter(request, response);
            return;
        }

        boolean liquid = path.startsWith("/liquid/");
        String token = readSessionCookie(req);
        if (token == null || !JWTUtil.isTokenValid(token)
                || (!liquid && !"admin".equalsIgnoreCase(roleOf(token)))) {
            res.sendRedirect(liquid ? "/liquid/index.html" : "/admin/index.html");
            return;
        }

        chain.doFilter(request, response);
    }

    private static String roleOf(String token) {
        try {
            return JWTUtil.getRoleFromToken(token);
        } catch (Exception e) {
            return null;
        }
    }

    private static String readSessionCookie(HttpServletRequest req) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (InputProcessor.CONSOLE_SESSION_COOKIE.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
