package org.tsicoop.nexus.api;

import org.tsicoop.nexus.framework.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.StringJoiner;

/**
 * POST /api/ingest
 * External systems push data into Nexus to update twin state.
 * Auth is validated via service_registry auth_config header/secret.
 */
public class Ingest implements Action {

    @Override
    @SuppressWarnings("unchecked")
    public void post(HttpServletRequest req, HttpServletResponse res) {
        PoolDB pool = null;
        Connection conn = null;
        try {
            pool = new PoolDB();
            JSONObject input     = InputProcessor.getInput(req);
            String identifier    = str(input, "identifier");
            String externalId    = str(input, "external_id");
            JSONObject data      = (JSONObject) input.get("data");

            if (identifier.isEmpty() || externalId.isEmpty() || data == null || data.isEmpty()) {
                OutputProcessor.errorResponse(res, 400, "Bad request",
                    "identifier, external_id, and data are required", req.getRequestURI()); return;
            }

            conn = pool.getConnection();

            // 1. Look up the INGEST service
            String authConfigJson = null;
            String sql = "SELECT auth_config::text FROM service_registry " +
                         "WHERE identifier=? AND service_type='INGEST' AND status='Active'";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, identifier.toUpperCase());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) authConfigJson = rs.getString("auth_config");
                }
            }
            if (authConfigJson == null) {
                OutputProcessor.errorResponse(res, 403, "Forbidden",
                    "Unknown or inactive ingest source", req.getRequestURI()); return;
            }

            // 2. Validate auth header
            JSONObject cfg     = (JSONObject) new JSONParser().parse(authConfigJson);
            String headerName  = (String) cfg.get("header");
            String headerSecret = (String) cfg.get("secret");
            String provided    = req.getHeader(headerName);
            if (provided == null || !provided.equals(headerSecret)) {
                OutputProcessor.errorResponse(res, 403, "Forbidden",
                    "Invalid ingest key", req.getRequestURI()); return;
            }

            // 3. Patch twin state
            String patchSql = "UPDATE digital_twins SET current_state = COALESCE(current_state,'{}') || ?::jsonb " +
                              "WHERE external_id=?";
            try (PreparedStatement ps = conn.prepareStatement(patchSql)) {
                ps.setString(1, data.toJSONString());
                ps.setString(2, externalId);
                if (ps.executeUpdate() == 0) {
                    OutputProcessor.errorResponse(res, 404, "Not found",
                        "Entity not found: " + externalId, req.getRequestURI()); return;
                }
            }

            // 4. Build human-readable stream content
            StringJoiner sj = new StringJoiner(", ");
            for (Object key : data.keySet()) sj.add(key + "=" + data.get(key));
            String content = "Ingest via " + identifier.toUpperCase() + ": " + sj.toString();

            // 5. Append to interaction_stream
            String streamSql = "INSERT INTO interaction_stream (owner_id, content, intent_mapped) " +
                                "SELECT id, ?, ? FROM digital_twins WHERE external_id=?";
            try (PreparedStatement ps = conn.prepareStatement(streamSql)) {
                ps.setString(1, content);
                ps.setString(2, identifier.toLowerCase());
                ps.setString(3, externalId);
                ps.executeUpdate();
            }

            JSONObject result = new JSONObject();
            result.put("success",        true);
            result.put("external_id",    externalId);
            result.put("fields_updated", (long) data.size());
            OutputProcessor.send(res, 200, result);

        } catch (Exception e) {
            e.printStackTrace();
            OutputProcessor.errorResponse(res, 500, "Ingest failed", e.getMessage(), req.getRequestURI());
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
