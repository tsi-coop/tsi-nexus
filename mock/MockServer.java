import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.*;
import java.util.*;

/**
 * TSI Nexus - External Mock PULL Service
 *
 * Serves deterministic synthetic data for any registered entity type.
 * Field definitions are driven entirely by mock-data.json — no domain logic here.
 *
 * Usage:
 *   java MockServer.java [path/to/mock-data.json]
 *
 * Download mock-data.json from the Seeding page in the TSI Nexus admin after seeding.
 * Register each entity type as a PULL service in Service Registry (done automatically by seeder):
 *   Base URL: http://localhost:9090/{entity_type}
 *   Auth:     X-Mock-Key: nexus-demo
 */
public class MockServer {

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        String configPath = args.length > 0 ? args[0] : "mock-data.json";
        System.out.println("[MockServer] Reading config: " + Paths.get(configPath).toAbsolutePath());

        String src = new String(Files.readAllBytes(Paths.get(configPath)));
        Map<String,Object> config = parseObject(src.trim(), new int[]{0});

        long port       = toLong(config.get("port"), 9090L);
        String authHdr  = str(config, "auth_header", "X-Mock-Key");
        String authSec  = str(config, "auth_secret",  "nexus-demo");

        Map<String,Object> services = (Map<String,Object>) config.getOrDefault("services", new LinkedHashMap<>());

        HttpServer server = HttpServer.create(new InetSocketAddress((int) port), 0);
        server.createContext("/", new MockHandler(authHdr, authSec, services));
        server.setExecutor(null);
        server.start();

