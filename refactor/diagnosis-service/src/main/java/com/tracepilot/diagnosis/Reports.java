package com.tracepilot.diagnosis;

import static com.tracepilot.diagnosis.Domain.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

public final class Reports {
  // Server registry bounds match the existing 200-row / 1 MiB source boundary.
  // Model page and final request budgets remain unchanged.
  static final int MAX_ARRAY_ITEMS = 200, MAX_SCALARS = 20000, MAX_DEPTH = 64;
  record Projection(List<Object> facts, boolean complete) {}
  public static List<Object> factCatalog(Evidence evidence) { return project(evidence).facts(); }
  static Projection project(Evidence evidence) {
    List<Object> output = new ArrayList<>();
    boolean[] complete = {true};
    if (Set.of("AVAILABLE", "PARTIAL", "STALE", "NO_DATA").contains(evidence.status()))
      flatten(evidence.id(), "", evidence.data(), output, complete, 0);
    return new Projection(output, complete[0]);
  }
  private static final Set<String> METADATA = Set.of("evidenceId", "time", "collectedAt",
      "service", "environment", "deploymentVersion", "instanceId", "sourceRequestId");
  private static void flatten(String id, String pointer, JsonNode node, List<Object> output,
      boolean[] complete, int depth) {
    if (depth > MAX_DEPTH) { complete[0] = false; return; }
    if (node.isObject()) {
      var fields = node.fields();
      while (fields.hasNext()) {
        var e = fields.next();
        if (!METADATA.contains(e.getKey()))
          flatten(id, pointer + "/" + e.getKey().replace("~", "~0").replace("/", "~1"),
              e.getValue(), output, complete, depth + 1);
      }
    } else if (node.isArray() && !node.isEmpty()) {
      if (node.size() > MAX_ARRAY_ITEMS) complete[0] = false;
      for (int i = 0; i < Math.min(node.size(), MAX_ARRAY_ITEMS); i++)
        flatten(id, pointer + "/" + i, node.get(i), output, complete, depth + 1);
    } else if (output.size() < MAX_SCALARS) {
      output.add(Map.of("evidenceId", id, "pointer", pointer, "value", node));
    } else complete[0] = false;
  }

  public static JsonNode fields(JsonNode array, Set<String> names) {
    if (!array.isArray()) return array;
    var output = JSON.createArrayNode();
    for (JsonNode item : array) {
      var row = JSON.createObjectNode();
      for (String name : names) if (item.has(name)) row.set(name, item.get(name));
      output.add(row);
    }
    return output;
  }

  public static JsonNode clean(JsonNode input) {
    if (!input.isObject()) return input;
    var output = JSON.createObjectNode();
    for (String name : List.of("summary", "gaps", "actions"))
      if (input.has(name)) output.set(name, input.get(name));
    output.set(
        "facts", fields(input.path("facts"), Set.of("text", "evidenceId", "pointer", "value")));
    output.set(
        "candidates",
        fields(
            input.path("candidates"), CandidateContract.FIELDS));
    output.set("conflicts", fields(input.path("conflicts"), Set.of("text", "evidenceIds")));
    return output;
  }

  public static List<String> validate(JsonNode report, List<Evidence> evidence) {
    List<String> errors = new ArrayList<>();
    Map<String, Evidence> ids = new HashMap<>();
    evidence.forEach(e -> ids.put(e.id(), e));
    if (!report.isObject() || !report.path("summary").isTextual() || report.path("summary").asText().isBlank())
      errors.add("MISSING_SUMMARY");
    for (String field : List.of("facts", "candidates", "conflicts", "gaps", "actions"))
      if (!report.path(field).isArray()) errors.add("MISSING_" + field);
    if (report.path("candidates").size() > 3) errors.add("TOO_MANY_CANDIDATES");
    if (!hasWindowData(evidence)) {
      boolean attempted = evidence.stream().anyMatch(e -> Set.of("metrics", "logs", "trace").contains(e.source()));
      if (!attempted || !report.path("candidates").isEmpty() || report.path("gaps").isEmpty())
        errors.add("MISSING_WINDOW_EVIDENCE");
    }
    for(String field:List.of("gaps","actions"))
      for(JsonNode item:report.path(field)) if(!item.isTextual()) errors.add("INVALID_"+field+"_ITEM");
    for(JsonNode item:report.path("conflicts"))
      if(!item.path("text").isTextual() || !item.path("evidenceIds").isArray()) errors.add("INVALID_CONFLICT");
    checkRefs(report, ids, errors);
    for (JsonNode fact : report.path("facts")) {
      if(!fact.path("text").isTextual() || fact.path("text").asText().isBlank()
          || !fact.path("evidenceId").isTextual() || !fact.path("pointer").isTextual() || !fact.has("value"))
        errors.add("INVALID_FACT_FIELDS");
      String id = fact.path("evidenceId").asText(), pointer = fact.path("pointer").asText();
      if (!ids.containsKey(id)
          || !Set.of("AVAILABLE", "PARTIAL", "STALE", "NO_DATA").contains(ids.get(id).status())) {
        errors.add("INVALID_FACT_SOURCE");
        continue;
      }
      try {
        JsonNode observed = ids.get(id).data().at(pointer);
        if (pointer.isEmpty() || observed.isMissingNode() || !observed.equals(fact.path("value")))
          errors.add("FACT_VALUE_MISMATCH");
      } catch (Exception bad) {
        errors.add("INVALID_POINTER");
      }
    }
    for (JsonNode candidate : report.path("candidates")) {
      if (!Set.of("SUPPORTED", "LIKELY", "UNVERIFIED")
              .contains(candidate.path("confidence").asText())
          || !candidate.path("inference").isBoolean() || !candidate.path("inference").asBoolean(false)
          || !candidate.path("cause").isTextual() || candidate.path("cause").asText().isBlank()
          || !candidate.path("evidenceIds").isArray()) errors.add("INVALID_CANDIDATE");
      if (candidate.path("evidenceIds").isEmpty()) errors.add("UNSUPPORTED_CANDIDATE");
    }
    return errors.stream().distinct().toList();
  }

