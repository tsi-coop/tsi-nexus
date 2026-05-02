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
import java.util.UUID;

/**
 * TSI Nexus: Universal Context Resolver
 * Assembles the "Institutional Memory" by weaving together the State Store (Postgres),
 * the Social/Industrial Graph (Relationships), and the Interaction Stream (History).
 */
public class Context implements Action {

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        try {
            JSONObject input = InputProcessor.getInput(req);
            String func = req.getHeader("X-DX-FUNCTION");

            if ("get_twin_context".equalsIgnoreCase(func)) {
                String externalId = (String) input.get("external_id");
                // Clean the @ prefix if present
                String cleanId = externalId.startsWith("@") ? externalId.substring(1) : externalId;
                OutputProcessor.send(res, 200, assembleFullContext(cleanId));
            } else {
                OutputProcessor.errorResponse(res, 400, "Bad Request", "Missing or invalid function.", req.getRequestURI());
            }
        } catch (Exception e) {
            OutputProcessor.errorResponse(res, 500, "Context Retrieval Failed", e.getMessage(), req.getRequestURI());
        }
    }

    /**
     * The "Graph-Walk" Method.
     * Fetches the Twin's state and its immediate network of relationships.
     */
    private JSONObject assembleFullContext(String externalId) throws SQLException {
        Connection conn = null; PreparedStatement pstmt = null; ResultSet rs = null; PoolDB pool = new PoolDB();
        JSONObject response = new JSONObject();
        JSONObject context = new JSONObject();

        try {
            conn = pool.getConnection();
            
            // Universal Query: Joins the Twin with its Graph Relationships
            // Uses a Left Join to ensure we get the twin even if it has no links yet.
            String sql = "SELECT t.id, t.type, t.current_state, t.updated_at, " +
                         "(SELECT json_agg(json_build_object('type', r.relationship_type, 'to', t2.external_id)) " +
                          "FROM twin_relationships r JOIN digital_twins t2 ON r.to_twin_id = t2.id " +
                          "WHERE r.from_twin_id = t.id) as out_links " +
                         "FROM digital_twins t WHERE t.external_id = ?";

            pstmt = conn.prepareStatement(sql);
            pstmt.setString(1, externalId);
            rs = pstmt.executeQuery();

            if (rs.next()) {
                UUID internalId = (UUID) rs.getObject("id");
                context.put("twin_id", internalId.toString());
                context.put("target", "@" + externalId);
                context.put("type", rs.getString("type"));
                context.put("state", rs.getString("current_state"));
                context.put("last_updated", rs.getTimestamp("updated_at").toString());
                
                // Attach Graph Data (The 'Links' that define institutional role)
                context.put("graph_links", rs.getString("out_links"));

                // Attach Interaction Stream (The 'History' that builds trust)
                context.put("recent_interactions", fetchInteractionStream(conn, internalId));
                
                response.put("success", true);
                response.put("context", context);
            } else {
                response.put("success", false);
                response.put("reason", "Entity not found in Institutional Memory.");
            }
        } finally {
            pool.cleanup(rs, pstmt, conn);
        }
        return response;
    }

    /**
     * Fetches recent semantic history for this specific node.
     */
    private JSONArray fetchInteractionStream(Connection conn, UUID twinId) throws SQLException {
        JSONArray logs = new JSONArray();
        String sql = "SELECT content, created_at FROM interaction_stream " +
                     "WHERE owner_id = ? ORDER BY created_at DESC LIMIT 5";
        
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setObject(1, twinId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    JSONObject entry = new JSONObject();
                    entry.put("content", rs.getString("content"));
                    entry.put("timestamp", rs.getTimestamp("created_at").toString());
                    logs.add(entry);
                }
            }
        }
        return logs;
    }

    @Override public void get(HttpServletRequest req, HttpServletResponse res) {}
    @Override public void put(HttpServletRequest req, HttpServletResponse res) {}
    @Override public void delete(HttpServletRequest req, HttpServletResponse res) {}
    @Override public boolean validate(String m, HttpServletRequest req, HttpServletResponse res) { return true; }
}