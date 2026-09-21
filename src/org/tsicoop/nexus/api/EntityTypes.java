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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TSI Nexus: Entity Type Registry (read-only)
 *
 * GET /api/entity_types
 *   → { types: [{ type, required[], optional[], attributes[], system, count,
 *                 relationships: { outgoing:[{rel_type,to_type}], incoming:[{rel_type,from_type}] } }],
 *       relationship_types: [{ rel_type, from_type, to_type }] }
 *
 * Lets clients build register forms from Nexus instead of hardcoding fields. Types are declared
 * with /api/graph define_type (attributes, required, optional) and relationships with define_rel.
 * "Registered" means present in the registry only; types that exist solely as data are neither
 * accepted on create nor listed. The seeder registers the types and relationships it creates.
 *
 * This class also holds the registry lookups /api/twins and /api/relationships use to validate writes.
 */
public class EntityTypes implements Action {

    /** Snapshot of the type and relationship registries plus types/edges already present as data. */
    public static class Registry {
        final JSONObject types;
        final JSONArray rels;

        Registry(JSONObject types, JSONArray rels) { this.types = types; this.rels = rels; }

        public boolean isKnownType(String type) { return types.containsKey(type); }

        public boolean isKnownRel(String relType) {
            for (Object o : rels) if (relType.equals(((JSONObject) o).get("rel_type"))) return true;
            return false;
        }

        public List<String> knownTypes() {
            Set<String> all = new LinkedHashSet<>();
            for (Object k : types.keySet()) all.add((String) k);
            all.remove("system");
            return new ArrayList<>(all);
        }

        public List<String> knownRels() {
            Set<String> all = new LinkedHashSet<>();
            for (Object o : rels) all.add((String) ((JSONObject) o).get("rel_type"));
            return new ArrayList<>(all);
        }

        public List<String> required(String type) { return strings(type, "required"); }
        public List<String> optional(String type) { return strings(type, "optional"); }

        private List<String> strings(String type, String key) {
            List<String> out = new ArrayList<>();
            if (types.get(type) instanceof JSONObject) {
                Object v = ((JSONObject) types.get(type)).get(key);
                if (v instanceof JSONArray) for (Object o : (JSONArray) v) out.add(String.valueOf(o));
            }
            return out;
        }

        /** Registered {from_type, to_type} pairs for a relationship type; empty means unconstrained. */
        public List<String[]> endpoints(String relType) {
            List<String[]> out = new ArrayList<>();
            for (Object o : rels) {
                JSONObject r = (JSONObject) o;
                if (relType.equals(r.get("rel_type"))) {
                    String[] pair = {String.valueOf(r.get("from_type")), String.valueOf(r.get("to_type"))};
                    boolean dup = false;
                    for (String[] p : out) if (p[0].equals(pair[0]) && p[1].equals(pair[1])) dup = true;
                    if (!dup) out.add(pair);
                }
            }
            return out;
        }

        /** Null if valid; otherwise a message naming the expected types. */
        public String checkEndpoints(String relType, String fromType, String toType) {
            List<String[]> allowed = endpoints(relType);
            if (allowed.isEmpty()) return null;
            for (String[] p : allowed) if (p[0].equals(fromType) && p[1].equals(toType)) return null;
            StringBuilder exp = new StringBuilder();
            for (String[] p : allowed) {
                if (exp.length() > 0) exp.append(" or ");
                exp.append(p[0]).append(" -> ").append(p[1]);
            }
            return relType + " requires " + exp + "; got " + fromType + " -> " + toType;
        }

        /** Null if state has a non-blank value for every required field of the type. */
        public String checkRequired(String type, JSONObject state) {
            List<String> missing = new ArrayList<>();
            for (String f : required(type)) {
                Object v = state.get(f);
                if (v == null || v.toString().isBlank()) missing.add(f);
            }
            return missing.isEmpty() ? null
                : type + " requires " + String.join(", ", missing) + " (required: " + String.join(", ", required(type)) + ")";
        }
    }

    @SuppressWarnings("unchecked")
    public static Registry load(Connection conn) throws Exception {
        JSONObject types = new JSONObject();
        JSONArray rels = new JSONArray();
        try (PreparedStatement ps = conn.prepareStatement("SELECT config::text FROM root_organisation LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next() && rs.getString(1) != null) {
                JSONObject cfg = (JSONObject) new JSONParser().parse(rs.getString(1));
                if (cfg.get("type_registry") instanceof JSONObject) types = (JSONObject) cfg.get("type_registry");
                if (cfg.get("relationship_registry") instanceof JSONArray) rels = (JSONArray) cfg.get("relationship_registry");
            }
        }
        return new Registry(types, rels);
    }

