package org.tsicoop.nexus.framework;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;

public class InterceptingFilter implements Filter {

    private static final String URL_DELIMITER = "/";
    private static final String ADMIN_URI = "admin";
    private static final String CLIENT_URI = "client";

    private static final HashMap<String, String> filterConfig = new HashMap<String, String>();

    private static final Map<String, String> API_KEY_SCOPES = new HashMap<>();
    static {
        API_KEY_SCOPES.put("/api/intent",     "intent:read");
        API_KEY_SCOPES.put("/api/context",    "context:read");
        API_KEY_SCOPES.put("/api/governance", "governance:read");
        API_KEY_SCOPES.put("/api/capture",    "capture:write");
        API_KEY_SCOPES.put("/api/entities",   "context:read");
        API_KEY_SCOPES.put("/api/graph",      "context:read");
        API_KEY_SCOPES.put("/api/entity_types", "context:read");
    }

    /** Resource paths with sub-paths (/api/twins/{id}[/restore], /api/relationships/{rel_id}). */
    private static final List<String> RESOURCE_PATHS = Arrays.asList("/api/twins", "/api/relationships");

    private static String resourceBase(String path) {
        for (String base : RESOURCE_PATHS) {
            if (path.equals(base) || path.startsWith(base + "/")) return base;
        }
        return null;
    }

    /** Scopes accepted for the twin/relationship API: twins:read for reads, twins:write for writes
     *  (legacy context:write is still honoured for writes). */
    private static String[] resourceScopes(String method) {
        return "GET".equalsIgnoreCase(method)
            ? new String[]{"twins:read", "twins:write"}
            : new String[]{"twins:write", "context:write"};
    }

    private static final Set<String> ADMIN_ONLY_PATHS = new HashSet<>(Arrays.asList(
        "/api/dashboard",
        "/api/audit",
        "/api/users",
        "/api/apikeys",
        "/api/tuning",
        "/api/reports",
        "/api/policy",
        "/api/templates",
        "/api/schema",
        "/api/registry",
        "/api/stream",
        "/api/debug",
        "/api/seeding",
        "/api/services"
    ));

    /** Paths that need no credentials at the filter (login/setup; ingest authenticates with its own source secret). */
    private static final Set<String> PUBLIC_PATHS = new HashSet<>(Arrays.asList(
        "/api/auth", "/api/setup", "/api/ingest"
    ));

    /** Paths where non-GET calls are ordinary end-user operations (any valid JWT / scoped API key).
     *  Every other non-GET call on a non-resource path is configuration and needs an admin JWT. */
    private static final Set<String> USER_WRITE_PATHS = new HashSet<>(Arrays.asList(
        "/api/intent", "/api/context", "/api/governance", "/api/capture",
        "/api/analytics", "/api/commentary"
    ));

    private static boolean isSafeMethod(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
    }
    @Override
    public void destroy() {
        // Any cleanup of resources
    }

