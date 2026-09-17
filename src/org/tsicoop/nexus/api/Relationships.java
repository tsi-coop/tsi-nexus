package org.tsicoop.nexus.api;

import org.tsicoop.nexus.framework.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * TSI Nexus: Digital Twin Relationship Instance API
 *
 * POST /api/relationships
 *   { from_external_id, relationship_type, to_external_id, metadata? }
 *   → creates a graph edge between two existing digital twins
 *
 * Relationship *kinds* are declared separately via /api/graph (define_rel);
 * this endpoint creates the actual edge row that /api/graph and /api/context
 * then read back. Creating the same edge twice is idempotent - it returns the
 * existing rel_id with created:false instead of a duplicate row or an error.
 */
public class Relationships implements Action {

    @Override
    @SuppressWarnings("unchecked")
    public void post(HttpServletRequest req, HttpServletResponse res) {
        PoolDB pool = null;
        Connection conn = null;
        try {
            pool = new PoolDB();
            JSONObject input = InputProcessor.getInput(req);

            String fromId  = str(input, "from_external_id").replaceFirst("^@", "");
            String toId    = str(input, "to_external_id").replaceFirst("^@", "");
            String rawRel  = str(input, "relationship_type");
            JSONObject meta = input.get("metadata") instanceof JSONObject
                    ? (JSONObject) input.get("metadata") : new JSONObject();

            if (fromId.isEmpty() || toId.isEmpty() || rawRel.isEmpty()) {
                OutputProcessor.errorResponse(res, 400, "Bad request",
                    "from_external_id, relationship_type, and to_external_id are required", req.getRequestURI()); return;
            }
            String relType = rawRel.trim().toUpperCase().replaceAll("[^A-Z0-9_]", "_");
            if (relType.isEmpty()) {
                OutputProcessor.errorResponse(res, 400, "Bad request",
                    "relationship_type must contain at least one letter, digit, or underscore", req.getRequestURI()); return;
            }

            conn = pool.getConnection();

            // Resolve both twins up front so a missing side gives a clear 404
            // instead of a silent no-op insert (the SELECT...FROM two tables
            // join pattern used during seeding just yields zero rows here).
            String fromTwinId = lookupTwinId(conn, fromId);
            if (fromTwinId == null) {
                OutputProcessor.errorResponse(res, 404, "Not found",
                    "from_external_id not found: " + fromId, req.getRequestURI()); return;
            }
            String toTwinId = lookupTwinId(conn, toId);
            if (toTwinId == null) {
                OutputProcessor.errorResponse(res, 404, "Not found",
                    "to_external_id not found: " + toId, req.getRequestURI()); return;
            }

            String existingRelId = null;
            String dupSql = "SELECT rel_id::text FROM twin_relationships " +
                             "WHERE from_twin_id=?::uuid AND to_twin_id=?::uuid AND relationship_type=?";
            try (PreparedStatement ps = conn.prepareStatement(dupSql)) {
                ps.setString(1, fromTwinId);
                ps.setString(2, toTwinId);
                ps.setString(3, relType);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) existingRelId = rs.getString(1);
                }
            }

            JSONObject result = new JSONObject();
            if (existingRelId != null) {
                result.put("success", true);
                result.put("rel_id",  existingRelId);
                result.put("created", false);
                OutputProcessor.send(res, 200, result);
                return;
            }

            String insSql = "INSERT INTO twin_relationships (from_twin_id, to_twin_id, relationship_type, metadata) " +
                             "VALUES (?::uuid, ?::uuid, ?, ?::jsonb) RETURNING rel_id::text";
            try (PreparedStatement ps = conn.prepareStatement(insSql)) {
                ps.setString(1, fromTwinId);
                ps.setString(2, toTwinId);
                ps.setString(3, relType);
                ps.setString(4, meta.toJSONString());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    result.put("success", true);
                    result.put("rel_id",  rs.getString(1));
                    result.put("created", true);
                }
            }
            OutputProcessor.send(res, 200, result);

        } catch (Exception e) {
            e.printStackTrace();
            OutputProcessor.errorResponse(res, 500, "Create failed", e.getMessage(), req.getRequestURI());
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    private String lookupTwinId(Connection conn, String externalId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id::text FROM digital_twins WHERE external_id = ?")) {
            ps.setString(1, externalId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
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
