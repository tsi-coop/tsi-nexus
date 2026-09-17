package org.tsicoop.nexus.api;

import org.tsicoop.nexus.framework.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * TSI Nexus: Digital Twin Instance API
 *
 * POST /api/twins  { external_id, type, current_state? }  → create a new digital twin
 *
 * Type *schemas* (attribute lists) are declared separately via /api/graph
 * (define_type); this endpoint creates the actual instance row that
 * /api/entities, /api/context, and /api/graph then read back. type is not
 * required to be pre-registered - consistent with the rest of the platform,
 * an ad-hoc type is accepted and simply shows up as a new blueprint.
 */
public class Twins implements Action {

    @Override
    @SuppressWarnings("unchecked")
    public void post(HttpServletRequest req, HttpServletResponse res) {
        PoolDB pool = null;
        Connection conn = null;
        try {
            pool = new PoolDB();
            JSONObject input = InputProcessor.getInput(req);

            String externalId = str(input, "external_id").replaceFirst("^@", "");
            String rawType     = str(input, "type");
            JSONObject state   = input.get("current_state") instanceof JSONObject
                    ? (JSONObject) input.get("current_state") : new JSONObject();

            if (externalId.isEmpty() || rawType.isEmpty()) {
                OutputProcessor.errorResponse(res, 400, "Bad request",
                    "external_id and type are required", req.getRequestURI()); return;
            }
            String type = rawType.trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");
            if (type.isEmpty()) {
                OutputProcessor.errorResponse(res, 400, "Bad request",
                    "type must contain at least one letter, digit, or underscore", req.getRequestURI()); return;
            }

            conn = pool.getConnection();
            String sql = "INSERT INTO digital_twins (external_id, type, current_state) " +
                         "VALUES (?, ?, ?::jsonb) RETURNING id::text";
            String twinId;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, externalId);
                ps.setString(2, type);
                ps.setString(3, state.toJSONString());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    twinId = rs.getString(1);
                }
            } catch (SQLException e) {
                if ("23505".equals(e.getSQLState())) {
                    OutputProcessor.errorResponse(res, 409, "Conflict",
                        "external_id already exists: " + externalId, req.getRequestURI()); return;
                }
                throw e;
            }

            JSONObject result = new JSONObject();
            result.put("success",     true);
            result.put("twin_id",     twinId);
            result.put("external_id", externalId);
            result.put("type",        type);
            OutputProcessor.send(res, 200, result);

        } catch (Exception e) {
            e.printStackTrace();
            OutputProcessor.errorResponse(res, 500, "Create failed", e.getMessage(), req.getRequestURI());
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    private String str(JSONObject o, String key) {
        Object v = o.get(key);
        return v != null ? v.toString().trim() : "";
    }

    @Override public void get(HttpServletRequest q, HttpServletResponse s) {}
    @Override public void put(HttpServletRequest q, HttpServletResponse s) {}
    @Override public void delete(HttpServletRequest q, HttpServletResponse s) {}
    @Override public boolean validate(String m, HttpServletRequest q, HttpServletResponse s) { return true; }
}
