package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
/** v7 initial hypotheses use the same reference IR as REVIEW. No second free-text fact channel. */
final class InitialContract {
 static JsonNode schema(){var plan=ReviewContract.planSchema().deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)plan.at("/properties/premiseFactIds")).put("minItems",0);return tree(Map.of("type","array","maxItems",3,"items",plan));}
 static List<Map<String,Object>> errors(JsonNode candidates){var errors=new ArrayList<String>();ContractErrors.validate(candidates,schema(),"/candidates",errors);return errors.stream().map(e->map(e)).toList();}
 static Hypotheses.Review seed(JsonNode candidates,List<Evidence> es,Request r){
  var errors=new ArrayList<String>();ContractErrors.validate(candidates,schema(),"/candidates",errors);var rows=new ArrayList<Object>();int i=0;
  for(var candidate:candidates){if(!candidate.isObject())continue;String p="/candidates/"+i;ReviewContract.validateReferences(candidate,es,r,p,errors);var ids=new LinkedHashSet<String>();var known=new HashMap<String,JsonNode>();FactReferences.catalog(es,r).forEach(f->known.put(tree(f).path("factId").asText(),tree(f)));for(String role:ReviewContract.ROLES)for(var ref:candidate.path(role)){var f=known.get(ref.asText());if(f!=null)ids.add(f.path("evidenceId").asText());}
   rows.add(Map.of("hypothesisId","H"+(++i),"initialPlan",candidate,"initialEvidenceIds",ids,"status","PENDING","gaps",List.of("VERIFY_MECHANISM"),"contractVersion",ReviewContract.VERSION));
  }
  return new Hypotheses.Review(errors.isEmpty()?rows:List.of(),List.copyOf(errors));
 }
}
