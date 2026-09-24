import http from "node:http";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { randomBytes, createHmac } from "node:crypto";
import { Readable } from "node:stream";
const port = Number(process.env.WEB_PORT || 3000),
  root = fileURLToPath(new URL("./", import.meta.url));
const secret = process.env.WEB_AUTH_SECRET;
if (!secret || secret.length < 32)
  throw Error("WEB_AUTH_SECRET must be configured");
const sessions = new Map();
const business = process.env.WEB_BUSINESS_URL || "http://127.0.0.1:8082",
  diagnosis = process.env.WEB_DIAGNOSIS_URL || "http://127.0.0.1:8093";
for (const endpoint of [business, diagnosis]) {
  const parsed = new URL(endpoint);
  if (parsed.protocol !== "http:" || parsed.hostname !== "127.0.0.1" ||
      parsed.username || parsed.password || parsed.search || parsed.hash || parsed.pathname !== "/")
    throw Error("Only configured loopback service origins are allowed");
}
function json(res, status, value) {
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
  });
  res.end(JSON.stringify(value));
}
async function body(req) {
  let text = "";
  for await (const b of req) {
    text += b;
    if (text.length > 16384) throw Error("BODY_LIMIT");
  }
  return text;
}
const businessRoutes =
  /^\/api\/(resources|slots|me\/(participations|notifications)|reservations(?:\/\d+(?:\/cancel)?)?|waitlists(?:\/\d+(?:\/leave)?)?|admin\/(resources(?:\/\d+)?|slots(?:\/\d+)?|stats)|drills(?:\/current|\/[a-f0-9-]{36}(?:\/stop)?|\/dataset(?:\/plans)?)?|reports\/usage)$/;
const diagRoutes =
  /^\/(health|api\/(overview|diagnoses(?:\/[a-f0-9-]{36}(?:\/(events|cancel|retry|feedback|export))?)?|evidence\/[a-f0-9-]{36}))$/;
