package com.tracepilot.diagnosis;
import static com.tracepilot.diagnosis.Domain.*;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
class SyntaxCaptureTest {
 @Test void masksPrivateValuesButPreservesSyntaxAndOffsets(){
  String raw="  {\"type\":\"review\",\"gaps\":[\"Alice secret 123\\n\\\"中文\"],}  ";
  var a=SpringAiGateway.parseAnswer(raw,"end_turn");
  assertThat(a.path("type").asText()).isEqualTo("INVALID_JSON");
  var c=a.path("syntaxCapture");
  assertThat(c.at("/body/text").asText()).hasSize(raw.length()).doesNotContain("Alice","secret","123","中文");
  assertThat(c.at("/parserText/text").asText()).hasSize(raw.trim().length()).contains("\\n","\\\"");
  assertThat(c.path("offset").asLong()).isPositive();
  assertThat(SpringAiGateway.parseAnswer(c.at("/parserText/text").asText(),"end_turn").path("characterOffset")).isEqualTo(a.path("characterOffset"));
 }
 @Test void limitsAndUnicodePositionsAreExplicit(){
  String raw="{\"summary\":\""+"秘密😀".repeat(5000)+"\",}";
  var a=SpringAiGateway.parseAnswer(raw,"end_turn").path("syntaxCapture");
  assertThat(a.at("/body/truncated").asBoolean()).isTrue();
  assertThat(a.at("/body/complete").asBoolean()).isFalse();
  assertThat(a.at("/body/text").asText()).hasSize(8192);
  assertThat(a.at("/nearby/text").asText()).hasSizeLessThanOrEqualTo(160);
 }
 @Test void fenceNormalizationIsRecordedSeparately(){
  String raw="```json\n{\"type\":\"review\",}\n```";
  var c=SpringAiGateway.parseAnswer(raw,"end_turn").path("syntaxCapture");
  assertThat(c.at("/body/originalCharacters").asInt()).isEqualTo(raw.length());
  assertThat(c.at("/parserText/text").asText()).startsWith("{").endsWith("}");
 }
}