  public static boolean hasWindowData(List<Evidence> evidence) {
    return evidence.stream().anyMatch(e -> (e.source().equals("overview") && MetricFacts.verifiedVersion(e.data().path("deploymentVersion").asText()) && MetricFacts.KINDS.keySet().stream().anyMatch(k->e.data().at(k).isNumber())) || Set.of("metrics", "logs", "trace").contains(e.source())
        && Set.of("AVAILABLE", "PARTIAL", "STALE").contains(e.status()) && e.data().path("data").isArray() && !e.data().path("data").isEmpty());
  }

  private static void checkRefs(JsonNode node, Map<String, Evidence> ids, List<String> errors) {
    if (node.isObject())
      node.fields()
          .forEachRemaining(
              entry -> {
                if (entry.getKey().equals("evidenceId")) {
                  if (!ids.containsKey(entry.getValue().asText())) errors.add("UNKNOWN_REFERENCE");
                } else if (entry.getKey().equals("evidenceIds")) {
                  if (!entry.getValue().isArray()) errors.add("INVALID_REFERENCES");
                  else
                    for (JsonNode ref : entry.getValue())
                      if (!ids.containsKey(ref.asText())) errors.add("UNKNOWN_REFERENCE");
                } else checkRefs(entry.getValue(), ids, errors);
              });
    else if (node.isArray()) node.forEach(n -> checkRefs(n, ids, errors));
  }

  public static Map<String, Object> partial(String reason, List<Evidence> evidence) {
    String state = "CANCELLED_BY_USER".equals(reason) ? "诊断已取消" : "诊断未完成";
    String saved = evidence.isEmpty()
        ? "尚未保存查询结果。"
        : "已保存查询结果及数据源状态，详见证据；不代表已确认故障原因。";
    return new LinkedHashMap<>(
        Map.of(
            "summary",
            state + "；" + saved,
            "facts",
            List.of(),
            "candidates",
            List.of(),
            "conflicts",
            List.of(),
            "gaps",
            List.of(reason),
            "actions",
            List.of(evidence.isEmpty() ? "如需继续诊断，请重新发起任务并检查数据源与模型配置。" : "检查数据与模型配置，结合已保存查询记录人工复核。"),
            "evidenceIds",
            evidence.stream().map(Evidence::id).toList()));
  }

  static List<String> validateGapAction(JsonNode action){
    var errors=new ArrayList<String>();
    if(!action.isObject() || !action.path("type").asText().equals("insufficient"))errors.add("INVALID_INSUFFICIENT_ACTION");
    action.fieldNames().forEachRemaining(k->{if(!Set.of("type","summary","gaps","actions").contains(k))errors.add("UNEXPECTED_GAP_FIELD:"+k);});
    if(!action.path("summary").isTextual()||action.path("summary").asText().isBlank())errors.add("MISSING_SUMMARY");
    for(String k:List.of("gaps","actions")){
      if(!action.path(k).isArray())errors.add("INVALID_"+k);
      else for(JsonNode x:action.path(k))if(!x.isTextual()||x.asText().isBlank())errors.add("INVALID_"+k+"_ITEM");
    }
    if(action.path("gaps").isEmpty())errors.add("MISSING_DATA_GAPS");
    return errors;
  }
  static Map<String,Object> insufficient(JsonNode action,List<Evidence> es,Request r){
    if(!validateGapAction(action).isEmpty() || EvidenceRegistry.hasEvent(es,r))throw new IllegalArgumentException("INVALID_INSUFFICIENT_REPORT");
    var out=new LinkedHashMap<String,Object>();
    for(String k:List.of("summary","gaps","actions"))out.put(k,action.get(k));
    for(String k:List.of("facts","candidates","conflicts"))out.put(k,List.of());
    out.put("checkedSources",EvidenceRegistry.entries(es,r));
    out.put("resultType",es.stream().anyMatch(e->!EvidenceRegistry.readable(e))?"INSUFFICIENT_EVIDENCE_SOURCE_FAILURE":"INSUFFICIENT_EVIDENCE");
    return out;
  }
  static List<String> unexpectedReportFields(JsonNode report){
    var errors=new ArrayList<String>();
    report.fieldNames().forEachRemaining(k->{if(!Set.of("summary","gaps","actions","facts","candidates","conflicts").contains(k))errors.add("UNEXPECTED_REPORT_FIELD:"+k);});
    Map<String,Set<String>> fields=Map.of("facts",Set.of("text","evidenceId","pointer","value"),
      "candidates",CandidateContract.FIELDS,"conflicts",Set.of("text","evidenceIds"));
    fields.forEach((name,allowed)->report.path(name).forEach(row->row.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))errors.add("UNEXPECTED_REFERENCE_FIELD:"+k);})));
    return errors;
  }

  private Reports() {}
}
