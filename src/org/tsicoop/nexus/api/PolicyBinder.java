package org.tsicoop.nexus.api;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.json.simple.parser.JSONParser;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared binder for policy_manifest SQL. Placeholders bind as text, in order:
 * the target external id(s), then source[k1..kn] for the policy's param_keys.
 * Missing / null values bind SQL NULL; SQL casts explicitly (e.g. ?::numeric).
 */
public final class PolicyBinder {
    private PolicyBinder() {}

    /** Parses the param_keys JSONB text into an ordered list ([] when null/invalid). */
    public static List<String> parseKeys(String json) {
        List<String> keys = new ArrayList<>();
        if (json == null || json.isBlank()) return keys;
        try {
            Object o = new JSONParser().parse(json);
            if (o instanceof JSONArray) for (Object k : (JSONArray) o) if (k != null) keys.add(k.toString());
        } catch (Exception ignore) {}
        return keys;
    }

    /** Binds targets at 1..t, then source values for each key at t+1... */
    public static void bind(PreparedStatement ps, String[] targets, List<String> paramKeys, JSONObject source)
            throws SQLException {
        int idx = 1;
        for (String t : targets) ps.setString(idx++, t);
        if (paramKeys == null) return;
        for (String key : paramKeys) {
            String v = toText(source == null ? null : source.get(key));
            if (v == null) ps.setNull(idx++, Types.VARCHAR);
            else ps.setString(idx++, v);
        }
    }

    static String toText(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean) return v.toString();
        if (v instanceof Number) {
            try { return new BigDecimal(v.toString()).toPlainString(); }
            catch (NumberFormatException e) { return v.toString(); }
        }
        if (v instanceof JSONObject || v instanceof JSONArray) return JSONValue.toJSONString(v);
        return v.toString();
    }
}
