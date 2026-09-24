package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

class ModelCaptureTest {
 @TempDir Path temp;
 static final String MODEL="deepseek-ai/DeepSeek-V4-Flash";
 static class Endpoint implements AutoCloseable {
  HttpServer server;List<String> requests=Collections.synchronizedList(new ArrayList<>());String mode;
  Endpoint(String mode)throws Exception {this.mode=mode;server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
   server.createContext("/v1/messages",e->{
    requests.add(new String(e.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
    if(mode.equals("disconnect")){e.close();return;}
    String answer=mode.equals("empty")?"":mode.equals("error")?"{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"fixture error\"}}":encode(Map.of("id","local-fixture","type","message","role","assistant","model",MODEL,
      "content",List.of(Map.of("type","thinking","thinking","PRIVATE_THOUGHT_NEVER_STORE","signature","fixture"),Map.of("type","text","text","{\"type\":\"ready\"}")),"stop_reason","end_turn","usage",Map.of("input_tokens",12,"output_tokens",8)));
    byte[] body=answer.getBytes(java.nio.charset.StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");
    e.sendResponseHeaders(mode.equals("error")?400:200,mode.equals("chunked")?0:body.length==0?-1:body.length);
    if(body.length>0)e.getResponseBody().write(body);e.close();});server.start();
  }
  String url(){return "http://127.0.0.1:"+server.getAddress().getPort();}
  public void close(){server.stop(0);}
 }
 @Test void enabledAndDisabledHaveIdenticalWireRequestsAndDecodedResponsesForFixedAndChunked()throws Exception {
  for(String mode:List.of("fixed","chunked"))try(var ep=new Endpoint(mode);var cap=new ModelCapture(temp.resolve(mode),"test-key")){
   var off=new SpringAiGateway("test-key",ep.url(),MODEL);var on=new SpringAiGateway("test-key",ep.url(),MODEL,cap);
   String user="{\"request\":{\"symptom\":\"notification missing\"},\"evidence\":[{\"id\":\"E1\",\"value\":8}]}";
   var a=off.call("Return JSON",user);var b=on.call("Return JSON",user,"task-test",1);cap.awaitWrites();
   assertThat(ep.requests).hasSize(2);assertThat(ep.requests.get(0)).isEqualTo(ep.requests.get(1));assertThat(encode(a)).isEqualTo(encode(b));
   assertThat(parse(ep.requests.get(1)).path("stream").asBoolean(false)).isFalse();
   var files=Files.list(temp.resolve(mode)).toList();assertThat(files).hasSize(2);
   String captured=files.stream().map(p->{try{return Files.readString(p);}catch(Exception e){throw new RuntimeException(e);}}).reduce("",String::concat);
   assertThat(captured).doesNotContain("PRIVATE_THOUGHT_NEVER_STORE","test-key").contains("task-test","notification missing","SPRING_AI_RESPONSE_OBJECT");
   var request=files.stream().filter(p->p.toString().endsWith("-request.json")).findFirst().orElseThrow();var body=parse(Files.readString(request)).path("payload");
   assertThat(body.path("user").path("complete").asBoolean()).isTrue();assertThat(parse(body.path("user").path("text").asText())).isEqualTo(parse(user));
  }
 }
 @Test void emptyHttpErrorAndInterruptedConnectionAreUnchangedAndRecordedAsUnknown()throws Exception {
  for(String mode:List.of("empty","error","disconnect"))try(var ep=new Endpoint(mode);var cap=new ModelCapture(temp.resolve(mode),"test-key")){
   var off=new SpringAiGateway("test-key",ep.url(),MODEL);var on=new SpringAiGateway("test-key",ep.url(),MODEL,cap);
   Throwable a=catchThrowable(()->off.call("Return JSON","{}"));Throwable b=catchThrowable(()->on.call("Return JSON","{}","task-error",1));
   assertThat(a).isNotNull();assertThat(b).isNotNull();assertThat(Workflow.rootError(a).getClass()).isEqualTo(Workflow.rootError(b).getClass());cap.awaitWrites();
   assertThat(new HashSet<>(ep.requests)).hasSize(1);
   var file=Files.list(temp.resolve(mode)).filter(p->p.toString().endsWith("-error.json")).findFirst().orElseThrow();
   assertThat(Files.readString(file)).contains("UNKNOWN","NOT_AVAILABLE").doesNotContain("fixture error");
  }
 }
 @Test void unwritableCaptureDoesNotChangeSuccessfulModelCall()throws Exception {
  Path file=temp.resolve("not-directory");Files.writeString(file,"fixture");
  try(var ep=new Endpoint("fixed");var cap=new ModelCapture(file,"test-key")){
   var result=new SpringAiGateway("test-key",ep.url(),MODEL,cap).call("Return JSON","{}");cap.awaitWrites();
   assertThat(result.action().path("type").asText()).isEqualTo("ready");assertThat(cap.failures.get()).isGreaterThan(0);
  }
 }
 @Test void rejectedCaptureQueueDoesNotChangeSuccessfulModelCall()throws Exception {
  try(var ep=new Endpoint("fixed");var cap=new ModelCapture(temp.resolve("closed"),"test-key")){
   cap.close();var result=new SpringAiGateway("test-key",ep.url(),MODEL,cap).call("Return JSON","{}");
   assertThat(result.action().path("type").asText()).isEqualTo("ready");assertThat(cap.failures.get()).isEqualTo(2);
  }
 }
 @Test void explicitRedactionAndTruncationDoNotPretendComplete()throws Exception {
  try(var cap=new ModelCapture(temp.resolve("redact"),"model-secret")){
   String raw="{\"password\":\"abc\",\"thinking\":\"private\",\"email\":\"somebody@example.com\",\"text\":\"model-secret\"}";
   assertThat(cap.bounded(raw).get("text").toString()).doesNotContain("abc","private","somebody@example.com","model-secret");
   assertThat(cap.bounded("<think>private").get("text")).isEqualTo("[INCOMPLETE_THINKING_BLOCK_REMOVED]");
   var longText=cap.bounded("x".repeat(ModelCapture.LIMIT+1));assertThat(longText.get("complete")).isEqualTo(false);assertThat(longText.get("truncated")).isEqualTo(true);
  }
 }
 @Test void sevenDayRetentionOnlyDeletesOwnedCaptureNames()throws Exception {
  Path dir=temp.resolve("retention");Files.createDirectories(dir);
  Path expired=dir.resolve(UUID.randomUUID()+"-request.json"),other=dir.resolve("keep.json");
  Files.writeString(expired,"{}");Files.writeString(other,"{}");var old=FileTime.from(Instant.now().minus(Duration.ofDays(8)));Files.setLastModifiedTime(expired,old);Files.setLastModifiedTime(other,old);
  try(var cap=new ModelCapture(dir,"")){cap.purge();assertThat(expired).doesNotExist();assertThat(other).exists();}
 }
 @Test void concurrentRetentionAndWriterPurgeDoNotRaceDeletes()throws Exception {
  Path dir=temp.resolve("concurrent-retention");Files.createDirectories(dir);
  for(int i=0;i<60;i++){Path p=dir.resolve(UUID.randomUUID()+"-response.json");Files.writeString(p,"{}");Files.setLastModifiedTime(p,FileTime.from(Instant.now().minus(Duration.ofDays(8))));}
  try(var cap=new ModelCapture(dir,"");var pool=java.util.concurrent.Executors.newFixedThreadPool(4)){
   var start=new java.util.concurrent.CountDownLatch(1);var work=new java.util.ArrayList<java.util.concurrent.Future<?>>();
   for(int i=0;i<4;i++)work.add(pool.submit(()->{start.await();cap.purge();return null;}));start.countDown();
   for(var item:work)item.get();try(var files=Files.list(dir)){assertThat(files.count()).isZero();}
  }
 }
}
