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
import java.util.HashSet;
import java.util.Set;

/**
 * TSI Nexus: Digital Twin Graph API
 *
 * GET  /api/graph  → blueprints (data + registry) and relationships (data + registry)
 * POST /api/graph  { action:"define_type", type_key, attributes[] }
 * POST /api/graph  { action:"define_rel",  from_type, rel_type, to_type }
 *
 * Definitions are stored in root_organisation.config as:
 *   config.type_registry         = { "type_key": { attributes:[], defined_at:"" } }
 *   config.relationship_registry = [ { from_type, rel_type, to_type } ]
 */
public class Graph implements Action {

    /* ── GET ─────────────────────────────────────────────────────────────── */

    @Override
    @SuppressWarnings("unchecked")
    public void get(HttpServletRequest req, HttpServletResponse res) {
        PoolDB pool = null;
        Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            JSONObject typeReg = new JSONObject();
            JSONArray  relReg  = new JSONArray();
            String cfgStr = loadConfig(conn);
            if (cfgStr != null) {
                JSONObject cfg = (JSONObject) new JSONParser().parse(cfgStr);
                if (cfg.get("type_registry") instanceof JSONObject)  typeReg = (JSONObject) cfg.get("type_registry");
                if (cfg.get("relationship_registry") instanceof JSONArray) relReg = (JSONArray) cfg.get("relationship_registry");
            }

            JSONObject out = new JSONObject();
            out.put("success",       true);
            out.put("blueprints",    fetchBlueprints(conn, typeReg));
            out.put("relationships", fetchRelationships(conn, relReg));
            OutputProcessor.send(res, 200, out);

        } catch (Exception e) {
            e.printStackTrace();
            OutputProcessor.errorResponse(res, 500, "Graph fetch failed", e.getMessage(), req.getRequestURI());
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    /* ── POST ────────────────────────────────────────────────────────────── */

    @Override
    @SuppressWarnings("unchecked")
    public void post(HttpServletRequest req, HttpServletResponse res) {
        PoolDB pool = null;
        Connection conn = null;
        try {
            pool = new PoolDB();
            JSONObject input = InputProcessor.getInput(req);
            String action = (String) input.get("action");
            conn = pool.getConnection();

            if ("define_type".equals(action)) {
                defineType(conn, req, res, input);
            } else if ("define_rel".equals(action)) {
                defineRel(conn, req, res, input);
            } else {
                OutputProcessor.errorResponse(res, 400, "Bad request", "Unknown action", req.getRequestURI());
            }
        } catch (Exception e) {
            e.printStackTrace();
            OutputProcessor.errorResponse(res, 500, "Operation failed", e.getMessage(), req.getRequestURI());
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    /* ── define_type ─────────────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void defineType(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String raw = (String) input.get("type_key");
        if (raw == null || raw.isBlank()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "type_key is required", req.getRequestURI());
            return;
        }
        String typeKey = raw.trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");

        JSONArray attrs = input.get("attributes") instanceof JSONArray ? (JSONArray) input.get("attributes") : new JSONArray();

        JSONObject typeDef = new JSONObject();
        typeDef.put("attributes",  attrs);
        typeDef.put("defined_at",  java.time.Instant.now().toString());

        JSONObject entry = new JSONObject();
        entry.put(typeKey, typeDef);

        String sql = "UPDATE root_organisation SET config = jsonb_set(" +
                     "  COALESCE(config,'{}'), '{type_registry}'," +
                     "  COALESCE(config->'type_registry','{}') || ?::jsonb)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, entry.toJSONString());
            ps.executeUpdate();
        }

        JSONObject result = new JSONObject();
        result.put("success",  true);
        result.put("type_key", typeKey);
        OutputProcessor.send(res, 200, result);
    }

    /* ── define_rel ──────────────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void defineRel(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject input) throws Exception {
        String fromType = (String) input.get("from_type");
        String relType  = (String) input.get("rel_type");
        String toType   = (String) input.get("to_type");

        if (fromType == null || fromType.isBlank() || relType == null || relType.isBlank() || toType == null || toType.isBlank()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "from_type, rel_type and to_type are required", req.getRequestURI());
            return;
        }

        fromType = fromType.trim().toLowerCase();
        relType  = relType.trim().toUpperCase().replaceAll("[^A-Z0-9_]", "_");
        toType   = toType.trim().toLowerCase();

        JSONObject relDef = new JSONObject();
        relDef.put("from_type", fromType);
        relDef.put("rel_type",  relType);
        relDef.put("to_type",   toType);

        JSONArray entry = new JSONArray();
        entry.add(relDef);

        String sql = "UPDATE root_organisation SET config = jsonb_set(" +
                     "  COALESCE(config,'{}'), '{relationship_registry}'," +
                     "  COALESCE(config->'relationship_registry','[]') || ?::jsonb)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, entry.toJSONString());
            ps.executeUpdate();
        }

        JSONObject result = new JSONObject();
        result.put("success",  true);
        result.put("rel_type", relType);
        OutputProcessor.send(res, 200, result);
    }

    /* ── fetch helpers ───────────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private JSONArray fetchBlueprints(Connection conn, JSONObject typeReg) throws Exception {
        JSONArray result = new JSONArray();
        Set<String> seen = new HashSet<>();

        String sql = "SELECT type, COUNT(*) AS cnt FROM digital_twins " +
                     "WHERE type != 'system' GROUP BY type ORDER BY MIN(created_at)";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String type = rs.getString("type");
                seen.add(type);
                JSONObject bp = new JSONObject();
                bp.put("type",       type);
                bp.put("count",      rs.getLong("cnt"));
                bp.put("attributes", fetchAttributes(conn, type));
                bp.put("source",     "data");
                result.add(bp);
            }
        }

        // Registry-defined types with no twins yet
        for (Object k : typeReg.keySet()) {
            String typeKey = (String) k;
            if (seen.contains(typeKey)) continue;
            JSONObject reg   = (JSONObject) typeReg.get(typeKey);
            JSONArray  attrs = reg.get("attributes") instanceof JSONArray ? (JSONArray) reg.get("attributes") : new JSONArray();
            JSONObject bp = new JSONObject();
            bp.put("type",       typeKey);
            bp.put("count",      0L);
            bp.put("attributes", attrs);
            bp.put("source",     "registry");
            result.add(bp);
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private JSONArray fetchAttributes(Connection conn, String type) throws Exception {
        JSONArray attrs = new JSONArray();
        String sql = "SELECT DISTINCT jsonb_object_keys(current_state) AS k " +
                     "FROM digital_twins WHERE type = ? LIMIT 50";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, type);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) attrs.add(rs.getString("k"));
            }
        }
        return attrs;
    }

    @SuppressWarnings("unchecked")
    private JSONArray fetchRelationships(Connection conn, JSONArray relReg) throws Exception {
        JSONArray result = new JSONArray();
        Set<String> seen = new HashSet<>();

        String sql =
            "SELECT ft.type AS from_type, tr.relationship_type, tt.type AS to_type, COUNT(*) AS cnt " +
            "FROM twin_relationships tr " +
            "JOIN digital_twins ft ON ft.id = tr.from_twin_id " +
            "JOIN digital_twins tt ON tt.id = tr.to_twin_id " +
            "GROUP BY ft.type, tr.relationship_type, tt.type ORDER BY cnt DESC";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String from = rs.getString("from_type");
                String rel  = rs.getString("relationship_type");
                String to   = rs.getString("to_type");
                seen.add(from + "||" + rel + "||" + to);
                JSONObject r = new JSONObject();
                r.put("from_type", from);
                r.put("rel_type",  rel);
                r.put("to_type",   to);
                r.put("count",     rs.getLong("cnt"));
                r.put("source",    "data");
                result.add(r);
            }
        }

        // Registry-defined relationships with no edges yet
        for (Object obj : relReg) {
            JSONObject reg  = (JSONObject) obj;
            String from = (String) reg.get("from_type");
            String rel  = (String) reg.get("rel_type");
            String to   = (String) reg.get("to_type");
            if (seen.contains(from + "||" + rel + "||" + to)) continue;
            JSONObject r = new JSONObject();
            r.put("from_type", from);
            r.put("rel_type",  rel);
            r.put("to_type",   to);
            r.put("count",     0L);
            r.put("source",    "registry");
            result.add(r);
        }

        return result;
    }

    private String loadConfig(Connection conn) throws Exception {
        String sql = "SELECT config::text FROM root_organisation LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString("config") : null;
        }
    }

    @Override public void put(HttpServletRequest q, HttpServletResponse s) {}
    @Override public void delete(HttpServletRequest q, HttpServletResponse s) {}
    @Override public boolean validate(String m, HttpServletRequest q, HttpServletResponse s) { return true; }
}
