package org.tsicoop.nexus.framework;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Live, domain-agnostic schema context for LLM prompts that generate SQL
 * (Policy.java admin-authored guardrails, Analytics.java ad-hoc queries).
 * Reads the actual current entity types, their JSONB field names, and
 * relationship types straight from the data - never hardcoded.
 */
public class SchemaIntrospector {

    public static String buildContext(Connection conn) {
        StringBuilder sb = new StringBuilder();

        // Field population frequency per type, most-populated first. Seeding batches for the
        // same entity type can drift and write the same concept under different keys (e.g.
        // margin_pct vs margin_percentage) - annotating each field with how many of the type's
        // rows actually have it lets the LLM prefer the dominant name instead of guessing.
        String sql =
            "SELECT dt.type, tt.entity_count, keys.key AS field_name, COUNT(*) AS field_count " +
            "FROM digital_twins dt " +
            "JOIN LATERAL jsonb_object_keys(dt.current_state) AS keys(key) ON true " +
            "JOIN (SELECT type, COUNT(*) AS entity_count FROM digital_twins WHERE type != 'system' GROUP BY type) tt " +
            "  ON tt.type = dt.type " +
            "WHERE dt.type != 'system' " +
            "GROUP BY dt.type, tt.entity_count, keys.key " +
            "ORDER BY dt.type, field_count DESC";

        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            String currentType = null;
            long currentTotal = 0;
            StringBuilder fields = new StringBuilder();
            while (rs.next()) {
                String type = rs.getString("type");
                if (!type.equals(currentType)) {
                    if (currentType != null) appendEntityLine(sb, currentType, currentTotal, fields);
                    currentType = type;
                    currentTotal = rs.getLong("entity_count");
                    fields.setLength(0);
                }
                if (fields.length() > 0) fields.append(", ");
                fields.append(rs.getString("field_name")).append('(').append(rs.getLong("field_count")).append('/').append(currentTotal).append(')');
            }
            if (currentType != null) appendEntityLine(sb, currentType, currentTotal, fields);
        } catch (Exception ignore) {}

        String relSql = "SELECT DISTINCT relationship_type FROM twin_relationships ORDER BY relationship_type";
        try (PreparedStatement ps = conn.prepareStatement(relSql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                sb.append("  relationship_type='").append(rs.getString("relationship_type")).append("'\n");
            }
        } catch (Exception ignore) {}

        return sb.toString();
    }

    private static void appendEntityLine(StringBuilder sb, String type, long total, StringBuilder fields) {
        sb.append("  entity type='").append(type).append("' count=").append(total)
          .append(" fields=[").append(fields).append("]\n");
    }
}
