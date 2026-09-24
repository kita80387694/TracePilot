package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import com.fasterxml.jackson.databind.JsonNode;import java.util.*;
/** Structural necessary condition only. Passing this check is NOT semantic/causal proof. */
final class CausalLink {
 static final String VERSION="causal-overlap-v2-premise-direction";
 static boolean repeatsObservations(JsonNode h,Map<String,JsonNode> facts,Map<String,Evidence> es){
  if(!h.path("relation").asText().equals("MAY_EXPLAIN")||!h.path("outcomeFactIds").isArray()||h.path("outcomeFactIds").isEmpty())return false;
  var premises=new HashSet<String>();for(var ref:h.path("premiseFactIds")){String key=identity(ref.asText(),facts,es);if(key!=null)premises.add(key);}
  var outcomes=new HashSet<String>();for(var ref:h.path("outcomeFactIds")){String key=identity(ref.asText(),facts,es);if(key!=null)outcomes.add(key);}
  // P plus the observed symptom S -> S still has the independent premise P.
  // Reject only when every premise is itself one of the selected outcomes.
  // A distinct premise is a structural condition, never proof of relevance/causality.
  return !premises.isEmpty()&&outcomes.containsAll(premises);
 }
 static String identity(String id,Map<String,JsonNode> facts,Map<String,Evidence> es){
  JsonNode f=facts.get(id);if(f==null)return null;Evidence e=es.get(f.path("evidenceId").asText());if(e==null)return null;
  String pointer=f.path("pointer").asText();JsonNode row=EvidenceUse.sample(e,f);
  // A stable source event ID proves the same stored event across pages. Without it,
  // keep distinct evidence IDs: equal values/timestamps alone need not mean the same event.
  String eventId=row.path("evidenceId").asText("");String owner=eventId.isBlank()?e.id():encode(List.of(e.source(),row.path("service"),row.path("environment"),row.path("instanceId"),row.path("deploymentVersion"),eventId));
  return encode(List.of(owner,eventId.isBlank()?pointer:pointer.replaceFirst("^/data/[0-9]+", ""),f.path("value")));
 }
}