        System.out.println("[MockServer] Listening on port " + port);
        System.out.println("[MockServer] Auth header : " + authHdr);
        System.out.println("[MockServer] Entity types: " + String.join(", ", services.keySet()));
        System.out.println("[MockServer] Example     : GET http://localhost:" + port + "/{entityType}/{externalId}");
        System.out.println("[MockServer] Press Ctrl+C to stop.");
    }

    /* ── HTTP handler ──────────────────────────────────────────────────── */

    static class MockHandler implements HttpHandler {
        private final String authHeader;
        private final String authSecret;
        private final Map<String,Object> services;

        MockHandler(String authHeader, String authSecret, Map<String,Object> services) {
            this.authHeader = authHeader;
            this.authSecret = authSecret;
            this.services   = services;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void handle(HttpExchange ex) throws IOException {
            try {
                if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                    send(ex, 405, "{\"error\":\"Method not allowed\"}");
                    return;
                }

                String provided = ex.getRequestHeaders().getFirst(authHeader);
                if (!authSecret.equals(provided)) {
                    send(ex, 403, "{\"error\":\"Unauthorized\"}");
                    return;
                }

                // Path: /{entityType}/{externalId}
                String path  = ex.getRequestURI().getPath().replaceFirst("^/+", "");
                String[] seg = path.split("/", 2);
                if (seg.length < 2 || seg[0].isBlank() || seg[1].isBlank()) {
                    send(ex, 400, "{\"error\":\"Expected /{entityType}/{externalId}\"}");
                    return;
                }
                String entityType = seg[0];
                String externalId = seg[1];

                if (!services.containsKey(entityType)) {
                    send(ex, 404, "{\"error\":\"No mock service for: " + entityType + "\"}");
                    return;
                }

                Map<String,Object> svc    = (Map<String,Object>) services.get(entityType);
                Map<String,Object> fields = (Map<String,Object>) svc.getOrDefault("fields", new LinkedHashMap<>());

                // Stable values: seed RNG from externalId hash so same entity always returns same values
                Random rng = new Random((long) externalId.hashCode());
                StringBuilder json = new StringBuilder("{");
                boolean first = true;
                for (Map.Entry<String,Object> e : fields.entrySet()) {
                    if (!first) json.append(",");
                    first = false;
                    Object val = generateValue((Map<String,Object>) e.getValue(), rng);
                    json.append("\"").append(e.getKey()).append("\":");
                    if (val instanceof String) json.append("\"").append(val).append("\"");
                    else json.append(val);
                }
                json.append("}");
                send(ex, 200, json.toString());

            } catch (Exception e) {
                send(ex, 500, "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
            }
        }

        private void send(HttpExchange ex, int code, String body) throws IOException {
            byte[] bytes = body.getBytes("UTF-8");
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        }
    }

    /* ── value generator ───────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    static Object generateValue(Map<String,Object> def, Random rng) {
        if (def == null) return "N/A";
        switch (str(def, "type", "text")) {
            case "int": {
                long min = toLong(def.get("min"), 0L);
                long max = toLong(def.get("max"), 100L);
                return min + (Math.abs(rng.nextLong()) % (max - min + 1));
            }
            case "enum": {
                List<Object> vals = (List<Object>) def.getOrDefault("values", List.of("N/A"));
                return vals.get(Math.abs(rng.nextInt(vals.size())));
            }
            case "date": {
                long daysAgo = Math.abs(rng.nextLong()) % 180;
                return LocalDate.now().minusDays(daysAgo).format(DateTimeFormatter.ISO_LOCAL_DATE);
            }
            default:
                return "REF-" + Long.toHexString(Math.abs(rng.nextLong())).substring(0, 6).toUpperCase();
        }
    }

    /* ── minimal JSON parser ───────────────────────────────────────────── */

    static Map<String,Object> parseObject(String s, int[] p) {
        Map<String,Object> map = new LinkedHashMap<>();
        p[0]++; // skip {
        skipWs(s, p);
        while (p[0] < s.length() && s.charAt(p[0]) != '}') {
            String key = parseString(s, p);
            skipWs(s, p);
            p[0]++; // skip :
            map.put(key, parseValue(s, p));
            skipWs(s, p);
            if (p[0] < s.length() && s.charAt(p[0]) == ',') p[0]++;
            skipWs(s, p);
        }
        if (p[0] < s.length()) p[0]++; // skip }
        return map;
    }

    static List<Object> parseArray(String s, int[] p) {
        List<Object> list = new ArrayList<>();
        p[0]++; // skip [
        skipWs(s, p);
        while (p[0] < s.length() && s.charAt(p[0]) != ']') {
            list.add(parseValue(s, p));
            skipWs(s, p);
            if (p[0] < s.length() && s.charAt(p[0]) == ',') p[0]++;
            skipWs(s, p);
        }
        if (p[0] < s.length()) p[0]++; // skip ]
        return list;
    }

    static Object parseValue(String s, int[] p) {
        skipWs(s, p);
        if (p[0] >= s.length()) return null;
        char c = s.charAt(p[0]);
        if (c == '{') return parseObject(s, p);
        if (c == '[') return parseArray(s, p);
        if (c == '"') return parseString(s, p);
        if (s.startsWith("true",  p[0])) { p[0] += 4; return Boolean.TRUE; }
        if (s.startsWith("false", p[0])) { p[0] += 5; return Boolean.FALSE; }
        if (s.startsWith("null",  p[0])) { p[0] += 4; return null; }
        return parseNumber(s, p);
    }

    static String parseString(String s, int[] p) {
        p[0]++; // skip opening "
        StringBuilder sb = new StringBuilder();
        while (p[0] < s.length() && s.charAt(p[0]) != '"') {
            char c = s.charAt(p[0]);
            if (c == '\\' && p[0] + 1 < s.length()) {
                p[0]++;
                char esc = s.charAt(p[0]);
                switch (esc) {
                    case 'n':  sb.append('\n'); break;
                    case 't':  sb.append('\t'); break;
                    case 'r':  sb.append('\r'); break;
                    default:   sb.append(esc);
                }
            } else {
                sb.append(c);
            }
            p[0]++;
        }
        p[0]++; // skip closing "
        return sb.toString();
    }

    static Object parseNumber(String s, int[] p) {
        int start = p[0];
        while (p[0] < s.length() && "-0123456789.eE+".indexOf(s.charAt(p[0])) >= 0) p[0]++;
        String num = s.substring(start, p[0]);
        if (num.contains(".") || num.contains("e") || num.contains("E")) return Double.parseDouble(num);
        return Long.parseLong(num);
    }

    static void skipWs(String s, int[] p) {
        while (p[0] < s.length() && Character.isWhitespace(s.charAt(p[0]))) p[0]++;
    }

    /* ── helpers ───────────────────────────────────────────────────────── */

    static long toLong(Object v, long def) {
        if (v instanceof Long)   return (Long) v;
        if (v instanceof Double) return ((Double) v).longValue();
        if (v instanceof String) return Long.parseLong((String) v);
        return def;
    }

    static String str(Map<String,Object> m, String key, String def) {
        Object v = m.get(key);
        return v instanceof String ? (String) v : def;
    }
}
