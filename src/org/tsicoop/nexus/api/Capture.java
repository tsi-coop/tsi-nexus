package org.tsicoop.nexus.api;

import org.tsicoop.nexus.framework.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

/**
 * TSI Nexus: Generic Interaction Capture Engine
 *
 * Serves schema-driven forms for any interaction type (KYC, surveys, document
 * uploads, field visits …). Zero sector-specific logic lives here — everything
 * is configured via rows in interaction_schema and policy_manifest.
 *
 * GET  /api/capture?external_id=@rohan_03
 *      → returns all active schemas applicable to that entity's type
 *
 * POST /api/capture  { schema_id, external_id, form_data }
 *      → validates fields, runs GUARDRAIL policies, patches current_state,
 *        appends to interaction_stream — all in one transaction
 */
public class Capture implements Action {

    /* ── GET: fetch applicable schemas for an entity ─────────────────────── */

    @Override
    @SuppressWarnings("unchecked")
    public void get(HttpServletRequest req, HttpServletResponse res) {
        String externalId = req.getParameter("external_id");
        if (externalId == null || externalId.isBlank()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "external_id parameter is required", req.getRequestURI());
            return;
        }
        String cleanId = externalId.replaceFirst("^@", "");
        PoolDB pool = null;
        Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            String[] entityMeta = resolveEntityMeta(conn, cleanId);
            if (entityMeta == null) {
                OutputProcessor.errorResponse(res, 404, "Not found", "Entity not found: " + cleanId, req.getRequestURI());
                return;
            }
            String entityType = entityMeta[0];
            String entityName = entityMeta[1]; // may be null
            String schemaId   = req.getParameter("schema_id");
            String cmdAction  = req.getParameter("action_type"); // command's action_type from the manifest
            JSONArray schemas;
            if (schemaId != null && !schemaId.isBlank()) {
                JSONObject s = loadSchema(conn, schemaId);
                schemas = new JSONArray();
                if (s != null) {
                    String policyKey = (cmdAction != null && !cmdAction.isBlank())
                                       ? cmdAction : (String) s.get("action_type");
                    if (policyKey != null) {
                        String[] violation = checkPolicies(conn, policyKey, cleanId);
                        if (violation == null && cmdAction != null && !cmdAction.equals(s.get("action_type"))) {
                            violation = checkPolicies(conn, (String) s.get("action_type"), cleanId);
                        }
                        if (violation != null) {
                            logBlock(conn, req, policyKey, cleanId, violation[1], violation[0]);
                            respond(res, false, violation[0], null);
                            return;
                        }
                    }
                    schemas.add(s);
                }
            } else {
                String policyKey = (cmdAction != null && !cmdAction.isBlank()) ? cmdAction : null;
                JSONArray all = fetchSchemas(conn, entityType);
                schemas = new JSONArray();
                String[] firstViolation = null;
                if (policyKey != null) {
                    firstViolation = checkPolicies(conn, policyKey, cleanId);
                    if (firstViolation != null) {
                        logBlock(conn, req, policyKey, cleanId, firstViolation[1], firstViolation[0]);
                        respond(res, false, firstViolation[0], null);
                        return;
                    }
                }
                for (Object o : all) {
                    JSONObject s = (JSONObject) o;
                    String at = (String) s.get("action_type");
                    String[] violation = (at != null && !at.equals(policyKey)) ? checkPolicies(conn, at, cleanId) : null;
                    if (violation != null) {
                        if (firstViolation == null) firstViolation = violation;
                    } else {
                        schemas.add(s);
                    }
                }
                if (schemas.isEmpty() && firstViolation != null) {
                    logBlock(conn, req, firstViolation[1], cleanId, firstViolation[1], firstViolation[0]);
                    respond(res, false, firstViolation[0], null);
                    return;
                }
            }
            JSONObject result = new JSONObject();
            result.put("success",     true);
            result.put("external_id", "@" + cleanId);
            result.put("entity_type", entityType);
            result.put("entity_name", entityName != null ? entityName : "");
            result.put("schemas",     schemas);
            OutputProcessor.send(res, 200, result);
        } catch (Exception e) {
            e.printStackTrace();
            OutputProcessor.errorResponse(res, 500, "Fetch failed", e.getMessage(), req.getRequestURI());
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    /* ── POST: submit a capture form ──────────────────────────────────────── */

    @Override
    @SuppressWarnings("unchecked")
    public void post(HttpServletRequest req, HttpServletResponse res) {
        PoolDB pool = null;
        Connection conn = null;
        try {
            pool = new PoolDB();
            JSONObject input    = InputProcessor.getInput(req);
            String schemaId    = (String) input.get("schema_id");
            String rawId       = (String) input.get("external_id");
            String externalId  = rawId.replaceFirst("^@", "");
            JSONObject formData = (JSONObject) input.get("form_data");

            conn = pool.getConnection();
            conn.setAutoCommit(false);

            String actorTwinId = InputProcessor.getTwinId(req);
            UUID actorId = (actorTwinId != null && !actorTwinId.isBlank())
                           ? UUID.fromString(actorTwinId) : null;

            // 1. Load the schema row
            JSONObject schema = loadSchema(conn, schemaId);
            if (schema == null) {
                respond(res, false, "Schema not found: " + schemaId, null);
                return;
            }

            // 2. Validate required fields and patterns (server-side mirror of client validation)
            JSONArray fields = (JSONArray) schema.get("fields");
            String validationError = validateFields(fields, formData);
            if (validationError != null) {
                respond(res, false, validationError, null);
                return;
            }

            // 3. Run GUARDRAIL policies for this action_type (db-configured, zero hardcoding)
            String actionType = (String) schema.get("action_type");
            String[] violation = checkPolicies(conn, actionType, externalId);
            if (violation != null) {
                respond(res, false, violation[0], null);
                return;
            }

            // 4. Build state patch: field transforms + schema.state_patch overrides
            JSONObject statePatch = buildStatePatch(fields, formData, (JSONObject) schema.get("state_patch"));

            // 5. Render stream entry from the template
            String streamEntry = renderStreamTemplate((String) schema.get("stream_tmpl"), fields, formData);

            // 6. Resolve entity UUID
            UUID ownerId = resolveOwnerId(conn, externalId);
            if (ownerId == null) throw new Exception("Entity not found: " + externalId);

            // 7. Patch current_state (triggers lineage trigger automatically)
            patchState(conn, externalId, statePatch);

            // 8. Append to interaction_stream
            appendInteraction(conn, ownerId, actorId, streamEntry);

            conn.commit();

            String ft = actionType; String fe = externalId; JSONObject fd = formData;
            new Thread(() -> callPushServices(ft, fe, fd)).start();

            JSONObject ok = new JSONObject();
            ok.put("success", true);
            ok.put("message", "Captured and recorded.");
            ok.put("stream_entry", streamEntry);
            OutputProcessor.send(res, 200, ok);

        } catch (Exception e) {
            e.printStackTrace();
            try { if (conn != null) conn.rollback(); } catch (Exception ignore) {}
            OutputProcessor.errorResponse(res, 500, "Capture failed", e.getMessage(), req.getRequestURI());
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    /* ── DB helpers ───────────────────────────────────────────────────────── */

    private String[] resolveEntityMeta(Connection conn, String externalId) throws Exception {
        String sql = "SELECT type, current_state->>'name' AS name FROM digital_twins WHERE external_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, externalId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new String[]{ rs.getString("type"), rs.getString("name") };
            }
        }
    }

    @SuppressWarnings("unchecked")
    private JSONArray fetchSchemas(Connection conn, String entityType) throws Exception {
        JSONArray schemas = new JSONArray();
        String sql = "SELECT schema_id, label, applies_to, action_type, fields, stream_tmpl " +
                     "FROM interaction_schema " +
                     "WHERE is_active = TRUE AND (applies_to = ? OR applies_to = '*') " +
                     "ORDER BY label";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, entityType);
            try (ResultSet rs = ps.executeQuery()) {
                JSONParser parser = new JSONParser();
                while (rs.next()) {
                    JSONObject s = new JSONObject();
                    s.put("schema_id",   rs.getString("schema_id"));
                    s.put("label",       rs.getString("label"));
                    s.put("applies_to",  rs.getString("applies_to"));
                    s.put("action_type", rs.getString("action_type"));
                    s.put("fields",      parser.parse(rs.getString("fields")));
                    s.put("stream_tmpl", rs.getString("stream_tmpl"));
                    schemas.add(s);
                }
            }
        }
        return schemas;
    }

    @SuppressWarnings("unchecked")
    private JSONObject loadSchema(Connection conn, String schemaId) throws Exception {
        String sql = "SELECT schema_id, label, action_type, fields, state_patch, stream_tmpl " +
                     "FROM interaction_schema WHERE schema_id = ? AND is_active = TRUE";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schemaId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                JSONParser parser = new JSONParser();
                JSONObject s = new JSONObject();
                s.put("schema_id",   rs.getString("schema_id"));
                s.put("label",       rs.getString("label"));
                s.put("action_type", rs.getString("action_type"));
                s.put("fields",      parser.parse(rs.getString("fields")));
                String rawPatch = rs.getString("state_patch");
                s.put("state_patch", rawPatch != null ? parser.parse(rawPatch) : new JSONObject());
                String rawTmpl = rs.getString("stream_tmpl");
                s.put("stream_tmpl", rawTmpl != null ? rawTmpl : "");
                return s;
            }
        }
    }

    private String validateFields(JSONArray fields, JSONObject formData) {
        for (Object obj : fields) {
            JSONObject field = (JSONObject) obj;
            String key      = (String) field.get("key");
            boolean req     = Boolean.TRUE.equals(field.get("required"));
            Object rawVal   = formData != null ? formData.get(key) : null;
            String val      = rawVal != null ? rawVal.toString().trim() : "";

            if (req && val.isEmpty()) {
                return "Required field missing: " + field.get("label");
            }
            String pattern = (String) field.get("pattern");
            if (pattern != null && !val.isEmpty() && !val.matches(pattern)) {
                return "Invalid format for " + field.get("label") + ". " + field.getOrDefault("hint", "");
            }
        }
        return null;
    }

    // Returns [error_message, policy_id] if a policy fires, null if clear
    private String[] checkPolicies(Connection conn, String actionType, String externalId) throws Exception {
        String sql = "SELECT policy_id, query_logic, error_message FROM policy_manifest " +
                     "WHERE action_type = ? AND is_active = TRUE AND execution_mode = 'GUARDRAIL'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, actionType.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String policyId = rs.getString("policy_id");
                    String query    = rs.getString("query_logic");
                    String error    = rs.getString("error_message");
                    try (PreparedStatement gps = conn.prepareStatement(query)) {
                        gps.setString(1, externalId);
                        try (ResultSet grs = gps.executeQuery()) {
                            if (grs.next() && grs.getInt(1) > 0) return new String[]{error, policyId};
                        }
                    }
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void logBlock(Connection conn, HttpServletRequest req, String actionType, String externalId, String policyId, String reason) {
        try {
            String actorTwinId = InputProcessor.getTwinId(req);
            UUID actorId = (actorTwinId != null && !actorTwinId.isBlank()) ? UUID.fromString(actorTwinId) : null;
            JSONObject executed = new JSONObject();
            executed.put("success",     false);
            executed.put("action_type", actionType);
            executed.put("entity",      externalId);
            executed.put("reason",      reason);
            String sql = "INSERT INTO action_audit_log (actor_id, intent_raw, action_executed, policy_id, created_at) " +
                         "VALUES (?, ?, ?::jsonb, ?, NOW())";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, actorId);
                ps.setString(2, actionType + " " + externalId);
                ps.setString(3, executed.toJSONString());
                ps.setString(4, policyId);
                ps.executeUpdate();
            }
        } catch (Exception ignore) {}
    }

    @SuppressWarnings("unchecked")
    private JSONObject buildStatePatch(JSONArray fields, JSONObject formData, JSONObject schemaPatch) {
        JSONObject patch = new JSONObject();
        for (Object obj : fields) {
            JSONObject field    = (JSONObject) obj;
            String key          = (String) field.get("key");
            String stateKey     = field.containsKey("state_key") ? (String) field.get("state_key") : key;
            String transform    = (String) field.getOrDefault("state_transform", "");
            Object rawVal       = formData != null ? formData.get(key) : null;
            if (rawVal == null) continue;
            String val = rawVal.toString().trim();
            if (val.isEmpty()) continue;
            if ("omit".equals(transform)) continue;
            if ("last4".equals(transform)) val = val.length() >= 4 ? val.substring(val.length() - 4) : val;
            if ("uppercase".equals(transform)) val = val.toUpperCase();
            patch.put(stateKey, val);
        }
        // Fixed key-values from the schema always win (e.g. kyc → "Verified")
        if (schemaPatch != null) patch.putAll(schemaPatch);
        return patch;
    }

    private String renderStreamTemplate(String tmpl, JSONArray fields, JSONObject formData) {
        String out = tmpl;
        for (Object obj : fields) {
            JSONObject field = (JSONObject) obj;
            String key = (String) field.get("key");
            Object val = formData != null ? formData.get(key) : null;
            out = out.replace("{" + key + "}", val != null ? val.toString() : "");
        }
        return out;
    }

    private UUID resolveOwnerId(Connection conn, String externalId) throws Exception {
        String sql = "SELECT id FROM digital_twins WHERE external_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, externalId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? (UUID) rs.getObject("id") : null;
            }
        }
    }

    private void patchState(Connection conn, String externalId, JSONObject patch) throws Exception {
        String sql = "UPDATE digital_twins SET current_state = COALESCE(current_state,'{}') || ?::jsonb " +
                     "WHERE external_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, patch.toJSONString());
            ps.setString(2, externalId);
            if (ps.executeUpdate() == 0) throw new Exception("Entity not found: " + externalId);
        }
    }

    private void appendInteraction(Connection conn, UUID ownerId, UUID actorId, String content) throws Exception {
        String sql = "INSERT INTO interaction_stream (owner_id, actor_id, content, created_at) VALUES (?, ?, ?, NOW())";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, ownerId);
            ps.setObject(2, actorId);
            ps.setString(3, content);
            ps.executeUpdate();
        }
    }

    @SuppressWarnings("unchecked")
    private void respond(HttpServletResponse res, boolean success, String reason, String streamEntry) {
        JSONObject r = new JSONObject();
        r.put("success", success);
        if (reason != null)      r.put("reason", reason);
        if (streamEntry != null) r.put("stream_entry", streamEntry);
        OutputProcessor.send(res, 200, r);
    }

    @SuppressWarnings("unchecked")
    private void callPushServices(String actionType, String externalId, JSONObject formData) {
        PoolDB pool = null; Connection conn = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            String sql = "SELECT api_base_url, auth_config::text FROM service_registry " +
                         "WHERE service_type='PUSH' AND trigger_action=? AND status='Active'";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, actionType.toUpperCase());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JSONObject cfg = (JSONObject) new JSONParser().parse(rs.getString("auth_config"));
                        JSONObject body = new JSONObject();
                        body.put("action_type", actionType);
                        body.put("external_id", externalId);
                        body.put("form_data",   formData != null ? formData : new JSONObject());
                        body.put("timestamp",   java.time.Instant.now().toString());
                        try {
                            new HttpClient().sendPost(
                                rs.getString("api_base_url"), body,
                                (String) cfg.get("header"), (String) cfg.get("secret")
                            );
                        } catch (Exception e) {
                            System.err.println("[PUSH] " + rs.getString("api_base_url") + ": " + e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[PUSH] callPushServices failed: " + e.getMessage());
        } finally { if (pool != null) pool.cleanup(null, null, conn); }
    }

    @Override public void put(HttpServletRequest q, HttpServletResponse s) {}
    @Override public void delete(HttpServletRequest q, HttpServletResponse s) {}
    @Override public boolean validate(String m, HttpServletRequest q, HttpServletResponse s) { return true; }
}
