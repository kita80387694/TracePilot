package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
/** Actual retained post-load archive, no model, fixed root from operator CLI. */
public class LargeArchiveProbe {
  public static void main(String[] args) throws Exception {
    Path root=Path.of(args[0]);
    var request=new Request("tracepilot-business","demo",Instant.parse(args[1]),Instant.parse(args[2]),"archive regression");
    var tools=new ReadTools(args.length>4?args[4]:"http://127.0.0.1:1",System.getenv("OBSERVER_TOKEN"),root.toString());
    List<Object> records=new ArrayList<>();Set<String> ids=new HashSet<>();String cursor="0";
    long started=System.nanoTime();int count=0;
    do {
      long before=System.nanoTime();var r=tools.probe(request,new Query("logs",Map.of("limit","200","cursor",cursor)));
      if(!Set.of("AVAILABLE","PARTIAL","NO_DATA").contains(r.status()))throw new IllegalStateException(r.status());
      for(var row:r.data().path("data")) {
        if(!ids.add(row.path("evidenceId").asText()))throw new IllegalStateException("duplicate record");
        count++;
      }
      records.add(Map.of("milliseconds",(System.nanoTime()-before)/1e6,"result",r));
      cursor=r.data().path("nextCursor").asText();
      if(!r.data().path("hasMore").asBoolean())break;
      if(records.size()>200)throw new IllegalStateException("probe page budget");
    }while(true);
    Files.writeString(Path.of(args[3]),encode(Map.of("java",System.getProperty("java.version"),
        "maxHeapBytes",Runtime.getRuntime().maxMemory(),"seconds",(System.nanoTime()-started)/1e9,
        "rows",count,"pages",records,"kind",args.length>4?"REAL_RETAINED_POSTLOAD_ARCHIVE_HTTP_TOOL":"REAL_RETAINED_POSTLOAD_ARCHIVE_OFFLINE_TOOL")));
    System.out.println("pages="+records.size()+" rows="+count+" maxHeap="+Runtime.getRuntime().maxMemory());
  }
}
