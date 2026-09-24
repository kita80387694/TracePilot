package com.tracepilot.diagnosis;

import static com.tracepilot.diagnosis.Domain.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Best-effort object-level capture. Never reads a transport stream or changes model input. */
final class ModelCapture implements AutoCloseable {
  static final String VERSION="object-capture-v1";
  static final int LIMIT=250000;
  private final Path root;
  private final String secret;
  private final ThreadPoolExecutor writer;
  private final ScheduledExecutorService retention;
  final AtomicLong failures=new AtomicLong();
  ModelCapture(Path root,String secret) {
    this.root=root==null?null:root.toAbsolutePath().normalize();this.secret=secret;
    writer=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{var t=new Thread(r,"model-capture");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    retention=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"capture-retention");t.setDaemon(true);return t;});
    if(root!=null)retention.scheduleWithFixedDelay(()->{try{purge();}catch(Exception e){failed();}},0,1,TimeUnit.HOURS);
  }
  static ModelCapture disabled(){return new ModelCapture(null,"");}
  String begin(String task,int ordinal,String system,String user,Map<String,Object> configuration) {
    if(root==null)return "DISABLED";
    String id=UUID.randomUUID().toString();
    enqueue(id,"request",()->Map.of("taskId",task,"ordinal",ordinal,"system",bounded(system),"user",bounded(user),"configuration",configuration,
      "layer","MODEL_GATEWAY_OBJECT_TEXT_NOT_HTTP_WIRE","delivery","ATTEMPTED_NOT_PROOF_OF_PROVIDER_RECEIPT"));
    return id;
  }
  void response(String id,org.springframework.ai.chat.model.ChatResponse response) {
    if(root==null)return;
    enqueue(id,"response",()->{
      var texts=SpringAiGateway.answers(response).stream().map(g->bounded(g.getOutput().getText())).toList();
      var m=new LinkedHashMap<String,Object>();m.put("nonThinkingTextBlocks",texts);m.put("stream",false);
      m.put("nativeToolCalls",response.getResults().stream().filter(g->!g.getOutput().getMetadata().containsKey("signature")&&!g.getOutput().getMetadata().containsKey("data")).flatMap(g->g.getOutput().getToolCalls().stream()).map(c->Map.of("id",bounded(c.id()),"name",bounded(c.name()),"arguments",bounded(c.arguments()))).toList());
      m.put("finishReason",response.getResults().isEmpty()?"UNKNOWN":String.valueOf(response.getResults().get(0).getMetadata().getFinishReason()));
      var usage=response.getMetadata().getUsage();m.put("inputTokens",usage==null?null:usage.getPromptTokens());m.put("outputTokens",usage==null?null:usage.getCompletionTokens());
      m.put("model",response.getMetadata().getModel());m.put("layer","SPRING_AI_RESPONSE_OBJECT_NO_STREAM_REREAD");return m;
    });
  }
  void error(String id,Exception error){if(root!=null)enqueue(id,"error",()->Map.of("errorType",Workflow.rootError(error).getClass().getSimpleName(),"tokenUsage","UNKNOWN","responseBody","NOT_AVAILABLE"));}
  private void failed(){failures.incrementAndGet();org.slf4j.LoggerFactory.getLogger(ModelCapture.class).warn("model_capture_incomplete count={}",failures.get());}
  private void enqueue(String id,String kind,Callable<Map<String,Object>> value){
    try {writer.execute(()->{try{
      protectRoot();purge();var now=Instant.now();var record=new LinkedHashMap<String,Object>();
      record.put("captureId",id);record.put("captureVersion",VERSION);record.put("kind",kind);record.put("time",now.toString());record.put("expiresAt",now.plus(Duration.ofDays(7)).toString());record.put("payload",value.call());
      Path temp=root.resolve(id+"-"+kind+".tmp"),dest=root.resolve(id+"-"+kind+".json");
      Files.writeString(temp,encode(record));Files.move(temp,dest,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }catch(Exception e){failed();}});}catch(Exception e){failed();}
  }
  private void protectRoot() throws Exception {
    Files.createDirectories(root);
    var acl=Files.getFileAttributeView(root,AclFileAttributeView.class);
    if(acl!=null){var owner=Files.getOwner(root);acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner).setPermissions(EnumSet.allOf(AclEntryPermission.class)).setFlags(AclEntryFlag.DIRECTORY_INHERIT,AclEntryFlag.FILE_INHERIT).build()));}
    else Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));
  }
  // Timer and writer share this instance: serialize traversal/deletion on Windows.
  synchronized void purge() throws Exception {
    if(root==null || !Files.isDirectory(root))return;
    try(var files=Files.list(root)){for(var p:files.filter(p->p.getFileName().toString().matches("[0-9a-f-]{36}-(request|response|error)\\.(json|tmp)")).toList())
      if(!Files.isSymbolicLink(p) && Files.getLastModifiedTime(p).toInstant().isBefore(Instant.now().minus(Duration.ofDays(7))))Files.deleteIfExists(p);}
  }
  Map<String,Object> bounded(String raw) {
    if(raw!=null && raw.length()>LIMIT)return Map.of("text","[CAPTURE_BODY_OMITTED_SIZE_LIMIT]","redacted",true,"complete",false,"truncated",true,"originalCharacters",raw.length(),"limitCharacters",LIMIT);
    String safe=sanitize(raw,secret);boolean truncated=safe.length()>LIMIT;
    return Map.of("text",truncated?safe.substring(0,LIMIT):safe,"redacted",true,"complete",!truncated,"truncated",truncated,"redactedCharacters",safe.length(),"limitCharacters",LIMIT);
  }
  static String sanitize(String raw,String secret){
    String s=raw==null?"":raw;
    if(secret!=null&&!secret.isEmpty())s=s.replace(secret,"[REDACTED]");
    s=ReportExport.redact(s);
    s=s.replaceAll("(?is)<think(?:ing)?>.*?</think(?:ing)?>","[THINKING_REMOVED]");
    if(s.toLowerCase(Locale.ROOT).contains("<think>"))return "[INCOMPLETE_THINKING_BLOCK_REMOVED]";
    s=s.replaceAll("(?i)((?:password|api[_-]?key|token|secret|authorization)\\s*[\\\"]?\\s*[:=]\\s*[\\\"]?)[^\\s\\\",}]+","$1[REDACTED]");
    if(s.indexOf('@')>=0)s=s.replaceAll("(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,253}\\.[A-Za-z]{2,24}","[EMAIL_REDACTED]");
    // Preserve structured evidence fields; remove explicitly marked private blocks if embedded as JSON.
    try{return encode(scrub(JSON.readTree(s)));}catch(Exception ignored){return s;}
  }
  private static com.fasterxml.jackson.databind.JsonNode scrub(com.fasterxml.jackson.databind.JsonNode n){
    if(n.isObject()){var o=JSON.createObjectNode();n.fields().forEachRemaining(e->{if(e.getKey().matches("(?i).*(thinking|reasoning_content|password|secret|authorization|api.?key).*"))o.put(e.getKey(),"[REDACTED]");else o.set(e.getKey(),scrub(e.getValue()));});return o;}
    if(n.isArray()){var a=JSON.createArrayNode();n.forEach(v->a.add(scrub(v)));return a;}return n;
  }
  void awaitWrites() throws Exception {writer.submit(()->{}).get(10,TimeUnit.SECONDS);}
  public void close(){retention.shutdownNow();writer.shutdown();}
}
