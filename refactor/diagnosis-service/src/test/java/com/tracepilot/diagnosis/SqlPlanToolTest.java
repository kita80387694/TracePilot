package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class SqlPlanToolTest {
 @TempDir java.nio.file.Path root;
 @Test void fixedPlanToolRejectsSqlUrlsMissingOrMalformedFingerprints(){
  ReadTools.validate(new Query("sqlPlan",Map.of("statementFingerprint","a".repeat(64))));
  for(var args:List.of(Map.<String,String>of(),Map.of("statementFingerprint","select"),Map.of("statementFingerprint","a".repeat(64),"sql","SELECT 1"),Map.of("url","http://localhost")))
   assertThatThrownBy(()->ReadTools.validate(new Query("sqlPlan",args))).isInstanceOf(SecurityException.class);
  String schema=encode(DirectContract.schema(false));assertThat(schema).contains("sqlPlan","statementFingerprint","not historical execution");
 }
 @Test void actualHttpRequestUsesOnlyReadonlyEndpointAndKeepsCurrentObservationMeaning()throws Exception{
  var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
  var seen=new java.util.concurrent.atomic.AtomicReference<String>();
  server.createContext("/ops/query-plan",ex->{
   seen.set(ex.getRequestURI().toString());byte[] b=encode(Map.of("status","AVAILABLE","service","tracepilot-business","environment","demo","observationMeaning","current estimate, not historical execution","plan",Map.of("access_type","ALL"))).getBytes(java.nio.charset.StandardCharsets.UTF_8);
   ex.sendResponseHeaders(200,b.length);try(var out=ex.getResponseBody()){out.write(b);}
  });server.start();
  try{
   var tool=new ReadTools("http://127.0.0.1:"+server.getAddress().getPort(),"test-token",root.toString());
   var r=new Request("tracepilot-business","demo",Instant.now().minusSeconds(30),Instant.now().minusSeconds(1),"slow response");
   var result=tool.query(r,new Query("sqlPlan",Map.of("statementFingerprint","b".repeat(64))),Set.of());
   assertThat(result.status()).isEqualTo("AVAILABLE");assertThat(seen.get()).isEqualTo("/ops/query-plan?statementFingerprint="+"b".repeat(64));
   assertThat(result.data().path("observationMeaning").asText()).contains("not historical execution");
  }finally{server.stop(0);}
 }
}