    /** Adds a type to the registry if absent; never overwrites an existing definition. */
    public static void registerType(Connection conn, String type) throws Exception {
        JSONObject def = new JSONObject();
        def.put("attributes", new JSONArray());
        def.put("required", new JSONArray());
        def.put("optional", new JSONArray());
        def.put("defined_at", java.time.Instant.now().toString());
        JSONObject entry = new JSONObject();
        entry.put(type, def);
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE root_organisation SET config = jsonb_set(COALESCE(config,'{}'), '{type_registry}', " +
                "?::jsonb || COALESCE(config->'type_registry','{}'))")) {
            ps.setString(1, entry.toJSONString());
            ps.executeUpdate();
        }
    }

    /** Adds a from_type/rel_type/to_type triple to the registry if absent. */
    public static void registerRel(Connection conn, String fromType, String relType, String toType) throws Exception {
        JSONObject def = new JSONObject();
        def.put("from_type", fromType);
        def.put("rel_type", relType);
        def.put("to_type", toType);
        JSONArray entry = new JSONArray();
        entry.add(def);
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE root_organisation SET config = jsonb_set(COALESCE(config,'{}'), '{relationship_registry}', " +
                "COALESCE(config->'relationship_registry','[]') || ?::jsonb) " +
                "WHERE NOT (COALESCE(config->'relationship_registry','[]') @> ?::jsonb)")) {
            ps.setString(1, entry.toJSONString());
            ps.setString(2, entry.toJSONString());
            ps.executeUpdate();
        }
    }

    /* ── GET ─────────────────────────────────────────────────────────────── */

    @Override
    @SuppressWarnings("unchecked")
    public void get(HttpServletRequest req, HttpServletResponse res) {
        PoolDB pool = null; Connection conn = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            Registry reg = load(conn);

            Map<String, Long> counts = new LinkedHashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT type, COUNT(*) FROM digital_twins WHERE status='active' GROUP BY type");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) counts.put(rs.getString(1), rs.getLong(2));
            }

            JSONArray types = new JSONArray();
            for (String t : reg.knownTypes()) {
                JSONObject def = reg.types.get(t) instanceof JSONObject ? (JSONObject) reg.types.get(t) : new JSONObject();
                JSONObject o = new JSONObject();
                o.put("type",       t);
                o.put("required",   toArray(reg.required(t)));
                o.put("optional",   toArray(reg.optional(t)));
                o.put("attributes", def.get("attributes") instanceof JSONArray ? def.get("attributes") : new JSONArray());
                o.put("system",     Boolean.TRUE.equals(def.get("system")));
                o.put("count",      counts.getOrDefault(t, 0L));

                JSONArray out = new JSONArray(), in = new JSONArray();
                for (Object ro : reg.rels) {
                    JSONObject r = (JSONObject) ro;
                    if (t.equals(r.get("from_type"))) out.add(pair("rel_type", r.get("rel_type"), "to_type", r.get("to_type")));
                    if (t.equals(r.get("to_type")))   in.add(pair("rel_type", r.get("rel_type"), "from_type", r.get("from_type")));
                }
                JSONObject links = new JSONObject();
                links.put("outgoing", out);
                links.put("incoming", in);
                o.put("relationships", links);
                types.add(o);
            }

            JSONArray relTypes = new JSONArray();
            for (String rt : reg.knownRels()) {
                List<String[]> eps = reg.endpoints(rt);
                if (eps.isEmpty()) {
                    JSONObject r = new JSONObject();
                    r.put("rel_type", rt); r.put("from_type", null); r.put("to_type", null);
                    relTypes.add(r);
                }
                for (String[] p : eps) {
                    JSONObject r = new JSONObject();
                    r.put("rel_type", rt); r.put("from_type", p[0]); r.put("to_type", p[1]);
                    relTypes.add(r);
                }
            }

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("types", types);
            result.put("relationship_types", relTypes);
            OutputProcessor.send(res, 200, result.toJSONString());
        } catch (Exception e) {
            e.printStackTrace();
            OutputProcessor.apiError(res, 500, "server_error", e.getMessage());
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    @SuppressWarnings("unchecked")
    private static JSONArray toArray(List<String> l) { JSONArray a = new JSONArray(); a.addAll(l); return a; }

    @SuppressWarnings("unchecked")
    private static JSONObject pair(String k1, Object v1, String k2, Object v2) {
        JSONObject o = new JSONObject(); o.put(k1, v1); o.put(k2, v2); return o;
    }

    @Override public void post(HttpServletRequest q, HttpServletResponse s) {
        OutputProcessor.apiError(s, 405, "method_not_allowed", "Use /api/graph define_type / define_rel");
    }
    @Override public void put(HttpServletRequest q, HttpServletResponse s) {}
    @Override public void delete(HttpServletRequest q, HttpServletResponse s) {}
    @Override public boolean validate(String m, HttpServletRequest q, HttpServletResponse s) { return true; }
}
