package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import java.util.*;

/** Position-preserving syntax-only redaction. Never retains arbitrary words or numeric values. */
final class SyntaxCapture {
  static final int LIMIT=8192;
  static final Set<String> KEYS=Set.of("type","tool","args","report","summary","facts","text","evidenceId","pointer","value","candidates","cause","hypothesis","inference","confidence","evidenceIds","conflicts","gaps","actions","mechanism","support","contradictions","toVerify","scope","factId");
  static String mask(String s) {
    StringBuilder out=new StringBuilder();
    for(int i=0;i<s.length();) {
      char c=s.charAt(i);
      if(c=='"') {
        int start=i++; boolean escaped=false;
        while(i<s.length()) {char n=s.charAt(i++);if(n=='"'&&!escaped)break;if(n=='\\'&&!escaped)escaped=true;else escaped=false;}
        String token=s.substring(start,i);int next=i;while(next<s.length()&&Character.isWhitespace(s.charAt(next)))next++;
        if(token.length()>=2 && token.endsWith("\"") && next<s.length() && s.charAt(next)==':' && KEYS.contains(token.substring(1,token.length()-1))) {out.append(token);continue;}
        boolean slash=false;int unicode=0;
        for(int j=0;j<token.length();j++) {
          char n=token.charAt(j);
          if(unicode>0){out.append(Character.digit(n,16)>=0?'0':'x');unicode--;continue;}
          if(slash){out.append("\"\\/bfnrtu".indexOf(n)>=0?n:'x');unicode=n=='u'?4:0;slash=false;continue;}
          if(n=='\\'){out.append(n);slash=true;}
          else out.append(n=='"'||n<32?n:'x');
        }
      } else if(Character.isLetter(c)) {
        int start=i;while(i<s.length()&&Character.isLetter(s.charAt(i)))i++;
        String word=s.substring(start,i);out.append(Set.of("true","false","null").contains(word)?word:"x".repeat(word.length()));
      } else {out.append(Character.isDigit(c)?(c=='0'?'0':'1'):"{}[],:.-+ \\t\\r\\n".indexOf(c)>=0||c<32?c:'x');i++;}
    }
    return out.toString();
  }
  static Map<String,Object> capture(String raw,String parser,long offset,int line,int column) {
    String body=mask(raw),text=mask(parser);int center=(int)Math.max(0,Math.min(text.length(),offset));int from=Math.max(0,center-80),to=Math.min(text.length(),center+80);
    return Map.of("body",bounded(body),"parserText",bounded(text),"offset",offset,"line",line,"column",column,
        "positionUnit","Jackson Reader UTF-16 charOffset (zero-based); line/column one-based; normalized parser text",
        "nearby",Map.of("start",from,"endExclusive",to,"text",text.substring(from,to)),
        "redaction","syntax-only-v1: UTF-16 length preserved; prose/unknown keys/numbers masked; not original semantic values");
  }
  static Map<String,Object> bounded(String s){return Map.of("text",s.substring(0,Math.min(s.length(),LIMIT)),"originalCharacters",s.length(),"truncated",s.length()>LIMIT,"complete",s.length()<=LIMIT);}
}
