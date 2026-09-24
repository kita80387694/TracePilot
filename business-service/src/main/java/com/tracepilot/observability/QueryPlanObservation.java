package com.tracepilot.observability;

import static com.tracepilot.api.ApiSupport.require;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracepilot.reporting.UsageReport;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

/** Fixed statement allowlist. EXPLAIN only; no caller SQL, ANALYZE, or control state. */
@RestController @Profile("demo") @RequestMapping("/ops/query-plan")
public class QueryPlanObservation {
 private final AuxiliaryDatabase db;
 private final ObjectMapper json;
 private final String version,environment;
 public QueryPlanObservation(AuxiliaryDatabase db,ObjectMapper json,@Value("${app.deployment-version}") String version,@Value("${app.environment}") String environment){this.db=db;this.json=json;this.version=version;this.environment=environment;}
 public static String fingerprint(String statement){
  try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(statement.getBytes(StandardCharsets.UTF_8)));}
  catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
 }
 @GetMapping public Object query(@RequestParam String statementFingerprint)throws Exception{
  require(statementFingerprint.matches("[a-f0-9]{64}"),400,"INVALID_STATEMENT_FINGERPRINT");
  String statement=null;
  for(boolean shape:new boolean[]{false,true}){String sql=UsageReport.sql(shape);if(fingerprint(sql).equals(statementFingerprint))statement=sql;}
  require(statement!=null,400,"STATEMENT_NOT_REGISTERED");
  Instant started=Instant.now();
  var result=new LinkedHashMap<String,Object>();
  result.put("service","tracepilot-business");result.put("environment",environment);result.put("deploymentVersion",version);
  result.put("time",started.toString());result.put("statementFingerprint",statementFingerprint);
  result.put("queryId","usage-lookup-v1");result.put("statementTemplate",statement);
  result.put("observationMeaning","Current optimizer estimate for the registered statement and fixed application binding; not historical execution, lock-wait measurement or EXPLAIN ANALYZE. Correlate fingerprint and deployment with actual execution logs; schema/statistics may have changed.");
  try{
   String plan=db.jdbc.queryForObject("EXPLAIN FORMAT=JSON "+statement,String.class,4242);
   require(plan!=null&&plan.getBytes(StandardCharsets.UTF_8).length<=65536,503,"PLAN_RESPONSE_TOO_LARGE");
   result.put("plan",json.readTree(plan));result.put("status","AVAILABLE");
  }catch(org.springframework.dao.DataAccessException failure){
   result.put("status","UNAVAILABLE");result.put("errorType",failure.getClass().getSimpleName());
  }
  result.put("collectedAt",Instant.now().toString());return result;
 }
}
