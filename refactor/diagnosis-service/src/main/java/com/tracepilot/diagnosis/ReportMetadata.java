package com.tracepilot.diagnosis;

import static com.tracepilot.diagnosis.Domain.*;
import java.util.*;

/** Report display metadata only. The evidence store remains the complete audit source. */
final class ReportMetadata {
  static void attach(Map<String,Object> report, List<Evidence> evidence, Request request) {
    var registry=FactReferences.catalog(evidence,request);
    var metrics=MetricFacts.generate(evidence,request);
    // Historic/partial report formats may have no explicit display selection. Preserve that path.
    if(!report.containsKey("reportFactScope")) {
      report.put("factRegistry",registry); report.put("metricFacts",metrics); return;
    }
    var selected=new HashSet<String>();
    for(var fact:tree(report.get("facts"))) selected.add(fact.path("factId").asText());
    var shown=new ArrayList<Object>(); var shownMetrics=new ArrayList<Object>();
    for(var fact:registry) if(selected.contains(tree(fact).path("factId").asText())) shown.add(fact);
    for(var metric:metrics) {
      var row=tree(metric);
      if(selected.contains(FactReferences.id(row.path("evidenceId").asText(),row.path("pointer").asText()))) shownMetrics.add(metric);
    }
    report.put("factRegistry",shown); report.put("metricFacts",shownMetrics);
    report.put("metadataScope",Map.of("version","selected-report-metadata-v1",
      "registeredFacts",registry.size(),"displayedFacts",shown.size(),
      "registeredMetrics",metrics.size(),"displayedMetrics",shownMetrics.size(),
      "meaning","Only report-selected display metadata is duplicated here; full raw evidence remains in the task evidence store. Omitted metadata is not missing source evidence or proof of exhaustive review."));
  }
}
