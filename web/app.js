import { streamTask } from "./events.js";
import { coverageDetails, candidateDetails, cancelledBeforeExecution, workerMessage, queueMessage } from "./report.js";
const $ = (s) => document.querySelector(s),
  app = $("#app");
const esc = (v) =>
  String(v ?? "").replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        c
      ],
  );
const pretty = (v) => esc(JSON.stringify(v, null, 2)),
  time = (v) =>
    v
      ? new Date(
          typeof v === "number" && v < 100000000000 ? v * 1000 : v,
        ).toLocaleString("zh-CN", { hour12: false })
      : "—";
const labels = {
  QUEUED: "排队中",
  RUNNING: "诊断中",
  COMPLETED: "完整报告",
  PARTIAL: "部分完成",
  FAILED: "失败",
  CANCELLED: "已取消",
  AVAILABLE: "可用",
  NO_DATA: "无数据",
  UNAVAILABLE: "数据源不可用",
  TIMEOUT: "查询超时",
  FORBIDDEN: "权限拒绝",
  STALE: "历史快照",
  RESERVED: "已预约",
  WAITING: "候补中",
  LEFT: "已退出",
  UP: "运行中",
};
const badge = (s) =>
  `<span class="badge ${["PARTIAL", "STALE", "QUEUED", "WAITING"].includes(s) ? "warn" : ["FAILED", "UNAVAILABLE", "TIMEOUT", "FORBIDDEN", "异常观测"].includes(s) ? "bad" : ["NO_DATA", "CANCELLED", "LEFT"].includes(s) ? "neutral" : ""}">${esc(labels[s] || s)}</span>`;
const empty = (t) => `<div class="empty">${esc(t)}</div>`,
  error = (e) =>
    `<div class="alert error" role="alert">${esc(e.message)} <button data-action="refresh">重新加载</button></div>`;
let user,
  controller,
  timer,
  routeEpoch = 0,
  currentTask,
  taskWorkerHealth = null,
  streamStatus = "连接中";