http
  .createServer(async (req, res) => {
    res.setHeader("X-Content-Type-Options", "nosniff");
    res.setHeader("Referrer-Policy", "no-referrer");
    res.setHeader(
      "Content-Security-Policy",
      "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'",
    );
    try {
      if (
        ![`127.0.0.1:${port}`, `localhost:${port}`].includes(req.headers.host)
      ) {
        json(res, 403, { code: "HOST_DENIED" });
        return;
      }
      const url = new URL(req.url, `http://127.0.0.1:${port}`);
      if (
        !["GET", "HEAD"].includes(req.method) &&
        (req.headers["x-requested-with"] !== "TracePilot" ||
          (req.headers.origin &&
            !["http://127.0.0.1:" + port, "http://localhost:" + port].includes(
              req.headers.origin,
            )))
      ) {
        json(res, 403, { code: "CSRF_DENIED" });
        return;
      }
      const sid = (req.headers.cookie || "").match(
        /(?:^|;\s*)tp_session=([a-f0-9]{64})/,
      )?.[1];
      let session = sessions.get(sid);
      if (session && session.expires < Date.now()) {
        sessions.delete(sid);
        session = null;
      }
      if (url.pathname === "/session/login" && req.method === "POST") {
        const input = JSON.parse(await body(req));
        const response = await fetch(business + "/api/auth/login", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            username: input.username,
            password: input.password,
          }),
          signal: AbortSignal.timeout(10000),
        });
        const value = await response.json();
        if (!response.ok) {
          json(res, response.status, {
            code: value.code || "LOGIN_FAILED",
            message: "登录失败，请检查账号或业务服务",
          });
          return;
        }
        const id = randomBytes(32).toString("hex");
        if (sid) sessions.delete(sid);
        session = {
          username: input.username,
          userId: value.userId,
          role: value.role,
          token: value.token,
          expires: Date.now() + 86400000,
        };
        sessions.set(id, session);
        res.setHeader(
          "Set-Cookie",
          `tp_session=${id}; HttpOnly; SameSite=Strict; Path=/; Max-Age=86400`,
        );
        json(res, 200, {
          username: session.username,
          userId: session.userId,
          role: session.role,
          endpoints: { business, diagnosis },
        });
        return;
      }
      if (url.pathname === "/session/logout" && req.method === "POST") {
        sessions.delete(sid);
        res.setHeader(
          "Set-Cookie",
          "tp_session=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0",
        );
        json(res, 200, { status: "SIGNED_OUT" });
        return;
      }
      if (url.pathname === "/session") {
        json(
          res,
          session ? 200 : 401,
          session
            ? {
                username: session.username,
                userId: session.userId,
                role: session.role,
          endpoints: { business, diagnosis },
              }
            : { code: "LOGIN_REQUIRED" },
        );
        return;
      }
      const diag = url.pathname.startsWith("/diag/"),
        biz = url.pathname.startsWith("/business/");
      if (diag || biz) {
        if (!session) {
          json(res, 401, { code: "LOGIN_REQUIRED" });
          return;
        }
        const path = url.pathname.slice(diag ? 5 : 9);
        if (
          !(diag ? diagRoutes : businessRoutes).test(path) ||
          url.search.length > 2000
        ) {
          json(res, 403, { code: "ROUTE_DENIED" });
          return;
        }
        if (
          biz &&
          (path.startsWith("/api/drills") || path.startsWith("/api/admin")) &&
          session.role !== "ADMIN"
        ) {
          json(res, 403, { code: "ADMIN_REQUIRED" });
          return;
        }
        const owner = "user-" + session.userId;
        const headers = {
          "Content-Type": "application/json",
          Authorization:
            "Bearer " +
            (diag
              ? createHmac("sha256", secret).update(owner).digest("hex")
              : session.token),
        };
        if (diag) headers["X-TracePilot-Owner"] = owner;
        if (req.headers["idempotency-key"])
          headers["Idempotency-Key"] = String(req.headers["idempotency-key"]);
        if (req.headers["last-event-id"])
          headers["Last-Event-ID"] = String(req.headers["last-event-id"]);
        const abort = new AbortController();
        res.on("close", () => abort.abort());
        const timer = setTimeout(
          () => abort.abort(),
          path.endsWith("/events") ? 65000 : 25000,
        );
        res.on("finish", () => clearTimeout(timer));
        res.on("close", () => clearTimeout(timer));
        try {
          const response = await fetch(
            (diag ? diagnosis : business) + path + url.search,
            {
              method: req.method,
              headers,
              body: ["GET", "HEAD"].includes(req.method)
                ? undefined
                : await body(req),
              signal: abort.signal,
              redirect: "error",
            },
          );
          res.writeHead(response.status, {
            "Content-Type":
              response.headers.get("content-type") || "application/json",
            "Cache-Control": "no-store",
            ...(response.headers.get("content-disposition")
              ? {
                  "Content-Disposition": response.headers.get(
                    "content-disposition",
                  ),
                }
              : {}),
          });
          if (response.body)
            Readable.fromWeb(response.body)
              .on("error", () => res.destroy())
              .pipe(res);
          else res.end();
        } catch (error) {
          clearTimeout(timer);
          throw error;
        }
        return;
      }
      const files = {
        "/": "index.html",
        "/index.html": "index.html",
        "/app.js": "app.js",
        "/events.js": "events.js",
        "/report.js": "report.js",
        "/styles.css": "styles.css",
      };
      if (req.method !== "GET" || !files[url.pathname]) {
        json(res, 404, { code: "NOT_FOUND" });
        return;
      }
      const file = files[url.pathname];
      res.writeHead(200, {
        "Content-Type": file.endsWith(".css")
          ? "text/css"
          : file.endsWith(".js")
            ? "text/javascript"
            : "text/html; charset=utf-8",
        "Cache-Control": "no-cache",
      });
      res.end(await readFile(root + file));
    } catch (e) {
      if (!res.headersSent)
        json(res, 503, {
          code: "SERVICE_UNAVAILABLE",
          message: "服务暂不可用，请稍后重试。预约与诊断可独立使用。",
        });
      else res.end();
    }
  })
  .listen(port, "127.0.0.1", () =>
    console.log(`TracePilot web ready: http://127.0.0.1:${port}`),
  );
setInterval(() => {
  for (const [id, s] of sessions)
    if (s.expires < Date.now()) sessions.delete(id);
}, 60000).unref();
