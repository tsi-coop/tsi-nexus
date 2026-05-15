package org.tsicoop.nexus.api;

import org.tsicoop.nexus.framework.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class Debug implements Action {

    @Override
    @SuppressWarnings("unchecked")
    public void get(HttpServletRequest req, HttpServletResponse res) {
        PoolDB pool = null;
        Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            String verb = req.getParameter("command_verb");
            boolean scoped = verb != null && !verb.isBlank();

            JSONArray checks = new JSONArray();

            if (scoped) {
                checks.add(checkFormLinkedWrongType(conn, verb));
                checks.add(checkFormComponentNoForm(conn, verb));
                checks.add(checkDanglingLinkedForm(conn, verb));
                checks.add(checkOrphanCommandAction(conn, verb));
                checks.add(checkEntityTypeMismatch(conn, verb));
                checks.add(checkSqlTypeMismatch(conn, verb));
            } else {
                checks.add(checkSqlTypeMismatch(conn, null));
                checks.add(checkFormLinkedWrongType(conn, null));
                checks.add(checkFormComponentNoForm(conn, null));
                checks.add(checkDanglingLinkedForm(conn, null));
                checks.add(checkOrphanCommandAction(conn, null));
                checks.add(checkOrphanSchemaAction(conn));
                checks.add(checkEntityTypeMismatch(conn, null));
                checks.add(checkGuardrailNoCount(conn));
            }

            // Keep only checks that found issues; tally severity
            JSONArray issues = new JSONArray();
            int errors = 0, warnings = 0;
            for (Object o : checks) {
                JSONObject c = (JSONObject) o;
                JSONArray records = (JSONArray) c.get("records");
                if (records != null && !records.isEmpty()) {
                    issues.add(c);
                    if ("error".equals(c.get("severity"))) errors++;
                    else warnings++;
                }
            }

            JSONObject stats = new JSONObject();
            stats.put("total",    errors + warnings);
            stats.put("errors",   errors);
            stats.put("warnings", warnings);

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("stats",   stats);
            result.put("checks",  issues);
            OutputProcessor.send(res, 200, result);

        } catch (Exception e) {
            e.printStackTrace();
            OutputProcessor.errorResponse(res, 500, "Diagnostics failed", e.getMessage(), req.getRequestURI());
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void post(HttpServletRequest req, HttpServletResponse res) {
        PoolDB pool = null;
        Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            JSONObject input = InputProcessor.getInput(req);
            String action = (String) input.get("action");

            if ("fix_component_type".equals(action)) {
                String verb = (String) input.get("command_verb");
                if (verb == null || verb.isBlank()) {
                    OutputProcessor.errorResponse(res, 400, "Bad request", "command_verb is required", req.getRequestURI());
                    return;
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE command_manifest SET component_type = 'interaction_capture_form' " +
                        "WHERE command_verb = ? AND linked_form IS NOT NULL AND linked_form != ''")) {
                    ps.setString(1, verb.toLowerCase().trim());
                    int rows = ps.executeUpdate();
                    JSONObject result = new JSONObject();
                    result.put("success", rows > 0);
                    result.put("message", rows > 0
                            ? "component_type fixed for /" + verb
                            : "No command found with that verb and a linked form");
                    OutputProcessor.send(res, 200, result);
                }
            } else {
                OutputProcessor.errorResponse(res, 400, "Bad request", "Unknown action: " + action, req.getRequestURI());
            }

        } catch (Exception e) {
            e.printStackTrace();
            OutputProcessor.errorResponse(res, 500, "Fix failed", e.getMessage(), req.getRequestURI());
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    @SuppressWarnings("unchecked")
    private JSONObject makeCheck(String checkId, String severity, String title, String description,
                                 boolean autoFixable, JSONArray records) {
        JSONObject c = new JSONObject();
        c.put("check_id",     checkId);
        c.put("severity",     severity);
        c.put("title",        title);
        c.put("description",  description);
        c.put("auto_fixable", autoFixable);
        c.put("records",      records);
        return c;
    }

    @SuppressWarnings("unchecked")
    private JSONObject checkFormLinkedWrongType(Connection conn, String verb) throws SQLException {
        String sql = "SELECT command_verb, component_type, linked_form FROM command_manifest " +
                     "WHERE is_active = TRUE AND linked_form IS NOT NULL AND linked_form != '' " +
                     "AND component_type != 'interaction_capture_form'" +
                     (verb != null ? " AND command_verb = ?" : "");
        JSONArray records = new JSONArray();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (verb != null) ps.setString(1, verb);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JSONObject r = new JSONObject();
                    r.put("command_verb",   rs.getString("command_verb"));
                    r.put("component_type", rs.getString("component_type"));
                    r.put("linked_form",    rs.getString("linked_form"));
                    records.add(r);
                }
            }
        }
        return makeCheck("form_linked_wrong_type", "error",
                "Form Linked but Component Type Wrong",
                "Command has a linked_form but component_type is not 'interaction_capture_form' — the form will never be shown.",
                true, records);
    }

    @SuppressWarnings("unchecked")
    private JSONObject checkFormComponentNoForm(Connection conn, String verb) throws SQLException {
        String sql = "SELECT command_verb, component_type FROM command_manifest " +
                     "WHERE is_active = TRUE AND component_type = 'interaction_capture_form' " +
                     "AND (linked_form IS NULL OR linked_form = '')" +
                     (verb != null ? " AND command_verb = ?" : "");
        JSONArray records = new JSONArray();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (verb != null) ps.setString(1, verb);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JSONObject r = new JSONObject();
                    r.put("command_verb",   rs.getString("command_verb"));
                    r.put("component_type", rs.getString("component_type"));
                    records.add(r);
                }
            }
        }
        return makeCheck("form_component_no_form", "error",
                "Form Component Type but No Form Linked",
                "Command expects to show a form but has no linked_form schema configured.",
                false, records);
    }

    @SuppressWarnings("unchecked")
    private JSONObject checkDanglingLinkedForm(Connection conn, String verb) throws SQLException {
        String sql = "SELECT cm.command_verb, cm.linked_form FROM command_manifest cm " +
                     "LEFT JOIN interaction_schema s ON cm.linked_form = s.schema_id " +
                     "WHERE cm.is_active = TRUE AND cm.linked_form IS NOT NULL AND cm.linked_form != '' " +
                     "AND (s.schema_id IS NULL OR s.is_active = FALSE)" +
                     (verb != null ? " AND cm.command_verb = ?" : "");
        JSONArray records = new JSONArray();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (verb != null) ps.setString(1, verb);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JSONObject r = new JSONObject();
                    r.put("command_verb", rs.getString("command_verb"));
                    r.put("linked_form",  rs.getString("linked_form"));
                    records.add(r);
                }
            }
        }
        return makeCheck("dangling_linked_form", "error",
                "Linked Form Not Found or Inactive",
                "Command references a form schema that does not exist or has been deactivated.",
                false, records);
    }

    @SuppressWarnings("unchecked")
    private JSONObject checkOrphanCommandAction(Connection conn, String verb) throws SQLException {
        String sql = "SELECT cm.command_verb, cm.action_type FROM command_manifest cm " +
                     "LEFT JOIN policy_manifest pm ON cm.action_type = pm.action_type AND pm.is_active = TRUE " +
                     "WHERE cm.is_active = TRUE AND pm.policy_id IS NULL" +
                     (verb != null ? " AND cm.command_verb = ?" : "");
        JSONArray records = new JSONArray();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (verb != null) ps.setString(1, verb);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JSONObject r = new JSONObject();
                    r.put("command_verb", rs.getString("command_verb"));
                    r.put("action_type",  rs.getString("action_type"));
                    records.add(r);
                }
            }
        }
        return makeCheck("orphan_command_action", "warning",
                "No Guardrail Policy for Action",
                "Command's action_type has no active policy in the policy manifest — the action runs unguarded.",
                false, records);
    }

    @SuppressWarnings("unchecked")
    private JSONObject checkOrphanSchemaAction(Connection conn) throws SQLException {
        String sql = "SELECT s.schema_id, s.action_type FROM interaction_schema s " +
                     "LEFT JOIN policy_manifest pm ON s.action_type = pm.action_type AND pm.is_active = TRUE " +
                     "WHERE s.is_active = TRUE AND pm.policy_id IS NULL";
        JSONArray records = new JSONArray();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JSONObject r = new JSONObject();
                    r.put("schema_id",   rs.getString("schema_id"));
                    r.put("action_type", rs.getString("action_type"));
                    records.add(r);
                }
            }
        }
        return makeCheck("orphan_schema_action", "warning",
                "Form Schema Has No Policy",
                "Interaction schema's action_type has no active guardrail policy.",
                false, records);
    }

    @SuppressWarnings("unchecked")
    private JSONObject checkEntityTypeMismatch(Connection conn, String verb) throws SQLException {
        String sql = "SELECT cm.command_verb, cm.entity_type, s.schema_id, s.applies_to " +
                     "FROM command_manifest cm JOIN interaction_schema s ON cm.linked_form = s.schema_id " +
                     "WHERE cm.is_active = TRUE AND cm.entity_type != s.applies_to AND s.applies_to != '*'" +
                     (verb != null ? " AND cm.command_verb = ?" : "");
        JSONArray records = new JSONArray();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (verb != null) ps.setString(1, verb);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JSONObject r = new JSONObject();
                    r.put("command_verb",        rs.getString("command_verb"));
                    r.put("command_entity_type", rs.getString("entity_type"));
                    r.put("schema_id",           rs.getString("schema_id"));
                    r.put("schema_applies_to",   rs.getString("applies_to"));
                    records.add(r);
                }
            }
        }
        return makeCheck("entity_type_mismatch", "warning",
                "Entity Type Mismatch",
                "Command targets a different entity type than its linked form expects.",
                false, records);
    }

    @SuppressWarnings("unchecked")
    private JSONObject checkSqlTypeMismatch(Connection conn, String verb) throws SQLException {
        String sql = verb != null
            ? "SELECT pm.policy_id, pm.action_type, pm.query_logic FROM policy_manifest pm " +
              "JOIN command_manifest cm ON pm.action_type = cm.action_type " +
              "WHERE pm.is_active = TRUE AND cm.command_verb = ? " +
              "AND pm.query_logic ~ 'current_state->>' " +
              "AND pm.query_logic ~ '[<>=].*[0-9]' " +
              "AND pm.query_logic !~ '::(numeric|float|integer|int|bigint|decimal|real|double)'"
            : "SELECT policy_id, action_type, query_logic FROM policy_manifest " +
              "WHERE is_active = TRUE " +
              "AND query_logic ~ 'current_state->>' " +
              "AND query_logic ~ '[<>=].*[0-9]' " +
              "AND query_logic !~ '::(numeric|float|integer|int|bigint|decimal|real|double)'";
        JSONArray records = new JSONArray();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (verb != null) ps.setString(1, verb);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JSONObject r = new JSONObject();
                    r.put("policy_id",   rs.getString("policy_id"));
                    r.put("action_type", rs.getString("action_type"));
                    r.put("query_logic", rs.getString("query_logic"));
                    records.add(r);
                }
            }
        }
        return makeCheck("sql_type_mismatch", "error",
                "SQL Type Mismatch in Policy Query",
                "Policy query compares a JSONB ->> field (text) to an integer without ::numeric cast — will fail at runtime.",
                false, records);
    }

    @SuppressWarnings("unchecked")
    private JSONObject checkGuardrailNoCount(Connection conn) throws SQLException {
        String sql = "SELECT policy_id, execution_mode FROM policy_manifest " +
                     "WHERE is_active = TRUE AND execution_mode = 'GUARDRAIL' " +
                     "AND query_logic !~ 'COUNT\\(\\*\\)'";
        JSONArray records = new JSONArray();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JSONObject r = new JSONObject();
                    r.put("policy_id",      rs.getString("policy_id"));
                    r.put("execution_mode", rs.getString("execution_mode"));
                    records.add(r);
                }
            }
        }
        return makeCheck("guardrail_no_count", "warning",
                "GUARDRAIL Policy Missing COUNT(*)",
                "GUARDRAIL policies must return COUNT(*) — action is blocked when count > 0.",
                false, records);
    }

    @Override public void put(HttpServletRequest q, HttpServletResponse s) {}
    @Override public void delete(HttpServletRequest q, HttpServletResponse s) {}
    @Override public boolean validate(String m, HttpServletRequest q, HttpServletResponse s) { return true; }
}
