package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import com.fasterxml.jackson.databind.JsonNode;import java.time.*;import java.util.*;
/** Adjacent observed nodes within an event, computed with explicit clock precision; no causal claims. */
final class TimeComparisons {
 static List<Object> generate(EventTimeline.Catalog catalog){var out=new ArrayList<Object>();var groups=new TreeMap<String,List<JsonNode>>();for(var n:catalog.nodes())groups.computeIfAbsent(n.path("eventId").asText(),k->new ArrayList<>()).add(n);
  for(var group:groups.values()){group.sort(Comparator.comparing(n->EventTimeline.clock(n.path("eventTime").asText())));for(int i=1;i<group.size();i++)out.add(compare(group.get(i-1),group.get(i)));}return out;}
 static Map<String,Object> compare(JsonNode a,JsonNode b){var out=new LinkedHashMap<String,Object>();out.put("leftNodeId",a.path("nodeId"));out.put("rightNodeId",b.path("nodeId"));out.put("left",EventTimeline.display(a));out.put("right",EventTimeline.display(b));out.put("precision","LOG_EMISSION_MILLISECOND; collection timestamp not used");out.put("meaning","Log observation comparison only; not operation duration, retry delay, exact database commit time or causal proof");
  var left=EventTimeline.clock(a.path("eventTime").asText());var right=EventTimeline.clock(b.path("eventTime").asText());boolean known=!left.equals(Instant.MAX)&&!right.equals(Instant.MAX)&&a.path("eventId").equals(b.path("eventId"))&&!a.path("instanceId").asText().equals("UNKNOWN")&&a.path("instanceId").equals(b.path("instanceId"));
  if(!known){out.put("order","UNKNOWN");out.put("deltaMillis",null);}else{long delta=Duration.between(left,right).toMillis();out.put("order",delta==0?"UNRESOLVED_WITHIN_PRECISION":delta>0?"LEFT_OBSERVED_BEFORE_RIGHT":"LEFT_OBSERVED_AFTER_RIGHT");out.put("deltaMillis",delta);}return out;
 }
}
