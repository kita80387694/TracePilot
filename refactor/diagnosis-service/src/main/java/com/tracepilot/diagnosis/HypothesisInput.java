package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import java.util.*;import com.fasterxml.jackson.databind.JsonNode;
/** One candidate item contract, validated before any checkpoint mutation. No aliases. */
final class HypothesisInput {
 static final int MAX_TEXT=2000,MAX_REFS=12;
 static final Set<String> FIELDS=Set.of("hypothesis","evidenceIds");
 static Object schema(){return Map.of("type","array","maxItems",3,"items",Map.of("type","object","additionalProperties",false,"required",List.of("hypothesis","evidenceIds"),"properties",Map.of("hypothesis",Map.of("type","string","minLength",1,"maxLength",MAX_TEXT,"pattern",".*\\S.*","description","Nonblank hypothesis text; exact field name, no aliases."),"evidenceIds",Map.of("type","array","maxItems",MAX_REFS,"uniqueItems",true,"items",Map.of("type","string","minLength",1)))));}
 static List<Map<String,Object>> errors(JsonNode items){var errors=new ArrayList<Map<String,Object>>();
  if(!items.isArray()||items.size()>3){ActionDiagnostics.issue(errors,"/candidates",items,"array 0..3");return errors;}
  int i=0;for(var item:items){String p="/candidates/"+(i++);if(!item.isObject()){ActionDiagnostics.issue(errors,p,item,"object");continue;}
   item.fieldNames().forEachRemaining(k->{if(!FIELDS.contains(k))ActionDiagnostics.issue(errors,p+"/"+k,item.path(k),"unknown field forbidden");});
   var text=item.path("hypothesis");if(!text.isTextual()||text.asText().isBlank()||text.asText().length()>MAX_TEXT)ActionDiagnostics.issue(errors,p+"/hypothesis",text,"required nonblank string, <=2000 UTF16 code units");
   var refs=item.path("evidenceIds");if(!refs.isArray()||refs.size()>MAX_REFS)ActionDiagnostics.issue(errors,p+"/evidenceIds",refs,"required array 0..12 unique evidence IDs");
   else{var seen=new HashSet<String>();int n=0;for(var ref:refs){if(!ref.isTextual()||ref.asText().isBlank()||!seen.add(ref.asText()))ActionDiagnostics.issue(errors,p+"/evidenceIds/"+n,ref,"nonblank unique evidence ID");n++;}}
  }return errors;
 }
}
