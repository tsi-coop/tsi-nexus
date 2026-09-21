package org.tsicoop.nexus.api;

import org.tsicoop.nexus.framework.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * TSI Nexus: Digital Twin Instance API
 *
 *   GET    /api/twins?type=&status=&q=&include_system=&limit=&cursor=  → list (status: active|archived|all, default active;
 *          system twins such as governance_node are hidden unless include_system=true or type=system)
 *   GET    /api/twins/{external_id}                    → fetch one, including archived
 *   POST   /api/twins  { external_id, type, current_state? }  → create
 *   PATCH  /api/twins/{external_id}  { current_state:{...} }  → shallow key-by-key merge
 *   DELETE /api/twins/{external_id}                    → soft delete (archive), idempotent
 *   POST   /api/twins/{external_id}/restore            → back to active
 *   POST   /api/twins/{external_id}/state/clear  { keys:[...] }  → remove state keys
 *
 * Type *schemas* (attribute lists, required/optional profile fields) are declared via /api/graph
 * (define_type) and readable at /api/entity_types. Create requires a registered type, an external_id
 * matching ^[a-z][a-z0-9_]{2,63}$, and every required profile field; patch may not leave one blank.
 *
 * Archived twins keep their rows, interaction history and relationships (hidden
 * while archived, visible again on restore). An archived external_id cannot be
 * reused; restore it instead.
 *
 * Errors: {success:false, error:{code, message}}.
 */
public class Twins implements Action {

    private static final String BASE = "/api/twins";
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final java.util.regex.Pattern EXTERNAL_ID = java.util.regex.Pattern.compile("^[a-z][a-z0-9_]{2,63}$");

    private static final String TWIN_COLS =
        "external_id, type, current_state::text AS current_state, status, archived_at, created_at, updated_at";

    /* ── GET ─────────────────────────────────────────────────────────────── */

    @Override
    public void get(HttpServletRequest req, HttpServletResponse res) {
        String[] path = subPath(req);
        if (path.length == 0) { list(req, res); }
        else if (path.length == 1) { fetch(path[0], res); }
        else notFound(res, "Unknown twin resource");
    }

