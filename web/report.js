export function cancelledBeforeExecution(task) {
  return task.status === "CANCELLED" && task.tool_calls === 0 && task.model_calls === 0
    && Array.isArray(task.steps) && task.steps.length === 0
    && Array.isArray(task.evidence) && task.evidence.length === 0
    && !(task.report_json?.facts?.length || task.report_json?.candidates?.length);
}

export function workerMessage(health) {
  if (!health || health.status !== "UP") return "工作器状态未知：健康信息不可用，请刷新确认。";
  if (health.workerEnabled === false) return "工作器已暂停：新任务会保留在队列中，恢复工作器后才能执行。当前不会启动新的模型调用。";
  if (health.workerEnabled === true) return "工作器已启用，可领取排队任务。";
  return "工作器状态未知：服务未返回工作器配置。";
}

export function queueMessage(health) {
  if (health?.status === "UP" && health.workerEnabled === true)
    return "任务已持久化，正在等待空闲执行位置。";
  return "任务已持久化。" + workerMessage(health);
}

export function candidateDetails(candidate) {
  const esc = (v) => String(v ?? "").replace(/[&<>"']/g, (c) => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"})[c]);
  const mechanism = typeof candidate.mechanism === "string" && candidate.mechanism.trim()
    ? candidate.mechanism : "未知（报告未提供机制解释）";
  const fields = [["机制解释 · 推断", mechanism], ["支持说明", candidate.support],
    ["矛盾证据与限制", candidate.contradictions], ["待验证事项", candidate.toVerify]];
  return fields.filter(([, value]) => value != null && value !== "")
    .map(([label, value]) => `<div><strong>${esc(label)}</strong><p>${esc(typeof value === "string" ? value : JSON.stringify(value))}</p></div>`).join("");
}

export function coverageDetails(report) {
  const c = report?.completionCoverage;
  if (!c) return '<p class="muted">历史报告未记录本版覆盖判定；不追溯按新规则重判。</p>';
  const esc = (v) => String(v ?? "").replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;");
  const label = c.coverage === "BOUNDED_NOT_EXHAUSTIVE"
    ? "有界诊断：仍有未扫描或未阅读范围。"
    : c.exhaustiveCoverage === true ? "已完成所查询筛选范围的扫描与送达；不代表覆盖所有其他条件。"
    : "覆盖完整性尚未确认。";
  return `<h3>执行完成与证据覆盖</h3><p>${label} 流程完成不表示原因已证实，也不表示全窗口正常。</p><details><summary>覆盖范围、未读数量与阻塞原因</summary><pre>${esc(JSON.stringify({completionCoverage:c,evidenceDelivery:report.evidenceDelivery ?? null},null,2))}</pre></details>`;
}
