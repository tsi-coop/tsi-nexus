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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
 * Either end being archived → 409 twin_archived.
 *
 * GET /api/relationships?from=&to=&type=&status=
 *   → {relationships:[{rel_id, from_external_id, relationship_type, to_external_id, metadata, status}]}
 *   status is derived from the endpoints: active (both ends active, default), archived
 *   (either end archived - edges are hidden with their twin and return on restore), or all.
 *   Filter on `to` for incoming edges, on `from` for outgoing.
 *
 * DELETE /api/relationships/{rel_id} → hard delete (edges carry no audit value).
 *
 * relationship_type must be registered (see /api/entity_types) and, when the registry declares
 * endpoint types for it, from/to twin types must match (400 naming the expected types).
 *
 * Errors: {success:false, error:{code, message}}.
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
                OutputProcessor.apiError(res, 400, "bad_request",
                    "from_external_id, relationship_type, and to_external_id are required"); return;
            }
            String relType = rawRel.trim().toUpperCase().replaceAll("[^A-Z0-9_]", "_");
            if (relType.isEmpty()) {
                OutputProcessor.apiError(res, 400, "bad_request",
                    "relationship_type must contain at least one letter, digit, or underscore"); return;
            }

            conn = pool.getConnection();

            EntityTypes.Registry reg = EntityTypes.load(conn);
            if (!reg.isKnownRel(relType)) {
                OutputProcessor.apiError(res, 400, "bad_request",
                    "relationship_type not registered: " + relType + ". Registered: " + String.join(", ", reg.knownRels())); return;
            }

            // Resolve both twins up front so a missing side gives a clear 404
            // instead of a silent no-op insert (the SELECT...FROM two tables
            // join pattern used during seeding just yields zero rows here).
            String[] from = lookupTwin(conn, fromId);
            if (from == null) {
                OutputProcessor.apiError(res, 404, "not_found", "from_external_id not found: " + fromId); return;
            }
            String[] to = lookupTwin(conn, toId);
            if (to == null) {
                OutputProcessor.apiError(res, 404, "not_found", "to_external_id not found: " + toId); return;
            }
            if (!"active".equals(from[1])) {
                OutputProcessor.apiError(res, 409, "twin_archived", "Twin is archived: " + fromId); return;
            }
            if (!"active".equals(to[1])) {
                OutputProcessor.apiError(res, 409, "twin_archived", "Twin is archived: " + toId); return;
            }
            String endpointError = reg.checkEndpoints(relType, from[2], to[2]);
            if (endpointError != null) {
                OutputProcessor.apiError(res, 400, "bad_request", endpointError); return;
            }
            String fromTwinId = from[0];
            String toTwinId = to[0];

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
                             "VALUES (?::uuid, ?::uuid, ?, ?::jsonb) " +
                             "ON CONFLICT (from_twin_id, to_twin_id, relationship_type) DO NOTHING RETURNING rel_id::text";
            try (PreparedStatement ps = conn.prepareStatement(insSql)) {
                ps.setString(1, fromTwinId);
                ps.setString(2, toTwinId);
                ps.setString(3, relType);
                ps.setString(4, meta.toJSONString());
                try (ResultSet rs = ps.executeQuery()) {
                    result.put("success", true);
                    if (rs.next()) {
                        result.put("rel_id",  rs.getString(1));
                        result.put("created", true);
                    } else {
                        // Lost a race with a concurrent identical POST: return the winner's edge.
                        try (PreparedStatement dup = conn.prepareStatement(dupSql)) {
                            dup.setString(1, fromTwinId);
                            dup.setString(2, toTwinId);
                            dup.setString(3, relType);
                            try (ResultSet drs = dup.executeQuery()) {
                                drs.next();
                                result.put("rel_id",  drs.getString(1));
                            }
                        }
                        result.put("created", false);
                    }
                }
            }
            OutputProcessor.send(res, 200, result);

        } catch (Exception e) {
            e.printStackTrace();
            OutputProcessor.apiError(res, 500, "server_error", e.getMessage());
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    /** {id, status, type} or null if no such twin. */
    private String[] lookupTwin(Connection conn, String externalId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id::text, status, type FROM digital_twins WHERE external_id = ?")) {
            ps.setString(1, externalId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new String[]{rs.getString(1), rs.getString(2), rs.getString(3)} : null;
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void get(HttpServletRequest req, HttpServletResponse res) {
        PoolDB pool = null; Connection conn = null;
        try {
            String from   = param(req, "from").replaceFirst("^@", "");
            String to     = param(req, "to").replaceFirst("^@", "");
            String type   = param(req, "type").toUpperCase().replaceAll("[^A-Z0-9_]", "_");
            String status = param(req, "status").toLowerCase();
            if (status.isEmpty()) status = "active";
            if (!status.equals("active") && !status.equals("archived") && !status.equals("all")) {
                OutputProcessor.apiError(res, 400, "bad_request", "status must be active, archived or all"); return;
            }

            StringBuilder sql = new StringBuilder(
                "SELECT r.rel_id::text, ft.external_id AS from_id, r.relationship_type, tt.external_id AS to_id, " +
                "r.metadata::text AS metadata, " +
                "CASE WHEN ft.status='active' AND tt.status='active' THEN 'active' ELSE 'archived' END AS status " +
                "FROM twin_relationships r " +
                "JOIN digital_twins ft ON ft.id = r.from_twin_id " +
                "JOIN digital_twins tt ON tt.id = r.to_twin_id WHERE 1=1");
            List<String> args = new ArrayList<>();
            if (!from.isEmpty()) { sql.append(" AND ft.external_id = ?"); args.add(from); }
            if (!to.isEmpty())   { sql.append(" AND tt.external_id = ?"); args.add(to); }
            if (!type.isEmpty()) { sql.append(" AND r.relationship_type = ?"); args.add(type); }
            if (status.equals("active"))        sql.append(" AND ft.status='active' AND tt.status='active'");
            else if (status.equals("archived")) sql.append(" AND (ft.status<>'active' OR tt.status<>'active')");
            sql.append(" ORDER BY r.created_at, r.rel_id");

            pool = new PoolDB(); conn = pool.getConnection();
            JSONArray rels = new JSONArray();
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < args.size(); i++) ps.setString(i + 1, args.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JSONObject r = new JSONObject();
                        r.put("rel_id",            rs.getString("rel_id"));
                        r.put("from_external_id",  rs.getString("from_id"));
                        r.put("relationship_type", rs.getString("relationship_type"));
                        r.put("to_external_id",    rs.getString("to_id"));
                        String md = rs.getString("metadata");
                        r.put("metadata",          md == null ? new JSONObject() : new JSONParser().parse(md));
                        r.put("status",            rs.getString("status"));
                        rels.add(r);
                    }
                }
            }
            JSONObject out = new JSONObject();
            out.put("success", true);
            out.put("relationships", rels);
            OutputProcessor.send(res, 200, out.toJSONString());
        } catch (Exception e) {
            e.printStackTrace();
            OutputProcessor.apiError(res, 500, "server_error", e.getMessage());
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void delete(HttpServletRequest req, HttpServletResponse res) {
        String p = req.getServletPath();
        String relId = p.length() > "/api/relationships/".length() ? p.substring("/api/relationships/".length()) : "";
        UUID id;
        try { id = UUID.fromString(relId); }
        catch (IllegalArgumentException e) {
            OutputProcessor.apiError(res, 400, "bad_request", "rel_id must be a UUID"); return;
        }
        PoolDB pool = null; Connection conn = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM twin_relationships WHERE rel_id = ?")) {
                ps.setObject(1, id);
                if (ps.executeUpdate() == 0) {
                    OutputProcessor.apiError(res, 404, "not_found", "Relationship not found: " + relId); return;
                }
            }
            JSONObject out = new JSONObject();
            out.put("success", true);
            out.put("rel_id", relId);
            OutputProcessor.send(res, 200, out.toJSONString());
        } catch (Exception e) {
            e.printStackTrace();
            OutputProcessor.apiError(res, 500, "server_error", e.getMessage());
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    private static String param(HttpServletRequest req, String name) {
        String v = req.getParameter(name);
        return v == null ? "" : v.trim();
    }

    private String str(JSONObject o, String key) {
        Object v = o.get(key);
        return v != null ? v.toString().trim() : "";
    }

    @Override public void put(HttpServletRequest q, HttpServletResponse s) {}
    @Override public boolean validate(String m, HttpServletRequest q, HttpServletResponse s) { return true; }
}
