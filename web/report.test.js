import test from "node:test";
import assert from "node:assert/strict";
import { candidateDetails, coverageDetails } from "./report.js";
import { cancelledBeforeExecution, workerMessage, queueMessage } from "./report.js";
import { readFileSync } from "node:fs";

test("actual cancelled tasks are not described as evidence collection", () => {
  const archive = JSON.parse(readFileSync(new URL("./fixtures/cancelled-tasks.json", import.meta.url)));
  for (const task of archive.tasks) assert.equal(cancelledBeforeExecution(task), true);
});
test("executed, evidence-bearing and unknown legacy tasks retain their report", () => {
  const base = {status:"CANCELLED",tool_calls:0,model_calls:0,steps:[],evidence:[]};
  for (const delta of [{tool_calls:1},{model_calls:1},{steps:[{kind:"TOOL"}]},{evidence:[{id:"e"}]},{status:"PARTIAL"},{tool_calls:undefined},{report_json:{candidates:[{cause:"existing"}]}}])
    assert.equal(cancelledBeforeExecution({...base,...delta}),false);
});
test("worker pause, enabled and unknown are distinct", () => {
  assert.match(queueMessage({status:"UP",workerEnabled:false}),/已暂停/);
  assert.doesNotMatch(queueMessage({status:"UP",workerEnabled:false}),/空闲/);
  assert.match(queueMessage({status:"UP",workerEnabled:true}),/空闲/);
  for (const h of [null,{}, {status:"UP"}, {status:"DOWN",workerEnabled:false}]) {
    assert.match(workerMessage(h),/未知/);
    assert.doesNotMatch(queueMessage(h),/空闲/);
  }
});

test("specific mechanism is preserved and labelled as inference", () => {
  const text = "写入调用失败，后续尝试恢复；早期查询原因未确定。";
  const html = candidateDetails({mechanism:text,support:"关联失败与重试记录",contradictions:"后来已成功",toVerify:"核对查询时刻读视图"});
  for (const part of [text,"推断","关联失败与重试记录","后来已成功","核对查询时刻读视图"]) assert.ok(html.includes(part));
});
test("old reports and null mechanism remain explicitly unknown", () => {
  for (const c of [{cause:"legacy"},{mechanism:null},{mechanism:" "}]) assert.ok(candidateDetails(c).includes("未知"));
});
test("report content is escaped without executing markup", () => {
  const html=candidateDetails({mechanism:'<img src=x onerror="alert(1)">'});
  assert.ok(!html.includes("<img"));assert.ok(html.includes("&lt;img"));
});

test("bounded completion exposes unread scope without asserting health", () => {
  const html=coverageDetails({completionCoverage:{complete:true,coverage:"BOUNDED_NOT_EXHAUSTIVE",unreadRegisteredFacts:628,blockingReasons:[]},evidenceDelivery:{unreadRegisteredFactCount:628}});
  assert.match(html,/有界诊断/);assert.match(html,/628/);assert.match(html,/不表示原因已证实/);assert.match(html,/不表示全窗口正常/);
});
test("legacy coverage is unknown and coverage payload is escaped", () => {
  assert.match(coverageDetails({}),/不追溯/);
  assert.ok(!coverageDetails({completionCoverage:{blockingReasons:["<script>"]}}).includes("<script>"));
});
