package com.tracepilot.diagnosis;

import static com.tracepilot.diagnosis.Domain.*;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class ReportExport {
  private static volatile List<String> configuredSecrets = List.of();

  public ReportExport(
      @Value("${diag.api-keys}") String api,
      @Value("${diag.observer-token}") String observer,
      @Value("${diag.model-key}") String model,
      @Value("${diag.web-auth-secret:}") String web,
      @Value("${spring.datasource.password}") String db) {
    var secrets = new ArrayList<String>(List.of(observer, model, web, db));
    for (String entry : api.split(",")) {
      String[] p = entry.split(":", 2);
      if (p.length == 2) secrets.add(p[1]);
    }
    configuredSecrets = secrets.stream().filter(s -> s.length() >= 8).toList();
  }

  static String redact(String text) {
    for (String secret : configuredSecrets) text = text.replace(secret, "[REDACTED]");
    return text.replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~+/-]{12,}", "$1[REDACTED]")
        .replaceAll(
            "(?i)((?:password|api[_-]?key|token|secret)\\s*[:=]\\s*\"?)[^\\s\",}]{8,}",
            "$1[REDACTED]");
  }

  static String markdown(Map<String, Object> task) {
    StringBuilder out = new StringBuilder("# TracePilot 诊断报告\n\n");
    out.append("任务：")
        .append(task.get("id"))
        .append("\n\n状态：")
        .append(task.get("status"))
        .append("\n\nCOMPLETED 表示流程完整，不表示根因已证实。建议不自动执行。\n\n");
    var report = tree(task.get("report_json"));
    out.append("## 异常摘要\n\n").append(report.path("summary").asText("尚未生成报告")).append("\n\n");
    for (var section :
        Map.of(
                "facts",
                "观测事实",
                "candidates",
                "原因候选（推断）",
                "conflicts",
                "冲突证据",
                "gaps",
                "数据缺口",
                "actions",
                "建议动作",
                "execution",
                "执行用量",
                "completionCoverage",
                "流程完成条件与查询覆盖（不代表因果通过）",
                "evidenceDelivery",
                "实际证据送达与未读范围")
            .entrySet())
      out.append("## ")
          .append(section.getValue())
          .append("\n\n```json\n")
          .append(encode(report.path(section.getKey())))
          .append("\n```\n\n");
    out.append("## 本任务证据\n\n");
    for (var evidence : tree(task.get("evidence")))
      out.append("### ")
          .append(evidence.path("id").asText())
          .append("\n\n```json\n")
          .append(encode(evidence))
          .append("\n```\n\n");
    return redact(out.toString()).replace("<", "&lt;").replace(">", "&gt;");
  }
}
