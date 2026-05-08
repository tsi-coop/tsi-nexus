package org.tsicoop.nexus.api;

import org.tsicoop.nexus.framework.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * TSI Nexus: Interaction Stream Timeline
 *
 * GET /api/stream → paginated timeline with optional filters
 *   ?page=1       page number (default 1)
 *   ?limit=50     page size (default 50, max 100)
 *   ?owner=@id    filter by digital_twins.external_id (leading @ stripped)
 *   ?from=YYYY-MM-DD  filter created_at >= date
 *   ?to=YYYY-MM-DD    filter created_at < (date + 1 day)
 *   ?search=text  ILIKE filter on content
 */
public class Stream implements Action {

    /* ── GET ─────────────────────────────────────────────────────────────── */

    @Override
    @SuppressWarnings("unchecked")
    public void get(HttpServletRequest req, HttpServletResponse res) {
        PoolDB pool = null;
        Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            /* ── parse query parameters ──────────────────────────────────── */

            int page  = parseIntParam(req.getParameter("page"),  1);
            int limit = parseIntParam(req.getParameter("limit"), 50);
            if (page  < 1)   page  = 1;
            if (limit < 1)   limit = 1;
            if (limit > 100) limit = 100;

            String ownerParam  = req.getParameter("owner");
            String fromParam   = req.getParameter("from");
            String toParam     = req.getParameter("to");
            String searchParam = req.getParameter("search");

            // Strip leading '@' from owner filter
            String ownerFilter = null;
            if (ownerParam != null && !ownerParam.trim().isEmpty()) {
                ownerFilter = ownerParam.trim().replaceAll("^@+", "");
            }

            /* ── build WHERE clause dynamically ─────────────────────────── */

            List<String> conditions = new ArrayList<>();
            List<Object> params     = new ArrayList<>();

            if (ownerFilter != null && !ownerFilter.isEmpty()) {
                conditions.add("dt.external_id = ?");
                params.add(ownerFilter);
            }
            if (fromParam != null && !fromParam.trim().isEmpty()) {
                conditions.add("ist.created_at >= ?::date");
                params.add(fromParam.trim());
            }
            if (toParam != null && !toParam.trim().isEmpty()) {
                conditions.add("ist.created_at < (?::date + INTERVAL '1 day')");
                params.add(toParam.trim());
            }
            if (searchParam != null && !searchParam.trim().isEmpty()) {
                conditions.add("ist.content ILIKE ?");
                params.add("%" + searchParam.trim() + "%");
            }

            String whereClause = conditions.isEmpty()
                    ? ""
                    : "WHERE " + String.join(" AND ", conditions);

            int offset = (page - 1) * limit;

            /* ── main timeline query ─────────────────────────────────────── */

            String sql = "SELECT ist.id, " +
                         "dt.external_id, dt.type AS entity_type, " +
                         "ist.content, ist.intent_mapped, " +
                         "to_char(ist.created_at, 'DD Mon YYYY') AS created_fmt, " +
                         "to_char(ist.created_at, 'HH24:MI')     AS created_time " +
                         "FROM interaction_stream ist " +
                         "LEFT JOIN digital_twins dt ON dt.id = ist.owner_id " +
                         whereClause + " " +
                         "ORDER BY ist.id DESC " +
                         "LIMIT ? OFFSET ?";

            JSONArray entries = new JSONArray();

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int idx = 1;
                for (Object p : params) {
                    ps.setObject(idx++, p);
                }
                ps.setInt(idx++, limit);
                ps.setInt(idx,   offset);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JSONObject entry = new JSONObject();
                        entry.put("id",          rs.getLong("id"));
                        entry.put("external_id", rs.getString("external_id"));  // may be null for orphaned rows
                        entry.put("entity_type", rs.getString("entity_type"));  // may be null for orphaned rows
                        entry.put("content",     rs.getString("content"));
                        String intentMapped = rs.getString("intent_mapped");
                        if (intentMapped != null) {
                            entry.put("intent_mapped", intentMapped);
                        }
                        entry.put("created_fmt",  rs.getString("created_fmt"));
                        entry.put("created_time", rs.getString("created_time"));
                        entries.add(entry);
                    }
                }
            }

            boolean hasMore = (entries.size() == limit);

            /* ── global stats query (ignores page filters) ───────────────── */

            JSONObject stats = new JSONObject();
            String statsSql = "SELECT " +
                              "COUNT(*) AS total, " +
                              "COUNT(*) FILTER (WHERE created_at >= CURRENT_DATE) AS today, " +
                              "COUNT(DISTINCT owner_id) AS entities " +
                              "FROM interaction_stream";
            try (PreparedStatement ps = conn.prepareStatement(statsSql);
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    stats.put("total",    rs.getLong("total"));
                    stats.put("today",    rs.getLong("today"));
                    stats.put("entities", rs.getLong("entities"));
                }
            }

            /* ── assemble response ───────────────────────────────────────── */

            JSONObject out = new JSONObject();
            out.put("success",  true);
            out.put("entries",  entries);
            out.put("stats",    stats);
            out.put("page",     page);
            out.put("limit",    limit);
            out.put("has_more", hasMore);
            OutputProcessor.send(res, 200, out);

        } catch (Exception e) {
            e.printStackTrace();
            OutputProcessor.errorResponse(res, 500, "Fetch failed", e.getMessage(), req.getRequestURI());
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    /* ── POST (stub — stream entries are written via /capture) ──────────── */

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {}

    /* ── helpers ─────────────────────────────────────────────────────────── */

    private int parseIntParam(String value, int defaultValue) {
        if (value == null || value.trim().isEmpty()) return defaultValue;
        try { return Integer.parseInt(value.trim()); }
        catch (NumberFormatException ignored) { return defaultValue; }
    }

    @Override public void put(HttpServletRequest q, HttpServletResponse s) {}
    @Override public void delete(HttpServletRequest q, HttpServletResponse s) {}
    @Override public boolean validate(String m, HttpServletRequest q, HttpServletResponse s) { return true; }
}