function toast(t) {
  $("#toast").textContent = t;
  $("#toast").classList.add("show");
  setTimeout(() => $("#toast").classList.remove("show"), 4000);
}
async function api(path, { method = "GET", data, key, signal } = {}) {
  const r = await fetch(path, {
    method,
    signal,
    headers: {
      "Content-Type": "application/json",
      "X-Requested-With": "TracePilot",
      ...(key ? { "Idempotency-Key": key } : {}),
    },
    body: data === undefined ? undefined : JSON.stringify(data),
  });
  const v = await r.json();
  if (!r.ok) {
    const code = v.code || v.error || r.status;
    throw Object.assign(
      new Error(
        `${v.message || { 429: "队列已满，请稍后重试", 401: "登录已失效，请重新登录", 403: "没有访问权限", 404: "任务或证据不存在，或无权访问", 503: "服务暂不可用，预约和诊断可独立使用" }[r.status] || "请求未成功"} (${code})`,
      ),
      { status: r.status },
    );
  }
  return v;
}
function header(title, sub, action = "") {
  return `<div class="page-heading"><div><div class="eyebrow">TRACEPILOT / DEMO WORKSPACE</div><h1>${title}</h1><p class="muted">${sub}</p></div>${action}</div>`;
}
function shell(page) {
  app.innerHTML = `<div class="layout"><aside class="sidebar"><a class="brand" href="#overview">◈ TracePilot</a><p class="tagline">预约 · 观测 · 诊断</p><div class="nav-label">工作空间</div><nav>${[["overview", "◉", "服务概览"], ["new", "＋", "发起诊断"], ["history", "≡", "历史任务"], ["booking", "▦", "资源预约"], ...(user.role === "ADMIN" ? [["drills", "⚑", "故障演练"]] : [])].map(([id, icon, text]) => `<a href="#${id}" class="${page === id ? "active" : ""}"><span>${icon}</span> ${text}</a>`).join("")}</nav><div class="foot"><span class="badge">DEMO</span><p>${esc(user.username)} · ${user.role === "ADMIN" ? "管理员" : "用户"}</p><button class="ghost" data-action="logout">退出登录</button><p class="muted">Agent 仅提供诊断建议<br>变更由人执行</p></div></aside><main class="main"><div class="topbar"><span>服务诊断工作台 <button class="mobile-logout" data-action="logout">退出登录</button></span><span class="mono">业务 ${esc(user.endpoints?.business || "未知")} &nbsp; 诊断 ${esc(user.endpoints?.diagnosis || "未知")}</span></div><div id="content">${empty("正在读取服务数据…")}</div></main></div>`;
}
function login(message = "") {
  app.innerHTML = `<div class="login"><section class="login-art"><a class="brand">◈ TracePilot</a><div><div class="eyebrow">从业务症状，到可核查的证据</div><h1>让每一次诊断<br>都有据可循。</h1><p>真实业务、受控工具、可追溯报告。<br>一个清晰的服务诊断工作空间。</p><div class="rule"></div><p>01 &nbsp; 观察服务 &nbsp; 02 &nbsp; 关联证据 &nbsp; 03 &nbsp; 人工决策</p></div><small>DEMO WORKSPACE · READ-ONLY AGENT</small></section><section class="login-form"><div class="eyebrow">欢迎回来</div><h1>登录工作台</h1><p class="muted">使用预约业务的现有账号登录。</p>${message ? error(new Error(message)) : ""}<form id="login"><label>用户名<input name="username" required autocomplete="username" placeholder="admin 或 user1"></label><label>密码<input name="password" type="password" required autocomplete="current-password"></label><button class="primary" type="submit">登录</button></form><p class="muted">本地演示账号：admin / user1<br>演示密码：Demo-pass-123</p><div class="login-status">诊断服务与预约服务独立运行</div></section></div>`;
}
async function render() {
  controller?.abort();
  clearInterval(timer);
  controller = new AbortController();
  const epoch = ++routeEpoch;
  const [page = "overview", id] = location.hash.slice(1).split("/");
  if (!user) {
    login();
    return;
  }
  shell(page);
  try {
    if (page === "overview") await overview(epoch);
    else if (page === "new") newTask();
    else if (page === "history") await history(epoch);
    else if (page === "task") await detail(id, epoch);
    else if (page === "booking") await booking(epoch);
    else if (page === "drills") await drills(epoch);
    else location.hash = "overview";
  } catch (e) {
    if (epoch === routeEpoch)
      $("#content").innerHTML =
        header(
          [401,403,404].includes(e.status) ? "无法访问此页面" : "数据暂不可用",
          [401,403,404].includes(e.status) ? "请确认登录账号与访问权限。" : "当前页面的数据源未能响应。其他功能可继续独立使用。",
        ) + error(e);
  }
}
async function overview(epoch) {
  const results = await Promise.allSettled([
    api("/diag/api/overview"),
    api("/diag/health"),
  ]);
  if (epoch !== routeEpoch) return;
  const result = results[0].status === "fulfilled" ? results[0].value : null,
    s = result?.data || {},
    m = Object.fromEntries(
      Object.entries(s).flatMap(([group, value]) =>
        value && typeof value === "object"
          ? Object.entries(value).map(([key, v]) => [group + "." + key, v])
          : [],
      ),
    );
  const abnormal =
    s.pool?.pending > 0 ||
    s.http?.errorRate > 0 ||
    (s.events?.pending > 0 && s.events?.oldestSeconds >= 30);
  const value = (key, suffix = "") => {
    const source = s[key.split(".")[0]]?.status;
    if (["UNAVAILABLE", "TIMEOUT", "FORBIDDEN"].includes(source)) return labels[source];
    return m[key] === null || m[key] === undefined ? "无数据" : esc(m[key]) + suffix;
  };
  $("#content").innerHTML =
    header(
      "服务概览",
      "观察当前采样；没有请求时，错误率与耗时显示为无数据。",
      '<a class="button primary" href="#new">＋ 发起诊断</a>',
    ) +
    (result
      ? `<section class="panel"><div class="actions"><h2>tracepilot-business</h2>${badge(result.status)} ${result.status === "AVAILABLE" ? badge(abnormal ? "异常观测" : "当前未见阈值异常") : ""}<span class="muted">demo · ${time(s.time)}</span></div>${result.status === "STALE" ? '<div class="alert">实时源不可用，当前展示离线历史快照。</div>' : ""}<p class="muted">提示阈值：等待连接 > 0、区间 5xx 错误率 > 0，或未处理事件年龄 ≥ 30 秒。无请求不等于零错误。</p><p class="version">部署版本 ${esc(s.deploymentVersion || "无数据")}</p></section><div class="grid">${[
          ["请求总量", value("http.count")],
          [
            "错误率",
            m["http.errorRate"] == null
              ? "无数据"
              : (m["http.errorRate"] * 100).toFixed(2) + "%",
          ],
          ["平均耗时", value("http.averageMs", " ms")],
          ["待处理事件", value("events.pending")],
        ]
          .map(
            ([label, v]) =>
              `<div class="panel metric"><div class="label">${label}</div><div class="value">${v}</div></div>`,
          )
          .join(
            "",
          )}</div><div class="split"><section class="panel"><h2>数据库与事件</h2><dl><dt>连接使用 / 上限</dt><dd>${value("pool.active")} / ${value("pool.max")}</dd><dt>等待连接</dt><dd>${value("pool.pending")}</dd><dt>最老事件年龄</dt><dd>${value("events.oldestSeconds", " s")}</dd><dt>事件消费成功 / 失败</dt><dd>${value("events.success")} / ${value("events.failure")}</dd></dl></section><section class="panel"><h2>诊断服务</h2>${results[1].status === "fulfilled" ? badge(results[1].value.status === "UP" ? "服务可用" : results[1].value.status) : badge("UNAVAILABLE")}<p>${esc(workerMessage(results[1].status === "fulfilled" ? results[1].value : null))}</p><p>模型配置：${results[1].status === "fulfilled" ? (results[1].value.modelConfigured ? "已配置（不代表调用可用）" : "缺失，任务将保留证据并部分结束") : "无法读取"}</p><p class="mono muted">运行提示词版本：${esc(results[1].status === "fulfilled" ? results[1].value.promptVersion || "未知" : "未知")}</p><p class="muted">工具只读。诊断建议需要人工判断与执行。</p><details><summary>查看原始观测快照</summary><pre>${pretty(s)}</pre></details></section></div>`
      : error(results[0].reason));
}
function localInput(d) {
  return new Date(d.getTime() - d.getTimezoneOffset() * 60000)
    .toISOString()
    .slice(0, 19);
}
function newTask() {
  const end = new Date();
  $("#content").innerHTML =
    header("发起诊断", "描述观察到的症状。Agent 将自行选择只读工具收集证据。") +
    `<div class="split"><section class="panel"><form id="create"><div class="form-row"><label>服务<input name="service" value="tracepilot-business" readonly></label><label>环境<input name="environment" value="demo" readonly></label></div><div class="form-row"><label>开始时间<input name="start" type="datetime-local" step="1" value="${localInput(new Date(end - 600000))}" required></label><label>结束时间<input name="end" type="datetime-local" step="1" value="${localInput(end)}" required></label></div><label>异常症状（可选）<textarea name="symptom" maxlength="2000" rows="5" placeholder="例如：预约成功后，通知迟迟没有出现。请检查这段时间的服务情况。"></textarea></label><div class="form-row"><label>traceId（可选）<input name="traceId" pattern="[a-f0-9]{32}" placeholder="32 位十六进制"></label><label>接口路径（可选）<input name="interfacePath" placeholder="/api/reservations"></label></div><div id="form-error"></div><button class="primary" type="submit">创建诊断任务 →</button></form></section><aside class="panel"><h2>有边界的诊断</h2><p>概况检查 → 原因候选 → 补充证据 → 冲突检查 → 报告</p><ul><li>时间窗口最多 60 分钟</li><li>最多 12 次工具、8 次模型调用</li><li>执行最多 180 秒；排队时间另计</li><li>全局并发上限 2，最多 20 个排队任务</li></ul><p class="muted">证据不足或模型不可用时，会生成部分报告。不会执行 SQL、修改代码或重启服务。</p></aside></div>`;
}
async function history(epoch) {
  const params = new URLSearchParams(
    sessionStorage.getItem("historyFilter") || "",
  );
  const r = await api("/diag/api/diagnoses?" + params);
  if (epoch !== routeEpoch) return;
  $("#content").innerHTML =
    header(
      "历史任务",
      "仅展示当前用户有权访问的任务。重试会保留原任务并创建关联任务。",
      '<a class="button primary" href="#new">＋ 发起诊断</a>',
    ) +
    `<section class="panel"><form id="history-filter" class="filters"><label>状态<select name="status"><option value="">全部状态</option>${["QUEUED", "RUNNING", "COMPLETED", "PARTIAL", "FAILED", "CANCELLED"].map((s) => `<option value="${s}" ${params.get("status") === s ? "selected" : ""}>${labels[s]}</option>`).join("")}</select></label><label>服务<select name="service"><option value="">全部服务</option><option value="tracepilot-business" ${params.get("service")==="tracepilot-business"?"selected":""}>tracepilot-business</option></select></label><label>开始日期<input name="from" type="date" value="${esc(params.get("from") ? localInput(new Date(params.get("from"))).slice(0,10) : "")}"></label><label>结束日期<input name="to" type="date" value="${esc(params.get("to") ? localInput(new Date(params.get("to"))).slice(0,10) : "")}"></label><button>筛选</button></form>${
      r.data.length
        ? `<div class="scroll-table"><table><thead><tr><th>任务 / 症状</th><th>状态</th><th>创建时间</th><th>调用 工具 / 模型</th><th></th></tr></thead><tbody>${r.data
            .map((t) => {
              const q =
                typeof t.request_json === "string"
                  ? JSON.parse(t.request_json)
                  : t.request_json;
              return `<tr><td><a href="#task/${t.id}">${esc(q.symptom || "服务诊断")}</a><div class="mono muted">${t.id.slice(0, 8)}${t.parent_id ? " · 重试任务" : ""}</div></td><td>${badge(t.status)}</td><td>${time(t.created_at)}</td><td>${t.tool_calls} / ${t.model_calls}</td><td><a href="#task/${t.id}">查看 →</a></td></tr>`;
            })
            .join("")}</tbody></table></div>`
        : empty("没有符合筛选条件的任务")
    }<div class="pager"><span>共 ${r.total} 个任务 · 第 ${r.page + 1} 页</span><button data-page="${r.page - 1}" ${r.page === 0 ? "disabled" : ""}>上一页</button><button data-page="${r.page + 1}" ${(r.page + 1) * r.size >= r.total ? "disabled" : ""}>下一页</button></div></section>`;
}
const ref = (id) =>
  `<button class="ref mono" data-evidence="${esc(id)}">↗ ${esc(id.slice(0, 8))}</button>`;
