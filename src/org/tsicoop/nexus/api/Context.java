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
 * TSI Nexus: Context Resolver
 * Responsible for assembling the "Institutional Memory" for a Digital Twin.
 * Bridges the State Store, Interaction Stream (Vectors), and History.
 */
public class Context implements Action {

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        try {
            JSONObject input = InputProcessor.getInput(req);
            String func = req.getHeader("X-DX-FUNCTION");

            if (func == null || func.trim().isEmpty()) {
                OutputProcessor.errorResponse(res, 400, "Bad Request", "Missing function header.", req.getRequestURI());
                return;
            }

            switch (func.toLowerCase()) {
                case "get_twin_context":
                    // Assembles the full "Institutional Memory" for a twin
                    String externalId = (String) input.get("external_id");
                    OutputProcessor.send(res, 200, assembleFullContext(externalId));
                    break;

                case "get_interaction_history":
                    // Fetches recent semantic notes/chats for a twin
                    String twinId = (String) input.get("twin_id");
                    OutputProcessor.send(res, 200, fetchInteractionStream(UUID.fromString(twinId)));
                    break;

                default:
                    OutputProcessor.errorResponse(res, 400, "Unknown Function", func, req.getRequestURI());
            }
        } catch (SQLException e) {
            OutputProcessor.errorResponse(res, 500, "Database Error", e.getMessage(), req.getRequestURI());
        } catch (Exception e) {
            OutputProcessor.errorResponse(res, 500, "Internal Error", e.getMessage(), req.getRequestURI());
        }
    }

    /**
     * The "Collapse" Method: Joins Live State, History Count, and Metadata.
     */
    private JSONObject assembleFullContext(String externalId) throws SQLException {
        Connection conn = null; PreparedStatement pstmt = null; ResultSet rs = null; PoolDB pool = new PoolDB();
        JSONObject context = new JSONObject();

        try {
            conn = pool.getConnection();
            String sql = "SELECT id, type, current_state, version_count, updated_at " +
                         "FROM digital_twins WHERE external_id = ?";

            pstmt = conn.prepareStatement(sql);
            pstmt.setString(1, externalId);
            rs = pstmt.executeQuery();

            if (rs.next()) {
                UUID internalId = (UUID) rs.getObject("id");
                context.put("twin_id", internalId.toString());
                context.put("type", rs.getString("type"));
                context.put("state", rs.getString("current_state"));
                context.put("version", rs.getInt("version_count"));
                context.put("last_updated", rs.getTimestamp("updated_at").toString());

                // Nested Data: Attach recent semantic memory
                context.put("recent_interactions", fetchInteractionStream(internalId));
            }
        } finally { pool.cleanup(rs, pstmt, conn); }
        
        return new JSONObject() {{ put("success", true); put("context", context); }};
    }

    /**
     * Fetches the Interaction Stream (The Unstructured Memory Pillar).
     */
    private JSONArray fetchInteractionStream(UUID twinId) throws SQLException {
        JSONArray logs = new JSONArray();
        Connection conn = null; PreparedStatement pstmt = null; ResultSet rs = null; PoolDB pool = new PoolDB();
        try {
            conn = pool.getConnection();
            // Fetching recent 5 notes/sensor logs
            pstmt = conn.prepareStatement(
                "SELECT content, created_at FROM interaction_stream " +
                "WHERE owner_id = ? ORDER BY created_at DESC LIMIT 5"
            );
            pstmt.setObject(1, twinId);
            rs = pstmt.executeQuery();
            while (rs.next()) {
                JSONObject entry = new JSONObject();
                entry.put("content", rs.getString("content"));
                entry.put("timestamp", rs.getTimestamp("created_at").toString());
                logs.add(entry);
            }
        } finally { pool.cleanup(rs, pstmt, conn); }
        return logs;
    }

    @Override public void get(HttpServletRequest req, HttpServletResponse res) {}
    @Override public void put(HttpServletRequest req, HttpServletResponse res) {}
    @Override public void delete(HttpServletRequest req, HttpServletResponse res) {}
    
    @Override 
    public boolean validate(String method, HttpServletRequest req, HttpServletResponse res) {
        //return InputProcessor.validate(req, res);
        return true;
    }
}