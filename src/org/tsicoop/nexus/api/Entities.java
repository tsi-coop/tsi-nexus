package org.tsicoop.nexus.api;

import org.tsicoop.nexus.framework.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * TSI Nexus: Entity Directory API
 *
 * Returns entity types present in this deployment, with a sample of entities
 * per type. Drives the Liquid sidebar dynamically — no hardcoded names or
 * type labels anywhere in the frontend.
 *
 * GET /api/entities
 *   → { entity_types: [ { type, label_plural, count, sample: [{name, handle}] } ] }
 */
public class Entities implements Action {

    private static final int SAMPLE_SIZE = 8;

    @Override
    @SuppressWarnings("unchecked")
    public void get(HttpServletRequest req, HttpServletResponse res) {
        PoolDB pool = null;
        Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            JSONArray entityTypes = new JSONArray();
            String typesSql =
                "SELECT type, COUNT(*) AS cnt " +
                "FROM digital_twins " +
                "WHERE type != 'system' " +
                "GROUP BY type " +
                "ORDER BY MIN(created_at)";
            try (PreparedStatement ps = conn.prepareStatement(typesSql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String type  = rs.getString("type");
                    long   count = rs.getLong("cnt");
                    JSONObject group = new JSONObject();
                    group.put("type",   type);
                    group.put("count",  count);
                    group.put("sample", fetchSample(conn, type));
                    entityTypes.add(group);
                }
            }

            JSONArray commands = new JSONArray();
            String cmdSql = "SELECT command_verb, label, args_hint, hint, component_type " +
                            "FROM command_manifest WHERE is_active = TRUE ORDER BY command_verb";
            try (PreparedStatement ps = conn.prepareStatement(cmdSql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JSONObject cmd = new JSONObject();
                    cmd.put("command_verb",   rs.getString("command_verb"));
                    cmd.put("label",          rs.getString("label"));
                    cmd.put("args_hint",      rs.getString("args_hint"));
                    cmd.put("hint",           rs.getString("hint"));
                    cmd.put("component_type", rs.getString("component_type"));
                    commands.add(cmd);
                }
            }

            JSONObject out = new JSONObject();
            out.put("success",      true);
            out.put("entity_types", entityTypes);
            out.put("commands",     commands);
            OutputProcessor.send(res, 200, out);

        } catch (Exception e) {
            e.printStackTrace();
            OutputProcessor.errorResponse(res, 500, "Entity fetch failed", e.getMessage(), req.getRequestURI());
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    @SuppressWarnings("unchecked")
    private JSONArray fetchSample(Connection conn, String type) throws Exception {
        JSONArray sample = new JSONArray();
        String sql =
            "SELECT external_id, current_state->>'name' AS name " +
            "FROM digital_twins " +
            "WHERE type = ? " +
            "ORDER BY current_state->>'name' NULLS LAST " +
            "LIMIT ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, type);
            ps.setInt(2, SAMPLE_SIZE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JSONObject e = new JSONObject();
                    String name = rs.getString("name");
                    e.put("handle", "@" + rs.getString("external_id"));
                    e.put("name",   name != null ? name : rs.getString("external_id"));
                    sample.add(e);
                }
            }
        }
        return sample;
    }

    @Override public void post(HttpServletRequest q, HttpServletResponse s) {}
    @Override public void put(HttpServletRequest q, HttpServletResponse s) {}
    @Override public void delete(HttpServletRequest q, HttpServletResponse s) {}
    @Override public boolean validate(String m, HttpServletRequest q, HttpServletResponse s) { return true; }
}