function item(v) {
  return typeof v === "string" ? esc(v) : pretty(v);
}
function drawTask(t) {
  currentTask = t;
  const q = t.request_json,
    state = t.state_json,
    report = t.report_json,
    unexecutedCancellation = cancelledBeforeExecution(t),
    active = ["QUEUED", "RUNNING"].includes(t.status);
  const stages = [
    ["OVERVIEW", "概况检查"],
    ["CANDIDATES", "原因候选"],
    ["INVESTIGATE", "补充证据"],
    ["REVIEW", "冲突检查"],
    ["REPORT", "生成报告"],
  ];
  $("#content").innerHTML =
    header(
      "诊断详情",
      `${esc(q.service)} · ${time(q.start)} — ${time(q.end)}`,
      `<div class="actions"><a href="#history">返回历史</a>${active ? '<button class="danger" data-action="cancel">取消任务</button>' : '<button data-action="retry">关联重试</button>'}<a class="button" href="/diag/api/diagnoses/${t.id}/export" download>导出 Markdown</a></div>`,
    ) +
    `<section class="panel"><div class="actions">${badge(t.status)}<span class="mono">${t.id}</span><span class="connection" id="connection">${active ? esc(streamStatus) : "已读取最终状态"}</span></div><p>${esc(q.symptom || "未填写症状，按时间窗口检查服务")}</p>${t.parent_id ? `<p>重试来源：<a href="#task/${t.parent_id}">${t.parent_id}</a></p>` : ""}<div class="steps">${stages.map(([key, title], i) => `<div class="step ${state.phase === key && active ? "active" : ""}"><span>${i + 1}</span> ${title}</div>`).join("")}</div><div class="snapshot-line">工具 ${t.tool_calls} / ${t.max_tools} &nbsp; 模型 ${t.model_calls} / ${t.max_models} &nbsp; 执行上限 ${t.timeout_seconds} 秒</div></section>${t.status === "PARTIAL" ? '<div class="alert">任务部分完成。已收集的证据保留；请检查数据缺口，补充数据或恢复依赖后创建关联重试。</div>' : ""}${t.status === "CANCELLED" ? '<div class="alert">任务已取消，不再启动后续调用。已发出的请求会在超时边界内结束。</div>' : ""}<div class="split"><div><section class="panel"><h2>诊断报告</h2>${unexecutedCancellation ? `<p>任务在执行前已取消，未执行诊断，也未采集证据。</p><p>未生成原因分析或修复建议；这不是证据不足诊断结果。</p><p>如需诊断，可创建关联重试；是否执行取决于工作器状态。</p>` : report ? `<p>${esc(report.summary)}</p>${coverageDetails(report)}<h3>观测事实</h3>${report.facts?.length ? report.facts.map((f) => `<div class="report-item"><p>${esc(f.text)}</p>${ref(f.evidenceId)}<span class="mono muted">${esc(f.pointer)} = ${item(f.value)}</span></div>`).join("") : empty("没有可发布的观测事实")}<h3>原因候选 · 推断</h3>${report.candidates?.length ? report.candidates.map((c) => `<div class="report-item inference"><p>${esc(c.cause)}</p>${candidateDetails(c)}${badge(c.confidence)} ${(c.evidenceIds || []).map(ref).join("")}</div>`).join("") : empty("证据不足，暂不给出根因")}<h3>冲突证据</h3>${report.conflicts?.length ? report.conflicts.map((c) => `<div class="report-item">${esc(c.text)} ${(c.evidenceIds || []).map(ref).join("")}</div>`).join("") : empty("未记录冲突；不代表已排除所有其他原因")}<h3>数据缺口</h3><ul>${(report.gaps || []).map((g) => `<li>${item(g)}</li>`).join("") || "<li>报告未记录额外数据缺口</li>"}</ul><h3>建议动作 · 由人执行</h3><ul>${(report.actions || []).map((a) => `<li>${item(a)}</li>`).join("") || "<li>暂无建议动作</li>"}</ul><details><summary>执行概况与实际用量</summary><pre>${pretty(report.execution || { modelCalls: t.model_calls, toolCalls: t.tool_calls, usage: "请查看下方模型执行摘要" })}</pre></details>` : empty(t.status === "QUEUED" ? queueMessage(taskWorkerHealth) : t.status === "FAILED" ? "任务执行失败，暂无报告；请检查执行摘要后关联重试" : "正在收集证据，报告尚未发布")}<p class="muted">模型结论是推断，不是评估答案。此页面不展示模型私有思维链。</p></section><section class="panel"><h2>工具与模型执行摘要</h2>${t.steps.length ? `<div class="timeline">${t.steps.map((s) => `<details><summary>${time(s.time)} · ${esc(s.kind)} · ${esc(s.status)}</summary><pre>${pretty(s.kind === "REPORT" ? { validation: s.detail.errors } : s.detail)}</pre></details>`).join("")}</div>` : empty("尚未开始执行")}</section>${!active ? `<section class="panel"><h2>报告反馈</h2><p class="muted">用户反馈仅用于产品改进，不作为真实根因标签。</p><form id="feedback"><label>评价<select name="rating"><option value="HELPFUL">有帮助</option><option value="NOT_HELPFUL">没有帮助</option><option value="INSUFFICIENT">证据不足</option></select></label><label>说明<textarea name="note" maxlength="1000" rows="2"></textarea></label><button>保存反馈</button>${t.feedback_json ? '<span class="muted">已有反馈，可更新</span>' : ""}</form></section>` : ""}</div><aside><section class="panel sticky"><h2>证据浏览 <span class="muted">${t.evidence.length}</span></h2>${t.evidence.length ? t.evidence.map((e) => `<div class="report-item">${ref(e.id)} ${badge(e.status)}<div>${esc(e.source)} · ${time(e.start)}</div></div>`).join("") : empty("尚无证据")}<div id="evidence-panel">${empty("点击引用查看原始片段、来源和版本")}</div></section></aside></div>`;
}
async function detail(id, epoch) {
  taskWorkerHealth = null;
  const [t, health] = await Promise.all([api("/diag/api/diagnoses/" + id), api("/diag/health").catch(() => null)]);
  if (epoch !== routeEpoch) return;
  taskWorkerHealth = health;
  drawTask(t);
  if (t.status === "QUEUED") {
    timer = setInterval(async () => {
      const health = await api("/diag/health").catch(() => null);
      if (epoch !== routeEpoch || currentTask?.id !== id || currentTask.status !== "QUEUED") return;
      taskWorkerHealth = health;
      drawTask(currentTask);
    }, 10000);
  }
  if (["QUEUED", "RUNNING"].includes(t.status)) {
    let refreshing = false,
      terminal = false;
    streamTask(
      id,
      async (event) => {
        if (epoch !== routeEpoch) return;
        if (event.event === "snapshot") {
          terminal = true;
          drawTask(event.data);
          return;
        }
        if (refreshing) return;
        refreshing = true;
        try {
          const next = await api("/diag/api/diagnoses/" + id);
          if (epoch === routeEpoch && !terminal) drawTask(next);
        } catch (e) {
          toast(e.message);
        } finally {
          refreshing = false;
        }
      },
      (message) => {
        if (epoch === routeEpoch) {
          streamStatus = message;
          if ($("#connection")) $("#connection").textContent = message;
        }
      },
      controller.signal,
    );
  }
}
let slotPage = 0;
async function booking(epoch) {
  const [slots, mine, notifications, resources] = await Promise.all([
    api("/business/api/slots?page=" + slotPage + "&size=12"),
    api("/business/api/me/participations"),
    api("/business/api/me/notifications"),
    api("/business/api/resources?size=100"),
  ]);
  if (epoch !== routeEpoch) return;
  const rows = Array.isArray(mine) ? mine : mine.data || [],
    notes = Array.isArray(notifications)
      ? notifications
      : notifications.data || [],
    names = new Map(resources.data.map((r) => [r.id, r.name]));
  $("#content").innerHTML =
    header(
      "资源预约",
      "预约与 FIFO 候补由业务服务处理。诊断不可用时，业务继续独立运行。",
      '<button data-action="refresh">刷新名额</button>',
    ) +
    `<section class="panel"><h2>可预约时段</h2>${slots.data.length ? `<div class="scroll-table"><table><thead><tr><th>资源</th><th>时段</th><th>剩余 / 容量</th><th>操作</th></tr></thead><tbody>${slots.data.map((s) => `<tr><td>${esc(names.get(s.resource_id) || "资源 " + s.resource_id)}<div class="mono muted">时段 #${s.id}</div></td><td>${time(s.start_at)}<br><span class="muted">${time(s.end_at)}</span></td><td>${s.remaining} / ${s.capacity}</td><td><button data-join-kind="${s.remaining > 0 ? "reservations" : "waitlists"}" data-book="${s.id}" ${!s.open || !s.enabled || new Date(s.start_at) < new Date() ? "disabled" : ""}>${s.remaining > 0 ? "预约" : "加入候补"}</button></td></tr>`).join("")}</tbody></table></div>` : empty("暂无资源时段")}<div class="pager"><button data-slot-page="${slotPage - 1}" ${slotPage === 0 ? "disabled" : ""}>上一页</button><span>第 ${slotPage + 1} 页 · 当前 ${slots.data.length} 个时段</span><button data-slot-page="${slotPage + 1}" ${slots.data.length < 12 ? "disabled" : ""}>下一页</button></div></section><div class="split"><section class="panel"><h2>我的预约与候补</h2>${rows.length ? rows.map((r) => `<div class="report-item">时段 #${r.slot_id} ${badge(r.status)} ${["RESERVED", "WAITING"].includes(r.status) ? `<button data-participation="${r.id}" data-operation="${r.status === "RESERVED" ? "reservations" : "waitlists"}">${r.status === "RESERVED" ? "取消预约" : "退出候补"}</button>` : ""}</div>`).join("") : empty("尚无预约记录")}</section><section class="panel"><h2>站内通知</h2>${notes.length ? notes.map((n) => `<div class="report-item"><p>${esc(n.content || n.message || n.type)}</p><span class="muted">${time(n.created_at)}</span><details><summary>通知内容</summary><pre>${pretty(n)}</pre></details></div>`).join("") : empty("暂无通知")}</section></div>${user.role === "ADMIN" ? `<section class="panel"><h2>管理员 · 创建演示资源</h2><form id="resource"><div class="form-row"><label>资源名称<input name="name" required maxlength="100" placeholder="例如：会议室 A"></label><label>说明<input name="description" maxlength="500"></label></div><button>创建资源</button></form><h3>添加时段</h3><form id="slot"><div class="form-row"><label>资源<select name="resourceId">${resources.data.map((r) => `<option value="${r.id}">${esc(r.name)} (#${r.id})</option>`).join("")}</select></label><label>容量<input type="number" name="capacity" min="1" max="1000" value="5" required></label></div><div class="form-row"><label>开始<input name="startAt" type="datetime-local" value="${localInput(new Date(Date.now() + 86400000))}" required></label><label>结束<input name="endAt" type="datetime-local" value="${localInput(new Date(Date.now() + 90000000))}" required></label></div><button>创建时段</button></form></section>` : ""}`;
}
let drillRun;
async function drills(epoch) {
  if (user.role !== "ADMIN") throw Object.assign(new Error("仅 demo 环境管理员可以访问"),{status:403});
  const config = await api("/business/api/drills");
  const current = await api("/business/api/drills/current");
  const id = localStorage.getItem("drill:" + user.userId);
  let run =
    current.status === "RUNNING"
      ? current
      : id
        ? await api("/business/api/drills/" + id).catch(() => null)
        : null;
  if (epoch !== routeEpoch) return;
  drillRun = run;
  if (run?.status === "RUNNING")
    localStorage.setItem("drill:" + user.userId, run.id);
  $("#content").innerHTML =
    header(
      "故障演练",
      "此区域供人操作。演练控制与诊断工具隔离，Agent 不具有注入或恢复权限。",
    ) +
    `<div class="alert">仅 demo 环境管理员可操作。故障最多持续 ${config.maxDurationSeconds} 秒；请确认影响后启动，并在诊断后人工恢复。</div><div class="split"><section class="panel"><h2>受控故障注入</h2><form id="drill"><label>演练类型<select name="scenario">${config.scenarios.map((s) => `<option value="${s.id}">${esc(s.id)} · ${esc(s.impact)}</option>`).join("")}</select></label><div class="form-row"><label>持续时间（秒）<input name="durationSeconds" type="number" min="1" max="300" value="240" required></label><label>并发负载<input name="concurrency" type="number" min="1" max="100" value="2" required></label></div><label>每阶段请求数<input name="requestsPerPhase" type="number" min="1" max="10000" value="5" required></label><label class="checkbox"><input name="acknowledged" type="checkbox" required>我了解这会真实影响 demo 数据库连接、查询或通知消费。</label><button class="danger">启动演练</button></form><h3>影响说明</h3><p>F01：占用连接，业务请求可能超时。<br>F02：真实无索引查询消耗数据库资源。<br>F03：通知延迟，预约主流程仍可用。</p><p class="muted">负载字段记录演练条件，不自动发送预约负载。可在资源预约页发起实际请求。</p></section><section class="panel"><h2>恢复控制</h2>${run ? `${badge(run.status === "RUNNING" ? "演练运行中" : run.status)}<p class="mono">${esc(run.id)}</p><div class="countdown" id="countdown"></div><p>开始：${time(run.startedAt)}<br>最晚恢复：${time(run.deadline)}</p><button class="primary" data-action="recover" ${run.status !== "RUNNING" ? "disabled" : ""}>立即人工恢复</button><details><summary>查看实际演练记录</summary><pre>${pretty(run)}</pre></details>` : empty("此浏览器尚未启动演练")}<p class="muted">恢复后请刷新服务概览，并验证预约与通知。刷新页面会恢复本浏览器记录的演练状态。</p></section></div>`;
  if (run) {
    const tick = () => {
      if ($("#countdown"))
        $("#countdown").textContent =
          run.status === "RUNNING"
            ? Math.max(
                0,
                Math.ceil((new Date(run.deadline) - Date.now()) / 1000),
              ) + " 秒"
            : "已结束";
    };
    tick();
    timer = setInterval(async () => {
      tick();
      if (run.status === "RUNNING" && new Date(run.deadline) <= new Date()) {
        clearInterval(timer);
        await render();
      }
    }, 1000);
  }
}
document.addEventListener("submit", async (e) => {
  e.preventDefault();
  const form = e.target,
    submit = form.querySelector('button[type="submit"],button'),
    data = Object.fromEntries(new FormData(form));
  submit.disabled = true;
  try {
    if (form.id === "login") {
      user = await api("/session/login", { method: "POST", data });
      form.reset();
      await render();
    } else if (form.id === "create") {
      data.start = new Date(data.start).toISOString();
      data.end = new Date(data.end).toISOString();
      data.traceId = data.traceId || null;
      data.interfacePath = data.interfacePath || null;
      const t = await api("/diag/api/diagnoses", { method: "POST", data });
      location.hash = "task/" + t.id;
    } else if (form.id === "history-filter") {
      const p = new URLSearchParams();
      for (const [k, v] of Object.entries(data))
        if (v)
          p.set(
            k,
            ["from", "to"].includes(k)
              ? new Date(
                  v + (k === "from" ? "T00:00:00" : "T23:59:59"),
                ).toISOString()
              : v,
          );
      sessionStorage.setItem("historyFilter", p);
      await render();
    } else if (form.id === "feedback") {
      await api("/diag/api/diagnoses/" + currentTask.id + "/feedback", {
        method: "POST",
        data,
      });
      toast("反馈已保存，不作为评估答案");
    } else if (form.id === "resource" || form.id === "slot") {
      if (form.id === "slot") {
        data.resourceId = Number(data.resourceId);
        data.capacity = Number(data.capacity);
        data.startAt = new Date(data.startAt).toISOString();
        data.endAt = new Date(data.endAt).toISOString();
      }
      if (form.dataset.signature !== JSON.stringify(data)) {
        form.dataset.signature = JSON.stringify(data);
        form.dataset.key = crypto.randomUUID();
      }
      form.dataset.key ||= crypto.randomUUID();
      await api(
        "/business/api/admin/" + (form.id === "slot" ? "slots" : "resources"),
        { method: "POST", data, key: form.dataset.key },
      );
      delete form.dataset.key;
      toast("创建成功");
      await render();
    } else if (form.id === "drill") {
      for (const k of ["durationSeconds", "concurrency", "requestsPerPhase"])
        data[k] = Number(data[k]);
      data.acknowledged = true;
      const r = await api("/business/api/drills", { method: "POST", data });
      localStorage.setItem("drill:" + user.userId, r.id);
      await render();
    }
  } catch (err) {
    if ($("#form-error")) $("#form-error").innerHTML = error(err);
    else toast(err.message);
  } finally {
    submit.disabled = false;
  }
});
document.addEventListener("click", async (e) => {
  const b = e.target.closest("button");
  if (!b || b.disabled) return;
  try {
    if (b.dataset.action === "logout") {
      await api("/session/logout", { method: "POST", data: {} });
      user = null;
      routeEpoch++;
      controller?.abort();
      login();
    } else if (b.dataset.action === "refresh") await render();
    else if (b.dataset.action === "cancel") {
      b.disabled = true;
      await api("/diag/api/diagnoses/" + currentTask.id + "/cancel", {
        method: "POST",
        data: {},
      });
      await render();
    } else if (b.dataset.action === "retry") {
      b.disabled = true;
      const r = await api("/diag/api/diagnoses/" + currentTask.id + "/retry", {
        method: "POST",
        data: {},
      });
      location.hash = "task/" + r.id;
    } else if (b.dataset.evidence) {
      const v = await api("/diag/api/evidence/" + b.dataset.evidence);
      $("#evidence-panel").innerHTML =
        `<h3>${esc(v.source)} · 原始证据</h3><p class="mono">${esc(v.id)}</p><p>${time(v.start)} — ${time(v.end)}</p><h4>来源与版本定位</h4><pre>${pretty(v.locator)}</pre>${v.data?.hasMore ? `<div class="alert">此页为部分证据，仍有 ${esc(v.data.remainingBytes)} 字节未扫描。按相同时间窗和筛选条件使用 nextCursor 继续查询；空页不代表没有异常。游标失效时需从 0 重新查询。</div><details><summary>续查游标</summary><pre>${esc(v.data.nextCursor)}</pre></details>` : ""}${v.data?.gap && v.data.gap !== "NONE" ? `<div class="alert">数据缺口：${esc(v.data.gap)}</div>` : ""}<h4>原始片段（仅作为数据）</h4><pre>${pretty(v.data)}</pre>`;
    } else if (b.dataset.page !== undefined) {
      const p = new URLSearchParams(
        sessionStorage.getItem("historyFilter") || "",
      );
      p.set("page", b.dataset.page);
      sessionStorage.setItem("historyFilter", p);
      await render();
    } else if (b.dataset.slotPage !== undefined) {
      slotPage = Number(b.dataset.slotPage);
      await render();
    } else if (b.dataset.book) {
      b.disabled = true;
      b.dataset.key ||= crypto.randomUUID();
      await api("/business/api/" + b.dataset.joinKind, {
        method: "POST",
        data: { slotId: Number(b.dataset.book) },
        key: b.dataset.key,
      });
      toast("操作成功");
      await render();
    } else if (b.dataset.participation) {
      b.disabled = true;
      b.dataset.key ||= crypto.randomUUID();
      await api(
        "/business/api/" +
          b.dataset.operation +
          "/" +
          b.dataset.participation +
          "/" +
          (b.dataset.operation === "reservations" ? "cancel" : "leave"),
        { method: "POST", data: {}, key: b.dataset.key },
      );
      toast("已处理");
      await render();
    } else if (b.dataset.action === "recover") {
      b.disabled = true;
      await api("/business/api/drills/" + drillRun.id + "/stop", {
        method: "POST",
        data: {},
      });
      toast("已执行人工恢复，请复测业务");
      await render();
    }
  } catch (err) {
    toast(err.message);
  } finally {
    b.disabled = false;
  }
});
window.addEventListener("hashchange", render);
try {
  user = await api("/session");
} catch {}
await render();
