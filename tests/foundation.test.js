import test from "node:test";
import assert from "node:assert/strict";
import { mailboxAddress, capabilities } from "../packages/contracts/src/index.js";
import { UnconfiguredMailEngine } from "../services/engine/src/index.js";
import { createServer } from "../services/api/src/server.js";

test("mailbox address is normalized and validated", () => {
  assert.equal(mailboxAddress("Jacob", "tukutuku.org"), "jacob@tukutuku.org");
  assert.throws(() => mailboxAddress("bad address", "tukutuku.org"));
});

test("engine boundary is honest before transport is configured", async () => {
  const health = await new UnconfiguredMailEngine().health();
  assert.equal(health.transportReady, false);
  assert.equal(health.status, "not_configured");
});

test("contract exposes all planned clients", () => {
  assert.deepEqual(capabilities.clients, ["web", "android", "desktop"]);
});

test("control-plane health endpoint starts", async () => {
  const server=createServer();
  await new Promise(resolve=>server.listen(0,"127.0.0.1",resolve));
  const address=server.address();
  const response=await fetch(`http://127.0.0.1:${address.port}/health`);
  assert.equal(response.status,200);
  const body=await response.json();
  assert.equal(body.service,"tukumail-api");
  await new Promise(resolve=>server.close(resolve));
});
