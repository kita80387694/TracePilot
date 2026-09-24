// Streaming SSE parser: frame boundaries may span network chunks. IDs are deduplicated per task.
export function parseFrame(frame) {
  let id = null,
    event = "message",
    data = [];
  for (const line of frame.replaceAll("\r", "").split("\n")) {
    if (line.startsWith("id:")) id = line.slice(3).trim();
    else if (line.startsWith("event:")) event = line.slice(6).trim();
    else if (line.startsWith("data:")) data.push(line.slice(5).trimStart());
  }
  if (!data.length) return null;
  return { id, event, data: JSON.parse(data.join("\n")) };
}
export async function streamTask(id, onEvent, onConnection, signal) {
  let cursor = sessionStorage.getItem("cursor:" + id) || "0";
  const seen = new Set();
  while (!signal.aborted) {
    try {
      onConnection("连接中");
      const response = await fetch(
        `/diag/api/diagnoses/${id}/events?after=${cursor}`,
        { headers: { "Last-Event-ID": cursor }, signal },
      );
      if ([401,403,404].includes(response.status)) {
        onConnection(response.status === 401 ? "登录已失效，请重新登录" : "任务不存在或无权访问");
        return;
      }
      if (!response.ok) throw Error("SSE " + response.status);
      onConnection("实时连接");
      const reader = response.body.getReader(),
        decoder = new TextDecoder();
      let buffer = "";
      while (!signal.aborted) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true }).replaceAll("\r", "");
        let index;
        while ((index = buffer.indexOf("\n\n")) >= 0) {
          const frame = parseFrame(buffer.slice(0, index));
          buffer = buffer.slice(index + 2);
          if (!frame) continue;
          if (frame.event === "snapshot") {
            onEvent(frame);
            return;
          }
          if (frame.id) {
            if (seen.has(frame.id) || Number(frame.id) <= Number(cursor))
              continue;
            seen.add(frame.id);
            cursor = frame.id;
            sessionStorage.setItem("cursor:" + id, cursor);
          }
          onEvent(frame);
        }
      }
    } catch (e) {
      if (signal.aborted) return;
      onConnection("连接中断 · 自动重连");
    }
    if (!signal.aborted) await new Promise((r) => setTimeout(r, 1200));
  }
}
