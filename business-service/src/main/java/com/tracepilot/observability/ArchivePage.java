package com.tracepilot.observability;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Bounded reverse append-order pages. The cursor pins file identities and a byte high-water mark. */
public final class ArchivePage {
  public static final int SCAN_BYTES = 8 * 1024 * 1024, MAX_RECORD = 65536, RETURN_BYTES = 384 * 1024;
  private ArchivePage() {}
  public static boolean validCursor(String cursor) {
    return cursor.equals("0") || cursor.matches("v1\\.[0-9]{1,19}\\.[0-9]{1,19}\\.[a-f0-9]{24}\\.[a-f0-9]{24}");
  }
  private static String hash(String text) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
  }
  public static Map<String,Object> read(Path root, boolean logs, Instant start, Instant end,
      String cursor, int limit, String trace, String level, ObjectMapper json) throws Exception {
    if (!validCursor(cursor)) throw new IllegalArgumentException("INVALID_CURSOR");
    List<Path> paths;
    if (!Files.isDirectory(root)) paths = List.of();
    else try (var files = Files.list(root)) {
      paths = files.filter(p -> p.getFileName().toString().matches(logs
          ? "evidence(?:\\.\\d{4}-\\d{2}-\\d{2})?\\.jsonl" : "metrics\\.\\d{4}-\\d{2}-\\d{2}\\.jsonl"))
          .sorted(Comparator.comparing(p -> p.getFileName().toString().equals("evidence.jsonl")
              ? "z" : p.getFileName().toString())).toList();
    }
    long[] sizes = new long[paths.size()]; long total = 0;
    StringBuilder identity = new StringBuilder();
    for (int i=0;i<paths.size();i++) {
      Path p=paths.get(i);
      if (Files.isSymbolicLink(p) || !p.toRealPath().startsWith(root.toRealPath())) throw new SecurityException();
      var attr=Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class);
      sizes[i]=attr.size(); total+=sizes[i];
      identity.append(p.getFileName()).append(':').append(attr.fileKey()).append(':').append(attr.creationTime());
      // Only the last active file may grow between pages; older file mutations invalidate the cursor.
      if(i<paths.size()-1) identity.append(':').append(attr.size()).append(':').append(attr.lastModifiedTime());
    }
    String fingerprint=hash(identity.toString());
    String scope=hash(logs+"|"+start+"|"+end+"|"+trace+"|"+level);
    long high=total, position=total;
    if(!cursor.equals("0")) {
      String[] c=cursor.split("\\.");position=Long.parseLong(c[1]);high=Long.parseLong(c[2]);
      if(position<0 || position>high || high>total || !c[3].equals(fingerprint) || !c[4].equals(scope))
        throw new IllegalArgumentException("STALE_OR_WRONG_SCOPE_CURSOR_RESTART_AT_0");
    }
    List<JsonNode> rows=new ArrayList<>(); int scanned=0, malformed=0, oversized=0, returned=0;
    outer: while(position>0 && scanned<SCAN_BYTES && rows.size()<limit && returned<RETURN_BYTES) {
      if(Thread.currentThread().isInterrupted()) throw new InterruptedException();
      long base=0; int index=0;
      while(index<sizes.length && position>base+sizes[index]) base+=sizes[index++];
      if(index>=paths.size()) break;
      long offset=position-base;
      if(offset==0) {position=base;continue;}
      int count=(int)Math.min(offset, SCAN_BYTES-scanned);
      byte[] bytes=new byte[count];
      try(var file=new RandomAccessFile(paths.get(index).toFile(),"r")) {
        file.seek(offset-count);file.readFully(bytes);
      }
      int right=count;
      if(right>0 && bytes[right-1]=='\n') right--;
      boolean consumed=false;
      while(right>=0) {
        int left=right-1; while(left>=0 && bytes[left]!='\n') left--;
        if(left<0 && offset>count) break; // Keep boundary record for the next page/chunk.
        int length=right-left-1;
        long next=base+offset-count+left+1;
        scanned+=(int)(position-next);position=next;consumed=true;
        if(length>MAX_RECORD) oversized++;
        else if(length>0) try {
          JsonNode row=json.readTree(new String(bytes,left+1,length,StandardCharsets.UTF_8));
          Instant time=Instant.parse(row.path("time").asText());
          if(!time.isBefore(start) && !time.isAfter(end)
              && (trace==null || trace.equals(row.path("traceId").asText()))
              && (level==null || level.equals(row.path("level").asText()))) {
            rows.add(row);returned+=length;
          }
        } catch(Exception invalid) {malformed++;}
        if(rows.size()>=limit || returned>=RETURN_BYTES || scanned>=SCAN_BYTES) break outer;
        if(left<0) break;
        right=left;
      }
      if(!consumed) {
        // A remainder smaller than this page's budget may be an ordinary split line.
        // Retry from the unchanged record boundary with a fresh budget on the next page.
        if(scanned>0) break;
        // An overlong line cannot force unbounded allocation or prevent cursor progress.
        position-=count;scanned+=count;oversized++;
      }
    }
    boolean more=position>0;
    String status=more || malformed>0 || oversized>0 ? "PARTIAL" : rows.isEmpty()?"NO_DATA":"AVAILABLE";
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("status",status);result.put("data",rows);result.put("hasMore",more);
    result.put("nextCursor",more?"v1."+position+"."+high+"."+fingerprint+"."+scope:"0");
    result.put("start",start.toString());result.put("end",end.toString());
    result.put("ordering","REVERSE_APPEND_ORDER");result.put("scannedBytes",scanned);
    result.put("snapshotBytes",high);result.put("remainingBytes",position);
    result.put("malformedRecords",malformed);result.put("oversizedRecords",oversized);
    result.put("coverage",more?"INCOMPLETE_CONTINUE_WITH_SAME_WINDOW_AND_NEXT_CURSOR":"SNAPSHOT_SCANNED");
    result.put("gap",oversized>0?"OVERLONG_RECORD_FRAGMENTS_OMITTED":malformed>0?"MALFORMED_RECORDS_OMITTED":"NONE");
    return result;
  }
}
