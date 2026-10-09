// Copyright 2026 Tornbergs Consulting AB
// SPDX-License-Identifier: Apache-2.0
package consulting.tornbergs.directory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/** DEV response contract probe. No LDAP connection or credential authentication. */
public final class ResponseProbe {
    private static final Logger LOG = Logger.getLogger(ResponseProbe.class.getName());
    private static final int MAX_BODY = 65536;
    private record Result(int status, String outcome, String code, String comment) {}
    private static final Map<String, Result> RESULTS = Map.ofEntries(
        Map.entry("success", new Result(200, "CHANGED", "MEMBERSHIP_CHANGED", "Simulated membership change completed.")),
        Map.entry("already-member", new Result(200, "UNCHANGED", "ALREADY_MEMBER", "No change required: already a direct member.")),
        Map.entry("not-member", new Result(200, "UNCHANGED", "NOT_MEMBER", "No change required: not a direct member.")),
        Map.entry("bad-request", new Result(400, "FAILED", "INVALID_REQUEST", "Simulated invalid request.")),
        Map.entry("unauthorized", new Result(401, "FAILED", "INVALID_CREDENTIALS", "Simulated authentication failure.")),
        Map.entry("forbidden", new Result(403, "FAILED", "ACCESS_DENIED", "Simulated AD access denial.")),
        Map.entry("not-found", new Result(404, "FAILED", "OBJECT_NOT_FOUND", "Simulated missing directory object.")),
        Map.entry("unavailable", new Result(503, "FAILED", "DIRECTORY_UNAVAILABLE", "Simulated temporary directory failure.")),
        Map.entry("internal-error", new Result(500, "FAILED", "INTERNAL_ERROR", "Simulated unexpected failure.")));

    public static void main(String[] args) throws IOException {
        String host = System.getenv().getOrDefault("DPS_HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault("DPS_PORT", "8080"));
        Level level = Level.parse(System.getenv().getOrDefault("DPS_LOG_LEVEL", "INFO"));
        Logger root = Logger.getLogger("");
        root.setLevel(Level.INFO);
        LOG.setLevel(level);
        for (var handler : root.getHandlers()) handler.setLevel(level);
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 32);
        var executor = Executors.newFixedThreadPool(4);
        server.setExecutor(executor);
        server.createContext("/", ResponseProbe::handle);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { server.stop(1); executor.shutdownNow(); }));
        server.start();
        LOG.info("DEV response probe listening on " + host + ":" + port + "; no LDAP or credential authentication");
    }

    private static void handle(HttpExchange ex) throws IOException {
        String id = ex.getRequestHeaders().getFirst("X-Correlation-ID");
        if (id == null || !id.matches("[A-Za-z0-9._:-]{1,128}")) id = UUID.randomUUID().toString();
        long started = System.nanoTime();
        try {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/health") && ex.getRequestMethod().equals("GET")) {
                send(ex, id, new Result(200, "READY", "PROBE_READY", "DEV probe ready; LDAP not implemented."));
                return;
            }
            if (!path.startsWith("/test/responses/")) {
                send(ex, id, new Result(404, "FAILED", "UNKNOWN_ENDPOINT", "Unknown endpoint.")); return;
            }
            if (!ex.getRequestMethod().equals("POST")) {
                ex.getResponseHeaders().set("Allow", "POST");
                send(ex, id, new Result(405, "FAILED", "METHOD_NOT_ALLOWED", "Use POST.")); return;
            }
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (!basicSyntaxValid(auth)) {
                send(ex, id, new Result(401, "FAILED", "BASIC_REQUIRED", "Supply nonempty synthetic Basic credentials.")); return;
            }
            byte[] body = ex.getRequestBody().readNBytes(MAX_BODY + 1);
            if (body.length > MAX_BODY) {
                send(ex, id, new Result(413, "FAILED", "BODY_TOO_LARGE", "Body exceeds 64 KiB.")); return;
            }
            String scenario = path.substring("/test/responses/".length());
            Result result = RESULTS.get(scenario);
            if (result == null) result = new Result(404, "FAILED", "UNKNOWN_SCENARIO", "Unknown response scenario.");
            // Never log headers, credentials or request bodies, at any level.
            LOG.fine("request=" + id + " bodyBytes=" + body.length);
            send(ex, id, result);
        } finally {
            LOG.info("request=" + id + " status=" + ex.getResponseCode() + " durationMs=" + (System.nanoTime()-started)/1_000_000);
            ex.close();
        }
    }

    private static boolean basicSyntaxValid(String auth) {
        if (auth == null || !auth.regionMatches(true, 0, "Basic ", 0, 6)) return false;
        try {
            String decoded = new String(Base64.getDecoder().decode(auth.substring(6)), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            return colon > 0 && colon < decoded.length()-1;
        } catch (IllegalArgumentException e) { return false; }
    }

    private static void send(HttpExchange ex, String id, Result r) throws IOException {
        if (r.status == 401) ex.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"response-probe\", charset=\"UTF-8\"");
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Correlation-ID", id);
        String json = "{\"requestId\":\"" + id + "\",\"fulfillmentId\":\"probe-" + id
            + "\",\"outcome\":\"" + r.outcome + "\",\"code\":\"" + r.code
            + "\",\"comment\":\"" + r.comment + "\",\"simulated\":true}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(r.status, bytes.length);
        ex.getResponseBody().write(bytes);
    }
}
