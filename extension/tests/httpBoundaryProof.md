# HTTP Boundary Proof — `backendClient.js`

> **Phase 2 validation document**  
> Author: Extension/Background Agent

---

## What this proves

`backendClient.js` is the sole HTTP boundary between the Chrome extension and the Spring Boot backend. This document shows exactly how to verify both the **happy path** and the **unreachable path** from the Chrome DevTools service worker console — no automated test tooling needed at this stage.

---

## Prerequisites

| What | How |
|------|-----|
| Extension loaded | `chrome://extensions` → Developer Mode → **Load unpacked** → select `extension/` |
| Service worker open | `chrome://extensions` → the extension's **"Service Worker"** link → DevTools opens |

---

## Step 1 — Import `checkHealth` in the service worker console

All public functions are ES module exports. To call them from the DevTools console you need to reach them through the already-running module graph. The background script exposes nothing globally, but you can import dynamically:

```js
// Paste into the service worker DevTools console:
const { checkHealth, postIngest, getStatus } = await import(
  chrome.runtime.getURL('background/backendClient.js')
);
```

If the import succeeds you'll see the module object echoed back with no errors.

---

## Step 2A — Backend is RUNNING (Phase 1 happy path)

**Start the backend first** (see Phase 1 instructions — `mvn spring-boot:run`).

```js
await checkHealth();
```

### Expected console output

```
[backendClient] → GET http://localhost:8080/api/contest/health
[backendClient] ← 200 OK (http://localhost:8080/api/contest/health)
```

### Expected return value

```js
{ status: "UP", version: "1.0.0" }   // exact shape from Phase 1 backend
```

### Expected storage state

Open **Application → Storage → Extension Storage → Local** in DevTools:

```json
{
  "backendUnreachable": false,
  ...
}
```

---

## Step 2B — Backend is NOT RUNNING (unreachable path)

Make sure the backend process is **not** running (or point to a wrong port).

```js
await checkHealth();
```

### Expected console output

```
[backendClient] → GET http://localhost:8080/api/contest/health
[backendClient] TypeError — backend unreachable: Failed to fetch
```

### Expected return value

```js
null
```

### Expected storage state

```json
{
  "backendUnreachable": true,
  ...
}
```

---

## Step 3 — Verify `postIngest` drop-on-unreachable

With the backend still **not running** (so `backendUnreachable` is `true`):

```js
await postIngest(1, { accepted: 42, total: 100, percentage: 42.0 });
```

### Expected console output

```
[backendClient] postIngest(Q1) dropped — backend is unreachable.
```

### Expected return value

```js
null
```

**No network request is made** — verify by checking the Network tab in DevTools (it should be empty).

---

## Step 4 — Recovery: bring the backend back up

Start the backend and call `checkHealth()` again:

```js
await checkHealth();
```

`backendUnreachable` flips back to `false`. The next call to `postIngest` will now execute the real network request.

---

## Step 5 — Verify storage key survival across SW restart

1. Call `checkHealth()` successfully (sets `backendUnreachable: false`).
2. In `chrome://extensions`, click **"Service worker"** → Inspect → in the console, click **"Stop"** to force a SW restart.
3. Re-open the service worker inspector.
4. In Application → Local Storage, confirm `backendUnreachable` is still `false` — proving the flag survives SW restarts because it lives in `chrome.storage.local`, not in-memory.

---

## Summary table

| Scenario | `checkHealth()` return | `backendUnreachable` flag | Network request made? |
|----------|----------------------|--------------------------|----------------------|
| Backend running | `{ status: "UP", ... }` | `false` | ✅ Yes |
| Backend down | `null` | `true` | ✅ Yes (TypeError) |
| `postIngest` while down | `null` | unchanged (`true`) | ❌ No (dropped) |
| Recovery after restart | `{ status: "UP", ... }` | `false` | ✅ Yes |
