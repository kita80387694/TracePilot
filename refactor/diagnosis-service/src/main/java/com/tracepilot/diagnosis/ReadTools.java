package com.tracepilot.diagnosis;

import static com.tracepilot.diagnosis.Domain.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Closed tool surface: no supplied URL, file path, SQL, expression, command or control token. */
@Component
public class ReadTools {
  private final URI base;
  private final String token;
  private final Path root;
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(3))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private static final Set<String> MODULES =
      Set.of("api", "booking", "catalog", "events", "identity", "observability", "reporting");

  public ReadTools(
      @Value("${diag.business-url}") String base,
      @Value("${diag.observer-token}") String token,
      @Value("${diag.evidence-root}") String root) {
    this.base = URI.create(base);
    this.token = token;
    this.root = Path.of(root).toAbsolutePath().normalize();
    if (!Set.of("http", "https").contains(this.base.getScheme())
        || this.base.getRawUserInfo() != null
        || this.base.getQuery() != null
        || !Set.of("", "/").contains(this.base.getPath()))
      throw new IllegalArgumentException("INVALID_REGISTERED_ENDPOINT");
  }

  public Result query(Request request, Query query, Set<String> observedVersions) {
    try {
      validate(query);
      query = boundedPage(query);
      if (query.tool().equals("runbook")) {
        try (var stream =
            ReadTools.class.getResourceAsStream("/runbooks/business-troubleshooting.md")) {
          if (stream == null)
            return result("UNAVAILABLE", Map.of(), Map.of("source", "bundled-runbook"));
          byte[] bytes = stream.readNBytes(16385);
          if (bytes.length > 16384) throw new SecurityException();
          return result(
              "AVAILABLE",
              Map.of("text", new String(bytes, StandardCharsets.UTF_8)),
              Map.of(
                  "source",
                  "bundled-runbook",
                  "document",
                  "business-troubleshooting.md",
                  "version",
                  HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))));
        }
      }
      if (query.tool().equals("code")) return code(query.args(), observedVersions);
      String route =
          switch (query.tool()) {
            case "overview" -> "/ops/snapshot";
            case "metrics" -> "/ops/samples";
            case "sqlPlan" -> "/ops/query-plan?statementFingerprint=" + query.args().get("statementFingerprint");
            case "logs", "trace" -> "/ops/logs";
            default -> throw new SecurityException();
          };
      if (!Set.of("overview","sqlPlan").contains(query.tool())) {
        route +=
            "?start="
                + enc(request.start().toString())
                + "&end="
                + enc(request.end().toString())
                + "&limit="
                + Integer.toString(requestedLimit(query))
                + "&cursor="
                + query.args().getOrDefault("cursor", "0");
        if (query.args().containsKey("traceId")) route += "&traceId=" + query.args().get("traceId");
        if (query.args().containsKey("level")) route += "&level=" + query.args().get("level");
        if (query.args().containsKey("channel")) route += "&channel=" + query.args().get("channel");
      }
      Map<String, Object> locator =
          Map.of(
              "endpoint",
              route,
              "service",
              request.service(),
              "environment",
              request.environment());
      var response =
          http.send(
              HttpRequest.newBuilder(base.resolve(route))
                  .timeout(Duration.ofSeconds(9))
                  .header("Authorization", "Bearer " + token)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofInputStream());
      try (var stream = response.body()) {
        if (response.statusCode() == 401 || response.statusCode() == 403)
          return result("FORBIDDEN", Map.of(), locator);
        if (response.statusCode() != 200)
          return result("UNAVAILABLE", Map.of("httpStatus", response.statusCode()), locator);
        byte[] bytes = stream.readNBytes(1024 * 1024 + 1);
        if (bytes.length > 1024 * 1024)
          return result("UNAVAILABLE", Map.of("reason", "RESPONSE_TOO_LARGE"), locator);
        JsonNode data = parse(new String(bytes, StandardCharsets.UTF_8));
        if (!matchesScope(data, request))
          return result("FORBIDDEN", Map.of("reason", "SOURCE_SCOPE_MISMATCH"), locator);
        return new Result(data.path("status").asText("AVAILABLE"), data, locator);
      }
    } catch (SecurityException | IllegalArgumentException denied) {
      return result(
          "FORBIDDEN",
          Map.of("reason", "TOOL_POLICY"),
          Map.of("tool", String.valueOf(query.tool())));
    } catch (java.net.http.HttpTimeoutException e) {
      return result("TIMEOUT", Map.of(), Map.of("tool", query.tool()));
    } catch (Exception unavailable) {
      if (!Set.of("code","sqlPlan").contains(query.tool()))
        try {
          return offline(request, query);
        } catch (SecurityException denied) {
          return result("FORBIDDEN", Map.of(), Map.of("tool", query.tool()));
        } catch (Exception ignored) {
        }
      return result(
          "UNAVAILABLE",
          Map.of("errorType", unavailable.getClass().getSimpleName()),
          Map.of("tool", query.tool()));
    }
  }

  public Result probe(Request request, Query query) {
    var future = new java.util.concurrent.FutureTask<Result>(() -> query(request, query, Set.of()));
    Thread.ofVirtual().start(future);
    try {
      return future.get(10, java.util.concurrent.TimeUnit.SECONDS);
    } catch (java.util.concurrent.TimeoutException e) {
      return result("TIMEOUT", Map.of(), Map.of("tool", query.tool()));
    } catch (Exception e) {
      return result("UNAVAILABLE", Map.of(), Map.of("tool", query.tool()));
    } finally {
      future.cancel(true);
    }
  }

  private Result offline(Request request, Query query) throws Exception {
    safe(root);
    if (!Files.isDirectory(root)) throw new java.io.IOException();
    boolean logs = Set.of("logs", "trace").contains(query.tool());
    Path archiveRoot=query.args().getOrDefault("channel","all").equals("events")?safe(root.resolve("events")):root;
    var page = ArchivePage.read(archiveRoot, logs, request.start(), request.end(),
        query.args().getOrDefault("cursor", "0"),
        requestedLimit(query),
        query.args().get("traceId"), query.args().get("level"), JSON);
    if(archiveRoot!=root){
      page.put("channel","events");Path marker=archiveRoot.resolve("coverage-start.txt");
      if(!Files.exists(marker)){page.put("status","PARTIAL");page.put("sourceGap","UNKNOWN_EVENT_CHANNEL_START");}
      else {Instant started=Instant.parse(Files.readString(marker));page.put("sourceCoverageStart",started.toString());if(request.start().isBefore(started)){page.put("status","PARTIAL");page.put("sourceGap","EVENT_CHANNEL_NOT_COLLECTED_BEFORE_SOURCE_START");}}
    }
    if (!matchesScope(tree(page), request)) throw new SecurityException();
    var locator = Map.<String,Object>of("source", "offline-observability", "onlineStatus", "UNAVAILABLE",
        "kind", logs ? "logs" : "metrics", "start", request.start().toString(), "end", request.end().toString());
    // A historical page is not a current service overview.
    if(query.tool().equals("overview")) return result("STALE", page, locator);
    return result((String)page.get("status"), page, locator);
  }

  // Source acquisition and model presentation are independently bounded. The HTTP endpoint already
  // limits pages to 200 rows / 1 MiB; EvidenceDelivery retains its 24k-character reading pages.
  static int pageLimit(String tool){return switch(tool){case "code" -> 30;default -> 200;};}
  static int requestedLimit(Query q){return Integer.parseInt(q.args().getOrDefault("limit",Integer.toString(pageLimit(q.tool()))));}
  static Query boundedPage(Query q){
    if(!Set.of("logs","trace","metrics").contains(q.tool()))return q;
    var args=new LinkedHashMap<>(q.args());args.put("limit",Integer.toString(Math.min(requestedLimit(q),pageLimit(q.tool()))));return new Query(q.tool(),args);
  }
  static Set<String> allowedArgs(String tool) {
    return
        switch (String.valueOf(tool)) {
          case "overview", "runbook" -> Set.of();
          case "logs" -> Set.of("limit", "cursor", "level","channel");
          case "metrics" -> Set.of("limit", "cursor");
          case "trace" -> Set.of("limit", "cursor", "traceId","channel");
          case "code" -> Set.of("version", "query", "limit");
          case "sqlPlan" -> Set.of("statementFingerprint");
          default -> throw new SecurityException();
        };
  }

  static final String CODE_VERSION_PATTERN="sha256-[0-9a-f]{64}";
  static final String CODE_QUERY_PATTERN="[A-Za-z_][A-Za-z0-9_.]{1,79}";
  static final String TRACE_ID_PATTERN="[0-9a-f]{32}";
  static void validate(Query query) {
    Set<String> allowed = allowedArgs(query.tool());
    if (!allowed.containsAll(query.args().keySet())) throw new SecurityException();
    if(query.tool().equals("sqlPlan")&&!query.args().getOrDefault("statementFingerprint","").matches("[a-f0-9]{64}"))throw new SecurityException();
    if(query.args().containsKey("channel")&&!Set.of("all","events").contains(query.args().get("channel")))throw new SecurityException();
    for (var x : query.args().entrySet())
      if (x.getValue() == null || x.getValue().length() > 200) throw new SecurityException();
    int limit = requestedLimit(query);
    if (limit < 1 || limit > 200 || !ArchivePage.validCursor(query.args().getOrDefault("cursor", "0"))) throw new SecurityException();
    if (query.args().containsKey("level")
        && !Set.of("INFO", "WARN", "ERROR").contains(query.args().get("level")))
      throw new SecurityException();
    if (query.tool().equals("trace")
        && !query.args().getOrDefault("traceId", "").matches(TRACE_ID_PATTERN))
      throw new SecurityException();
    if (query.tool().equals("code")
        && (!query.args().getOrDefault("version", "").matches(CODE_VERSION_PATTERN)
            || !query.args().getOrDefault("query", "").matches(CODE_QUERY_PATTERN)))
      throw new SecurityException();
  }

  private boolean matchesScope(JsonNode node, Request request) {
    if (node.has("service")
        && (!node.path("service").asText().equals(request.service())
            || !node.path("environment").asText().equals(request.environment()))) return false;
    if (node.path("data").isArray())
      for (JsonNode row : node.path("data")) if (!matchesScope(row, request)) return false;
    return true;
  }

  private Result code(Map<String, String> args, Set<String> versions) throws Exception {
    String version = args.get("version");
    if (!versions.contains(version)) throw new SecurityException();
    Path directory = safe(root.resolve("code").resolve(version));
    Path manifest = safe(directory.resolve("manifest.json"));
    if (!Files.exists(manifest))
      return result(
          "NO_DATA", Map.of("reason", "VERSION_NOT_EXPORTED"), Map.of("version", version));
    if (Files.size(manifest) > 128 * 1024) throw new SecurityException();
    JsonNode data = parse(Files.readString(manifest).replaceFirst("^\uFEFF", ""));
    if (!data.path("deploymentVersion").asText().equals(version)) throw new SecurityException();
    List<Object> hits = new ArrayList<>();
    int limit = Math.min(Integer.parseInt(args.getOrDefault("limit", "30")), 100);
    long scanned = 0;
    for (JsonNode entry : data.path("files")) {
      String name = entry.path("path").asText();
      // Legacy M2 exports contain a demo credential bootstrap. It is never model evidence.
      if (name.equals("identity/DemoSeed.java")) continue;
      if (!name.matches("[a-z]+/[A-Za-z][A-Za-z0-9]*\\.java")
          || !MODULES.contains(name.split("/")[0])
          || name.endsWith("DeliveryGate.java")
          || name.endsWith("ReportQueryPolicy.java")) throw new SecurityException();
      Path file = safe(directory.resolve(name));
      long size = Files.size(file);
      scanned += size;
      if (size > 512 * 1024 || scanned > 8 * 1024 * 1024) throw new SecurityException();
      byte[] bytes = Files.readAllBytes(file);
      String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      if (!hash.equals(entry.path("sha256").asText()))
        return result(
            "UNAVAILABLE",
            Map.of("reason", "CODE_HASH_MISMATCH"),
            Map.of("version", version, "path", name));
      String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\\R");
      for (int i = 0; i < lines.length; i++)
        if (lines[i].contains(args.get("query")) && hits.size() < limit)
          hits.add(Map.of("path", name, "line", i + 1, "text", lines[i], "sha256", hash));
    }
    return result(
        hits.isEmpty() ? "NO_DATA" : "AVAILABLE",
        Map.of("hits", hits, "limit", limit, "possiblyTruncated", hits.size() == limit),
        Map.of(
            "version",
            version,
            "manifest",
            "code/" + version + "/manifest.json",
            "query",
            args.get("query")));
  }

  Path safe(Path path) throws Exception {
    Path p = path.toAbsolutePath().normalize();
    if (!p.startsWith(root)) throw new SecurityException();
    for (Path current = p; current != null; current = current.getParent())
      if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current))
        throw new SecurityException();
    if (Files.exists(p) && !p.toRealPath().startsWith(root.toRealPath()))
      throw new SecurityException();
    return p;
  }

  private static String enc(String v) {
    return URLEncoder.encode(v, StandardCharsets.UTF_8);
  }
}
