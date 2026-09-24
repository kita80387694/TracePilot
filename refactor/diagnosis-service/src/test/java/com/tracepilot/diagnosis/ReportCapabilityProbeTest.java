package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import java.util.*;import java.nio.file.*;import java.time.*;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
/** One guarded capability isolation request, never a diagnosis/acceptance run. */
@EnabledIfEnvironmentVariable(named="TRACEPILOT_REPORT_PROBE_AUTHORIZED",matches="v12-one-existing-input")
class ReportCapabilityProbeTest {
 @Test void oneReportOnlyRequest()throws Exception{
  Path root=Path.of(System.getenv("TRACEPILOT_ROOT")),out=root.resolve("evaluation/results/report-capability-v12");
  Files.writeString(out.resolve("started.guard"),Instant.now().toString(),StandardOpenOption.CREATE_NEW);
  String key=System.getenv().getOrDefault("DIAG_MODEL_KEY",System.getenv().getOrDefault("ANTHROPIC_API_KEY",""));
  String url=System.getenv().getOrDefault("DIAG_MODEL_URL",System.getenv().getOrDefault("ANTHROPIC_BASE_URL",""));
  if(key.isBlank()||!url.replaceAll("/+$","").equals("https://api.siliconflow.cn"))throw new IllegalStateException("MISSING_OR_UNEXPECTED_CONFIGURATION_NO_CALL");
  var record=new LinkedHashMap<String,Object>();var transport=new ArrayList<Map<String,Object>>();record.put("started",Instant.now().toString());record.put("inputTokens",null);record.put("outputTokens",null);record.put("fees","UNKNOWN");
  try(var capture=new ModelCapture(root.resolve(".local/report-capability-v12/private/capture"),key)){
   var gateway=new SpringAiGateway(key,url,"deepseek-ai/DeepSeek-V4-Flash",capture,"named-tools-v2");
   try{
    String input=Files.readString(out.resolve("input.json"));
    var reply=gateway.callAction(true,false,input,"REPORT_CAPABILITY_V12_NOT_DIAGNOSIS",1,x->{transport.add(x);try{Files.writeString(out.resolve("transport.json"),encode(transport));}catch(Exception ignored){}});
    record.put("action",reply.action());record.put("model",reply.model());record.put("protocol",reply.protocol());record.put("inputTokens",reply.inputTokens());record.put("outputTokens",reply.outputTokens());
    var provided=new HashSet<String>();for(String k:List.of("causeFactRegistry","backgroundFactRegistry"))for(var f:parse(input).path(k))provided.add(f.path("factId").asText());
    var source=root.resolve("evaluation/results/window-delivery-v12-live");var evidence=new ArrayList<Evidence>();for(var e:JSON.readTree(Files.readString(source.resolve("exports/DEV-P1/evidence.json"))))evidence.add(JSON.treeToValue(e,Evidence.class));
    var req=JSON.treeToValue(JSON.readTree(Files.readString(source.resolve("cases/DEV-P1.json"))).path("input"),Request.class);
    var result=DirectContract.render(reply.action(),evidence,req,provided);record.put("validationErrors",result.errors());record.put("renderedReport",result.report());record.put("status",result.errors().isEmpty()?"CONTRACT_PASS_SEMANTICS_UNREVIEWED":"CONTRACT_FAILED");
   }catch(Exception e){record.put("status","FAILED");record.put("errorType",e.getClass().getSimpleName());}
   finally{record.put("finished",Instant.now().toString());record.put("transport",transport);Files.writeString(out.resolve("result.json"),encode(record));}
  }
 }
}
