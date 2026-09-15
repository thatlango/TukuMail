import http from "node:http";
import { capabilities, tukumailContractVersion } from "../../../packages/contracts/src/index.js";
import { UnconfiguredMailEngine } from "../../engine/src/index.js";

const port = Number(process.env.PORT || 8080);
const engine = new UnconfiguredMailEngine();

function json(res, status, body) {
  res.writeHead(status, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" });
  res.end(JSON.stringify(body));
}

export function createServer() {
  return http.createServer(async (req, res) => {
    const url = new URL(req.url || "/", "http://localhost");
    if (req.method === "GET" && url.pathname === "/health") {
      return json(res, 200, { status: "ok", service: "tukumail-api", contractVersion: tukumailContractVersion });
    }
    if (req.method === "GET" && url.pathname === "/v1/capabilities") {
      return json(res, 200, { contractVersion: tukumailContractVersion, capabilities, engine: await engine.health() });
    }
    return json(res, 404, { error: { code: "not_found", message: "Route not found" } });
  });
}

if (process.argv[1] && import.meta.url === new URL(`file://${process.argv[1]}`).href) {
  createServer().listen(port, "0.0.0.0", () => console.log(`TukuMail API listening on :${port}`));
}
