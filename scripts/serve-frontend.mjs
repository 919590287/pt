import { createServer } from "node:http";
import { createReadStream } from "node:fs";
import { stat } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const distDir = path.resolve(process.env.FRONTEND_DIST_DIR || ".");
const host = process.env.FRONTEND_HOST || "0.0.0.0";
const port = Number(process.env.FRONTEND_PORT || 8088);

const mimeTypes = new Map([
  [".css", "text/css; charset=utf-8"],
  [".gif", "image/gif"],
  [".html", "text/html; charset=utf-8"],
  [".ico", "image/x-icon"],
  [".jpeg", "image/jpeg"],
  [".jpg", "image/jpeg"],
  [".js", "text/javascript; charset=utf-8"],
  [".json", "application/json; charset=utf-8"],
  [".map", "application/json; charset=utf-8"],
  [".mjs", "text/javascript; charset=utf-8"],
  [".png", "image/png"],
  [".svg", "image/svg+xml; charset=utf-8"],
  [".wasm", "application/wasm"],
  [".webp", "image/webp"],
]);

function safeResolve(urlPath) {
  let pathname;
  try {
    pathname = decodeURIComponent(new URL(urlPath, "http://localhost").pathname);
  } catch {
    return null;
  }

  const normalized = path.normalize(pathname).replace(/^(\.\.[/\\])+/, "");
  const resolved = path.resolve(distDir, `.${normalized}`);
  if (resolved !== distDir && !resolved.startsWith(`${distDir}${path.sep}`)) {
    return null;
  }
  return resolved;
}

function cacheControl(filePath) {
  const relative = path.relative(distDir, filePath).replaceAll(path.sep, "/");
  if (
    relative === "index.html" ||
    relative === "runtime-config.js" ||
    relative === "map-config.js"
  ) {
    return "no-cache, no-store, must-revalidate";
  }
  if (relative.startsWith("assets/")) {
    return "public, max-age=31536000, immutable";
  }
  return "no-cache";
}

async function findFile(requestPath) {
  const candidate = safeResolve(requestPath);
  if (!candidate) return null;

  try {
    const info = await stat(candidate);
    if (info.isFile()) return candidate;
    if (info.isDirectory()) {
      const indexFile = path.join(candidate, "index.html");
      if ((await stat(indexFile)).isFile()) return indexFile;
    }
  } catch {
    // Fall through to SPA fallback below.
  }

  const ext = path.extname(candidate);
  if (!ext) {
    const indexFile = path.join(distDir, "index.html");
    try {
      if ((await stat(indexFile)).isFile()) return indexFile;
    } catch {
      return null;
    }
  }
  return null;
}

const server = createServer(async (req, res) => {
  if (!["GET", "HEAD"].includes(req.method || "")) {
    res.writeHead(405, { Allow: "GET, HEAD" });
    res.end("Method Not Allowed");
    return;
  }

  const filePath = await findFile(req.url || "/");
  if (!filePath) {
    res.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" });
    res.end("Not Found");
    return;
  }

  const contentType = mimeTypes.get(path.extname(filePath).toLowerCase()) || "application/octet-stream";
  res.setHeader("Content-Type", contentType);
  res.setHeader("Cache-Control", cacheControl(filePath));

  if (req.method === "HEAD") {
    res.writeHead(200);
    res.end();
    return;
  }

  createReadStream(filePath)
    .on("error", () => {
      if (!res.headersSent) {
        res.writeHead(500, { "Content-Type": "text/plain; charset=utf-8" });
      }
      res.end("Internal Server Error");
    })
    .pipe(res);
});

server.listen(port, host, () => {
  const displayHost = host === "0.0.0.0" ? "localhost" : host;
  console.log(`Frontend static server listening at http://${displayHost}:${port}`);
  console.log(`Serving ${fileURLToPath(new URL(`file://${distDir}/`))}`);
});
