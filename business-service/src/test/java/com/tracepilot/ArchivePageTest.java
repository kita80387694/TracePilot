package com.tracepilot;

import static org.assertj.core.api.Assertions.*;
import com.tracepilot.observability.ArchivePage;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ArchivePageTest {
  @TempDir Path root;
  ObjectMapper json=new ObjectMapper();
  Instant start=Instant.parse("2026-09-11T00:00:00Z"), end=start.plusSeconds(3600);
  Map<String,Object> page(String cursor,int limit) throws Exception {
    return ArchivePage.read(root,true,start,end,cursor,limit,null,null,json);
  }
  @Test void snapshotPaginationSurvivesAppendWithoutDuplicateOrLostRecords() throws Exception {
    Path file=root.resolve("evidence.jsonl");
    for(int i=0;i<11;i++) Files.writeString(file,"{\"time\":\"2026-09-11T00:01:00Z\",\"id\":"+i+"}\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    var first=page("0",3); var ids=new ArrayList<Integer>();
    Map<String,Object> p=first;
    Files.writeString(file,"{\"time\":\"2026-09-11T00:01:00Z\",\"id\":99}\n",StandardOpenOption.APPEND);
    do {
      for(Object r:(List<?>)p.get("data")) ids.add(((JsonNode)r).path("id").asInt());
      assertThat((Integer)p.get("scannedBytes")).isLessThanOrEqualTo(ArchivePage.SCAN_BYTES);
      if(!(boolean)p.get("hasMore")) break;
      p=page((String)p.get("nextCursor"),3);
    } while(true);
    assertThat(ids).containsExactly(10,9,8,7,6,5,4,3,2,1,0);
    assertThatThrownBy(()->ArchivePage.read(root,true,start.plusSeconds(1),end,(String)first.get("nextCursor"),3,null,null,json))
        .isInstanceOf(IllegalArgumentException.class);
  }
  @Test void incompleteScanNeverClaimsNoDataAndOverlongRecordsCannotStall() throws Exception {
    Path file=root.resolve("evidence.jsonl");
    byte[] block=new byte[1024*1024]; Arrays.fill(block,(byte)'x');
    try(var out=Files.newOutputStream(file)){for(int i=0;i<10;i++)out.write(block);}
    var p=page("0",200);
    assertThat(p.get("status")).isEqualTo("PARTIAL");
    assertThat(p.get("hasMore")).isEqualTo(true);
    assertThat((Integer)p.get("scannedBytes")).isLessThanOrEqualTo(ArchivePage.SCAN_BYTES);
    assertThat((Long)p.get("remainingBytes")).isLessThan(10L*1024*1024);
    assertThat(p.get("gap")).isEqualTo("OVERLONG_RECORD_FRAGMENTS_OMITTED");
  }
  @Test void rotationInvalidatesCursorAndEmptyArchiveIsNoData() throws Exception {
    assertThat(page("0",2).get("status")).isEqualTo("NO_DATA");
    Path file=root.resolve("evidence.jsonl");
    Files.writeString(file,("{\"time\":\"2026-09-11T00:01:00Z\"}\n").repeat(5));
    String cursor=(String)page("0",1).get("nextCursor");
    Files.move(file,root.resolve("evidence.2026-09-11.jsonl"));
    Files.writeString(file,"");
    assertThatThrownBy(()->page(cursor,1)).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void scanBudgetBoundaryKeepsOrdinaryRecordForNextPage() throws Exception {
    Path file=root.resolve("evidence.jsonl");
    var expected=new ArrayList<Integer>();
    try(var writer=Files.newBufferedWriter(file)) {
      for(int i=0;i<22000;i++) {
        boolean selected=i%997==0;
        if(selected) expected.add(i);
        writer.write("{\"time\":\""+(selected?"2026-09-11T00:01:00Z":"2020-01-01T00:00:00Z")
            +"\",\"id\":"+i+",\"message\":\""+"x".repeat(1000)+"\"}\n");
      }
    }
    var ids=new ArrayList<Integer>();String cursor="0";int pages=0;
    do {
      var p=page(cursor,200);pages++;
      assertThat(p.get("oversizedRecords")).isEqualTo(0);
      assertThat(p.get("malformedRecords")).isEqualTo(0);
      assertThat((Integer)p.get("scannedBytes")).isLessThanOrEqualTo(ArchivePage.SCAN_BYTES);
      for(Object row:(List<?>)p.get("data")) ids.add(((JsonNode)row).path("id").asInt());
      if(!(boolean)p.get("hasMore"))break;
      cursor=(String)p.get("nextCursor");
      assertThat(pages).isLessThan(10);
    }while(true);
    Collections.reverse(expected);assertThat(ids).isEqualTo(expected);
    assertThat(pages).isGreaterThan(1);
  }
}