    @SuppressWarnings("unchecked")
    private void list(HttpServletRequest req, HttpServletResponse res) {
        PoolDB pool = null; Connection conn = null;
        try {
            String type   = param(req, "type");
            String status = param(req, "status").toLowerCase();
            String q      = param(req, "q");
            String cursor = param(req, "cursor");
            boolean includeSystem = "true".equalsIgnoreCase(param(req, "include_system"));
            if (status.isEmpty()) status = "active";
            if (!status.equals("active") && !status.equals("archived") && !status.equals("all")) {
                bad(res, "status must be active, archived or all"); return;
            }
            int limit = DEFAULT_LIMIT;
            if (!param(req, "limit").isEmpty()) {
                try { limit = Integer.parseInt(param(req, "limit")); }
                catch (NumberFormatException e) { bad(res, "limit must be an integer"); return; }
                if (limit < 1 || limit > MAX_LIMIT) { bad(res, "limit must be between 1 and " + MAX_LIMIT); return; }
            }
            String afterId = null;
            if (!cursor.isEmpty()) {
                try { afterId = new String(java.util.Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8); }
                catch (IllegalArgumentException e) { bad(res, "invalid cursor"); return; }
            }

            StringBuilder sql = new StringBuilder("SELECT " + TWIN_COLS + " FROM digital_twins WHERE 1=1");
            List<String> args = new ArrayList<>();
            if (!status.equals("all")) { sql.append(" AND status = ?"); args.add(status); }
            if (!type.isEmpty())       { sql.append(" AND type = ?");   args.add(type.toLowerCase()); }
            if (!includeSystem && !type.equalsIgnoreCase("system")) sql.append(" AND type <> 'system'");
            if (!q.isEmpty()) {
                sql.append(" AND (external_id ILIKE ? ESCAPE '\\' OR current_state->>'name' ILIKE ? ESCAPE '\\')");
                String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                args.add(like); args.add(like);
            }
            if (afterId != null) { sql.append(" AND external_id > ?"); args.add(afterId); }
            sql.append(" ORDER BY external_id LIMIT ").append(limit + 1);

            pool = new PoolDB(); conn = pool.getConnection();
            JSONArray twins = new JSONArray();
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < args.size(); i++) ps.setString(i + 1, args.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) twins.add(twinJson(rs));
                }
            }
            Object next = null;
            if (twins.size() > limit) {
                twins.remove(limit);
                String last = (String) ((JSONObject) twins.get(limit - 1)).get("external_id");
                next = java.util.Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(last.getBytes(StandardCharsets.UTF_8));
            }
            JSONObject out = new JSONObject();
            out.put("success", true);
            out.put("twins", twins);
            out.put("next_cursor", next);
            OutputProcessor.send(res, 200, out.toJSONString());
        } catch (Exception e) { serverError(res, e); }
        finally { if (pool != null) pool.cleanup(null, null, conn); }
    }

    @SuppressWarnings("unchecked")
    private void fetch(String externalId, HttpServletResponse res) {
        PoolDB pool = null; Connection conn = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            JSONObject twin = loadTwin(conn, externalId);
            if (twin == null) { notFound(res, "Twin not found: " + externalId); return; }
            JSONObject out = new JSONObject();
            out.put("success", true);
            out.put("twin", twin);
            OutputProcessor.send(res, 200, out.toJSONString());
        } catch (Exception e) { serverError(res, e); }
        finally { if (pool != null) pool.cleanup(null, null, conn); }
    }

    /* ── POST: create / restore / state clear ────────────────────────────── */

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        String[] path = subPath(req);
        if (path.length == 0) create(req, res);
        else if (path.length == 2 && path[1].equals("restore")) restore(path[0], res);
        else if (path.length == 3 && path[1].equals("state") && path[2].equals("clear")) clearState(path[0], req, res);
        else notFound(res, "Unknown twin resource");
    }

    @SuppressWarnings("unchecked")
    private void create(HttpServletRequest req, HttpServletResponse res) {
        PoolDB pool = null; Connection conn = null;
        try {
            JSONObject input = InputProcessor.getInput(req);
            if (input == null) { bad(res, "JSON body required"); return; }

            String externalId = str(input, "external_id").replaceFirst("^@", "");
            String rawType    = str(input, "type");
            JSONObject state  = input.get("current_state") instanceof JSONObject
                    ? (JSONObject) input.get("current_state") : new JSONObject();

            if (externalId.isEmpty() || rawType.isEmpty()) {
                bad(res, "external_id and type are required"); return;
            }
            String type = rawType.trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");
            if (type.isEmpty()) {
                bad(res, "type must contain at least one letter, digit, or underscore"); return;
            }

            if (!EXTERNAL_ID.matcher(externalId).matches()) {
                bad(res, "external_id must match ^[a-z][a-z0-9_]{2,63}$ (lowercase letters, digits, underscores; 3-64 chars)"); return;
            }

            pool = new PoolDB(); conn = pool.getConnection();
            EntityTypes.Registry reg = EntityTypes.load(conn);
            if (!reg.isKnownType(type) || type.equals("system")) {
                bad(res, "type not registered: " + type + ". Registered types: " + String.join(", ", reg.knownTypes())); return;
            }
            String missing = reg.checkRequired(type, state);
            if (missing != null) { bad(res, missing); return; }
            String twinId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO digital_twins (external_id, type, current_state) " +
                    "VALUES (?, ?, ?::jsonb) RETURNING id::text")) {
                ps.setString(1, externalId);
                ps.setString(2, type);
                ps.setString(3, state.toJSONString());
                try (ResultSet rs = ps.executeQuery()) { rs.next(); twinId = rs.getString(1); }
            } catch (SQLException e) {
                if ("23505".equals(e.getSQLState())) {
                    JSONObject existing = loadTwin(conn, externalId);
                    boolean archived = existing != null && "archived".equals(existing.get("status"));
                    OutputProcessor.apiError(res, 409, archived ? "twin_archived" : "conflict",
                        archived
                            ? "external_id exists but is archived; restore it instead: " + externalId
                            : "external_id already exists: " + externalId);
                    return;
                }
                throw e;
            }

            JSONObject out = new JSONObject();
            out.put("success",     true);
            out.put("twin_id",     twinId);
            out.put("external_id", externalId);
            out.put("type",        type);
            out.put("twin",        loadTwin(conn, externalId));
            OutputProcessor.send(res, 200, out.toJSONString());
        } catch (Exception e) { serverError(res, e); }
        finally { if (pool != null) pool.cleanup(null, null, conn); }
    }

    @SuppressWarnings("unchecked")
    private void restore(String externalId, HttpServletResponse res) {
        PoolDB pool = null; Connection conn = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE digital_twins SET status='active', archived_at=NULL WHERE external_id=?")) {
                ps.setString(1, externalId);
                if (ps.executeUpdate() == 0) { notFound(res, "Twin not found: " + externalId); return; }
            }
            JSONObject out = new JSONObject();
            out.put("success", true);
            out.put("twin", loadTwin(conn, externalId));
            OutputProcessor.send(res, 200, out.toJSONString());
        } catch (Exception e) { serverError(res, e); }
        finally { if (pool != null) pool.cleanup(null, null, conn); }
    }

    /** Removes the listed keys from current_state (e.g. Capture-owned *_current keys). */
    @SuppressWarnings("unchecked")
    private void clearState(String externalId, HttpServletRequest req, HttpServletResponse res) {
        PoolDB pool = null; Connection conn = null;
        try {
            JSONObject input = InputProcessor.getInput(req);
            if (input == null || !(input.get("keys") instanceof JSONArray) || ((JSONArray) input.get("keys")).isEmpty()) {
                bad(res, "keys must be a non-empty array"); return;
            }
            JSONArray keyArr = (JSONArray) input.get("keys");
            String[] keys = new String[keyArr.size()];
            for (int i = 0; i < keys.length; i++) keys[i] = String.valueOf(keyArr.get(i));

            pool = new PoolDB(); conn = pool.getConnection();
            JSONObject existing = loadTwin(conn, externalId);
            if (existing != null && "active".equals(existing.get("status"))) {
                JSONObject remaining = new JSONObject((JSONObject) existing.get("current_state"));
                for (String k : keys) remaining.remove(k);
                String missing = EntityTypes.load(conn).checkRequired((String) existing.get("type"), remaining);
                if (missing != null) { bad(res, missing); return; }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE digital_twins SET current_state = current_state - ?::text[] " +
                    "WHERE external_id=? AND status='active'")) {
                Array arr = conn.createArrayOf("text", keys);
                ps.setArray(1, arr);
                ps.setString(2, externalId);
                if (ps.executeUpdate() == 0 && missingOrArchived(conn, externalId, res)) return;
            }
            JSONObject out = new JSONObject();
            out.put("success", true);
            out.put("twin", loadTwin(conn, externalId));
            OutputProcessor.send(res, 200, out.toJSONString());
        } catch (Exception e) { serverError(res, e); }
        finally { if (pool != null) pool.cleanup(null, null, conn); }
    }

    /* ── PATCH ───────────────────────────────────────────────────────────── */

    @Override
    @SuppressWarnings("unchecked")
    public void patch(HttpServletRequest req, HttpServletResponse res) {
        String[] path = subPath(req);
        if (path.length != 1) { notFound(res, "Unknown twin resource"); return; }
        String externalId = path[0];
        PoolDB pool = null; Connection conn = null;
        try {
            JSONObject input = InputProcessor.getInput(req);
            if (input == null || !(input.get("current_state") instanceof JSONObject)) {
                bad(res, "current_state object required"); return;
            }
            for (String immutable : new String[]{"type", "external_id"}) {
                if (input.containsKey(immutable)) { bad(res, immutable + " cannot be changed"); return; }
            }
            JSONObject patch = (JSONObject) input.get("current_state");
            if (patch.isEmpty()) { bad(res, "current_state must contain at least one key"); return; }
            for (Object k : patch.keySet()) {
                if (String.valueOf(k).endsWith("_current")) {
                    bad(res, "keys ending _current are managed by Capture and cannot be patched: " + k); return;
                }
            }

            pool = new PoolDB(); conn = pool.getConnection();
            JSONObject existing = loadTwin(conn, externalId);
            if (existing != null && "active".equals(existing.get("status"))) {
                JSONObject merged = new JSONObject((JSONObject) existing.get("current_state"));
                merged.putAll(patch);
                String missing = EntityTypes.load(conn).checkRequired((String) existing.get("type"), merged);
                if (missing != null) { bad(res, missing); return; }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE digital_twins SET current_state = COALESCE(current_state,'{}'::jsonb) || ?::jsonb " +
                    "WHERE external_id=? AND status='active'")) {
                ps.setString(1, patch.toJSONString());
                ps.setString(2, externalId);
                if (ps.executeUpdate() == 0 && missingOrArchived(conn, externalId, res)) return;
            }
            JSONObject out = new JSONObject();
            out.put("success", true);
            out.put("twin", loadTwin(conn, externalId));
            OutputProcessor.send(res, 200, out.toJSONString());
        } catch (Exception e) { serverError(res, e); }
        finally { if (pool != null) pool.cleanup(null, null, conn); }
    }

    /* ── DELETE (archive) ────────────────────────────────────────────────── */

    @Override
    @SuppressWarnings("unchecked")
    public void delete(HttpServletRequest req, HttpServletResponse res) {
        String[] path = subPath(req);
        if (path.length != 1) { notFound(res, "Unknown twin resource"); return; }
        PoolDB pool = null; Connection conn = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE digital_twins SET status='archived', archived_at=COALESCE(archived_at, NOW()) " +
                    "WHERE external_id=?")) {
                ps.setString(1, path[0]);
                if (ps.executeUpdate() == 0) { notFound(res, "Twin not found: " + path[0]); return; }
            }
            JSONObject out = new JSONObject();
            out.put("success", true);
            out.put("twin", loadTwin(conn, path[0]));
            OutputProcessor.send(res, 200, out.toJSONString());
        } catch (Exception e) { serverError(res, e); }
        finally { if (pool != null) pool.cleanup(null, null, conn); }
    }

    /* ── helpers ─────────────────────────────────────────────────────────── */

    /** Segments after /api/twins, e.g. /api/twins/@x/restore → ["x","restore"]. */
    private String[] subPath(HttpServletRequest req) {
        String p = req.getServletPath();
        String rest = p.length() > BASE.length() ? p.substring(BASE.length()) : "";
        List<String> segs = new ArrayList<>();
        for (String s : rest.split("/")) if (!s.isEmpty()) segs.add(s);
        if (!segs.isEmpty()) segs.set(0, segs.get(0).replaceFirst("^@", ""));
        return segs.toArray(new String[0]);
    }

    /** After a 0-row active-only update: writes 404/409 and returns true; false if twin is active. */
    private boolean missingOrArchived(Connection conn, String externalId, HttpServletResponse res) throws Exception {
        JSONObject t = loadTwin(conn, externalId);
        if (t == null) { notFound(res, "Twin not found: " + externalId); return true; }
        if ("archived".equals(t.get("status"))) {
            OutputProcessor.apiError(res, 409, "twin_archived", "Twin is archived: " + externalId); return true;
        }
        return false;
    }

    private JSONObject loadTwin(Connection conn, String externalId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + TWIN_COLS + " FROM digital_twins WHERE external_id = ?")) {
            ps.setString(1, externalId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? twinJson(rs) : null; }
        }
    }

    @SuppressWarnings("unchecked")
    private JSONObject twinJson(ResultSet rs) throws Exception {
        JSONObject state = (JSONObject) new JSONParser().parse(rs.getString("current_state"));
        JSONObject t = new JSONObject();
        t.put("external_id",   rs.getString("external_id"));
        t.put("type",          rs.getString("type"));
        t.put("current_state", state);
        t.put("status",        rs.getString("status"));
        t.put("archived_at",   iso(rs.getTimestamp("archived_at")));
        t.put("created_at",    iso(rs.getTimestamp("created_at")));
        t.put("updated_at",    iso(rs.getTimestamp("updated_at")));
        Object name = state.get("name");
        t.put("display_name",  name != null && !name.toString().isEmpty() ? name.toString() : null);
        return t;
    }

    private static String iso(Timestamp ts) { return ts == null ? null : ts.toInstant().toString(); }

    private static String param(HttpServletRequest req, String name) {
        String v = req.getParameter(name);
        return v == null ? "" : v.trim();
    }

    private static String str(JSONObject o, String key) {
        Object v = o.get(key);
        return v != null ? v.toString().trim() : "";
    }

    private static void bad(HttpServletResponse res, String msg)      { OutputProcessor.apiError(res, 400, "bad_request", msg); }
    private static void notFound(HttpServletResponse res, String msg) { OutputProcessor.apiError(res, 404, "not_found", msg); }
    private static void serverError(HttpServletResponse res, Exception e) {
        e.printStackTrace();
        OutputProcessor.apiError(res, 500, "server_error", e.getMessage());
    }

    @Override public void put(HttpServletRequest q, HttpServletResponse s) {
        OutputProcessor.apiError(s, 405, "method_not_allowed", "PUT not supported; use PATCH");
    }
    @Override public boolean validate(String m, HttpServletRequest q, HttpServletResponse s) { return true; }
}