    static {
        //log.info("Logger inits");
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        String responseJson = "";
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        String method = req.getMethod();
        String servletPath = req.getServletPath();
        String resourceBase = resourceBase(servletPath.trim());
        if (resourceBase != null) servletPath = resourceBase;
        String uri = req.getRequestURI();
        String classname = null;
        String operation = null;
        Properties apiRegistry = null;
        Properties config = null;
        boolean validrequest = true;
        boolean validheader = true;

        res.setCharacterEncoding("UTF-8");
        res.setContentType("application/json");

        apiRegistry = SystemConfig.getProcessorConfig();
        config = SystemConfig.getAppConfig();

        if (apiRegistry.containsKey(servletPath.trim())) {
            StringTokenizer strTok = new StringTokenizer(servletPath, URL_DELIMITER);
            strTok.nextToken(); // skip api keyword
           
            // Check
            try {
                 if (resourceBase != null) {
                     boolean authed = false;
                     if (req.getHeader("X-API-Key") != null) {
                         authed = InputProcessor.processClientHeader(req, res, resourceScopes(method));
                     } else if (req.getHeader("Authorization") != null
                             && InputProcessor.processAdminHeader(req, res)) {
                         authed = "admin".equalsIgnoreCase(InputProcessor.getRole(req));
                     }
                     if (!authed) {
                         OutputProcessor.apiError(res, 401, "unauthorized", "Admin JWT or API key with twins:read/twins:write required");
                         return;
                     }
                 }
                 String path = servletPath.trim();
                 if (resourceBase != null || PUBLIC_PATHS.contains(path)) {
                     // resource paths authenticated above; public paths need no credentials
                 } else if (req.getHeader("X-API-Key") != null) {
                     // API keys: only on scoped paths; config-style paths (graph, entities, entity_types) are read-only
                     String requiredScope = API_KEY_SCOPES.get(path);
                     boolean methodOk = USER_WRITE_PATHS.contains(path) || isSafeMethod(method);
                     if (requiredScope == null || !methodOk) {
                         OutputProcessor.errorResponse(res, 403, "Forbidden", "API key not permitted for this endpoint", req.getRequestURI());
                         return;
                     }
                     validheader = InputProcessor.processClientHeader(req, res, requiredScope);
                 } else {
                     if (!InputProcessor.processAdminHeader(req, res)) {
                         OutputProcessor.errorResponse(res, 401, "Unauthorized", "Valid credentials required", req.getRequestURI());
                         return;
                     }
                     boolean adminRequired = ADMIN_ONLY_PATHS.contains(path)
                             || (!isSafeMethod(method) && !USER_WRITE_PATHS.contains(path));
                     if (adminRequired && !"admin".equalsIgnoreCase(InputProcessor.getRole(req))) {
                         OutputProcessor.errorResponse(res, 403, "Forbidden", "Admin role required", req.getRequestURI());
                         return;
                     }
                 }

                 if(!validheader) {
                     OutputProcessor.errorResponse(res, 401, "Unauthorized", "Invalid or missing API credentials", req.getRequestURI());
                 }else{
                     InputProcessor.processInput(req, res);
                     operation = strTok.nextToken();
                     classname = apiRegistry.getProperty(servletPath.trim());
                     if (classname == null || method == null) res.sendError(400);

                   
                     Action action = ((Action) Class.forName(classname).getConstructor().newInstance());
                     validrequest = action.validate(method, req, res);
                     if (validrequest) {
                         if (method.equalsIgnoreCase("GET")) {
                             res.setContentType("application/json");
                             action.get(req, res);
                         } else if (method.equalsIgnoreCase("POST")) {
                             res.setContentType("application/json");
                             action.post(req, res);
                         } else if (method.equalsIgnoreCase("PUT")) {
                             res.setContentType("application/json");
                             action.put(req, res);
                         } else if (method.equalsIgnoreCase("PATCH")) {
                             res.setContentType("application/json");
                             action.patch(req, res);
                         } else if (method.equalsIgnoreCase("DELETE")) {
                             res.setContentType("application/json");
                             action.delete(req, res);
                         } else {
                             res.sendError(400);
                         }
                     }
                 }
            } catch (Exception e) {
                e.printStackTrace();
                res.sendError(400);
            }
        }
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        try {
            JWTUtil.validate();
        } catch (IllegalStateException e) {
            throw new ServletException(e.getMessage(), e);
        }
        SystemConfig.loadProcessors(filterConfig.getServletContext());
        System.out.println("Loaded TSI Processor Config");
        SystemConfig.loadAppConfig(filterConfig.getServletContext());

        System.out.println("Loaded TSI App Config");
        JSONSchemaValidator.createInstance(filterConfig.getServletContext());
        System.out.println("Loaded TSI Schema Validator");
        System.out.println("TSI Privacy Vault started in "+System.getenv("TSI_PRIVACY_VAULT_ENV")+" environment");
    }
}
