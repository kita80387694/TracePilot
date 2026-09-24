package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class ProjectionBoundsTest {
 Evidence evidence(Object data){return new Evidence("E","metrics","AVAILABLE","2026-09-22T00:00:00Z","2026-09-22T00:01:00Z",Map.of(),tree(data));}
 @Test void exactArrayBoundIsCompleteButOverflowIsDisclosed(){
  var rows=new ArrayList<Object>();for(int i=0;i<Reports.MAX_ARRAY_ITEMS;i++)rows.add(Map.of("value",i));
  assertThat(Reports.project(evidence(Map.of("data",rows))).complete()).isTrue();
  rows.add(Map.of("value",999));var p=Reports.project(evidence(Map.of("data",rows)));
  assertThat(p.complete()).isFalse();assertThat(p.facts()).hasSize(Reports.MAX_ARRAY_ITEMS);
 }
 @Test void nestedArrayOverflowIsNotSilentlyMarkedComplete(){
  var values=new ArrayList<Integer>();for(int i=0;i<=Reports.MAX_ARRAY_ITEMS;i++)values.add(i);
  assertThat(Reports.project(evidence(Map.of("data",List.of(Map.of("values",values))))).complete()).isFalse();
 }
 @Test void scalarBoundIsExactAndPointersRemainResolvable(){
  var fields=new LinkedHashMap<String,Object>();for(int i=0;i<Reports.MAX_SCALARS;i++)fields.put("v"+i,i);
  var e=evidence(fields);var p=Reports.project(e);assertThat(p.complete()).isTrue();assertThat(p.facts()).hasSize(Reports.MAX_SCALARS);
  for(var f:p.facts()){var n=tree(f);assertThat(e.data().at(n.path("pointer").asText())).isEqualTo(n.path("value"));}
  fields.put("overflow",1);assertThat(Reports.project(evidence(fields)).complete()).isFalse();
 }
}
