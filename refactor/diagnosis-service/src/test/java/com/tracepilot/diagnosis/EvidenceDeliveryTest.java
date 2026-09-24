package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;import static org.assertj.core.api.Assertions.*;
import java.time.*;import java.util.*;import org.junit.jupiter.api.Test;
class EvidenceDeliveryTest {
 static final String START="2026-09-12T18:00:00Z",END="2026-09-12T18:01:00Z";
 Request request(){return new Request("tracepilot-business","demo",Instant.parse(START),Instant.parse(END),"notification delayed");}
 Evidence page(String id,String cursor,String next,boolean more){
  return new Evidence(id,"logs",more?"PARTIAL":"AVAILABLE",START,END,Map.of("query",Map.of("tool","logs","args",Map.of("channel","events","limit","2","cursor",cursor))),tree(Map.of("data",List.of(Map.of("time",START,"message","event_claimed","eventId",42,"attemptId","public-attempt")),"channel","events","hasMore",more,"nextCursor",next,"remainingBytes",501973787,"gap","NONE")));
 }
 @Test void metadataPackingPreservesAllNonDisplayFactsAndMetricDefinitions(){
  var e=page("E1","0","0",false);var full=EvidenceUse.catalog(List.of(e),request());var input=new LinkedHashMap<String,Object>();input.put("causeFactRegistry",full);ContextPacking.pack(input);
  var expanded=tree(ContextPacking.expand(tree(input),"causeFactRegistry"));var expected=tree(full).deepCopy();for(var f:expected)((com.fasterxml.jackson.databind.node.ObjectNode)f).remove("display");assertThat(expanded).isEqualTo(expected);
  assertThat(encode(input)).contains("contextRef","factContexts","measurementMeaning");
 }
 @Test void projectionIncludesFormerlyOmittedRowsAndCorrelationIdentifiers(){
  var rows=new ArrayList<Object>();for(int i=0;i<8;i++)rows.add(Map.of("time",START,"eventId",42,"attemptId","A"+i,"notificationId",100+i,"traceId","abc","message","notification_write_committed"));
  var e=new Evidence("E","logs","AVAILABLE",START,END,Map.of(),tree(Map.of("data",rows)));
  assertThat(Reports.factCatalog(e).stream().map(Domain::tree).map(n->n.path("pointer").asText()).toList()).contains("/data/7/notificationId","/data/7/traceId");
  assertThat(EvidenceCompleteness.projection(e).get("projectionComplete")).isEqualTo(true);
 }
 @Test void projectionLimitsAndSourceCoverageRemainExplicit(){
  var rows=new ArrayList<Object>();for(int i=0;i<=Reports.MAX_ARRAY_ITEMS;i++)rows.add(Map.of("message","fixture"));var e=new Evidence("E","logs","PARTIAL",START,END,Map.of(),tree(Map.of("data",rows,"sourceGap","NOT_COLLECTED")));
  assertThat(EvidenceCompleteness.projection(e).get("projectionComplete")).isEqualTo(false);assertThat(EvidenceCompleteness.gaps(List.of(e)).toString()).contains("NOT_COLLECTED","投影未完成");
 }
 @Test void paginationCompletionRequiresExactChainAndFilters(){
  var a=page("E1","0","opaque",true);var b=page("E2","opaque","0",false);
  assertThat(EvidenceCompleteness.scanComplete(a,List.of(a))).isFalse();assertThat(EvidenceCompleteness.scanComplete(a,List.of(a,b))).isTrue();
  var foreign=new Evidence(b.id(),b.source(),b.status(),b.start(),b.end(),Map.of("query",Map.of("tool","logs","args",Map.of("channel","all","limit","2","cursor","opaque"))),b.data());
  assertThat(EvidenceCompleteness.scanComplete(a,List.of(a,foreign))).isFalse();assertThat(EvidenceCompleteness.gaps(List.of(a,b))).isEmpty();
 }
 @Test void programRendersApproximateBytesAndDiscriminatingChecks(){
  var e=page("E","0","opaque",true);String gaps=EvidenceCompleteness.gaps(List.of(e)).toString();
  assertThat(gaps).contains("501973787 bytes","约 501.97 MB","约 478.72 MiB","HALF_UP");
  assertThat(EvidenceCompleteness.checks(List.of(e)).toString()).contains("eventId/attemptId","已写入但outbox未确认","原用户权限");
 }
 @Test void restrictedChannelDoesNotOpenPathsOrControls(){
  ReadTools.validate(new Query("logs",Map.of("channel","events","limit","2")));
  assertThatThrownBy(()->ReadTools.validate(new Query("logs",Map.of("channel","../private")))).isInstanceOf(SecurityException.class);
  assertThatThrownBy(()->ReadTools.validate(new Query("metrics",Map.of("channel","events")))).isInstanceOf(SecurityException.class);
 }
}
