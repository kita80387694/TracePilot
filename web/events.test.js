import test from "node:test";
import assert from "node:assert/strict";
import { parseFrame, streamTask } from "./events.js";
test("SSE comments, CRLF and multiline JSON are parsed without fake events", () => {
  assert.equal(parseFrame(": keepalive\r\n"), null);
  assert.deepEqual(
    parseFrame('id: 8\r\nevent: stage\r\ndata: {"kind":\r\ndata: "STATE"}\r\n'),
    { id: "8", event: "stage", data: { kind: "STATE" } },
  );
});
test("network loss reconnects from cursor, ignores replayed IDs and keeps final snapshot", async () => {
  const previous = globalThis.fetch,
    storage = globalThis.sessionStorage;
  const values = new Map([["cursor:task", "1"]]);
  globalThis.sessionStorage = {
    getItem: (k) => values.get(k),
    setItem: (k, v) => values.set(k, v),
  };
  let calls = 0;
  const requests = [],
    events = [];
  const frames = [
    'id: 2\nevent: stage\ndata: {"status":"RUNNING"}\n\n',
    'id: 2\nevent: stage\ndata: {"status":"RUNNING"}\n\nid: 3\nevent: stage\ndata: {"status":"COMPLETED"}\n\nevent: snapshot\ndata: {"status":"COMPLETED"}\n\n',
  ];
  globalThis.fetch = async (url, options) => {
    requests.push(options.headers["Last-Event-ID"]);
    let text = frames[calls++];
    return new Response(
      new ReadableStream({
        start(c) {
          for (let i = 0; i < text.length; i += 7)
            c.enqueue(new TextEncoder().encode(text.slice(i, i + 7)));
          c.close();
        },
      }),
    );
  };
  try {
    await streamTask(
      "task",
      (e) => events.push(e),
      () => {},
      new AbortController().signal,
    );
    assert.deepEqual(requests, ["1", "2"]);
    assert.deepEqual(
      events.map((e) => e.id),
      ["2", "3", null],
    );
    assert.equal(events.at(-1).data.status, "COMPLETED");
    assert.equal(values.get("cursor:task"), "3");
  } finally {
    globalThis.fetch = previous;
    globalThis.sessionStorage = storage;
  }
});
