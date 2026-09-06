import assert from "node:assert/strict";
import { afterEach, beforeEach, mock, test } from "node:test";
import worker from "./worker.mjs";

const origin = "https://updates.example.com";
const api = "https://git.hoodnet.work/api/v3/repos/firecord/microcord";
const env = { GITHUB_TOKEN: "local-test-only" };
const release = {
  tag_name: "build-42", draft: false, prerelease: false, published_at: "2026-09-06T12:00:00Z",
  target_commitish: "master",
  body: "DO NOT EXPOSE private release notes",
  assets: [{ id: 7, name: "app-release.apk", state: "uploaded", size: 8, digest: `sha256:${"a".repeat(64)}` }],
};
const commitTimestamp = Date.parse("2026-09-06T11:00:00Z") / 1000;
const manifest = {
  commitSha: "b".repeat(40), commitTimestamp, versionCode: commitTimestamp - 1577836800,
  versionName: "1.0", sha256: "a".repeat(64), size: 8, packageName: "dev.mantikafasi.manticord",
};

function withManifest(body = JSON.stringify(manifest), assetChanges = {}) {
  return { ...release, assets: [...release.assets, {
    id: 8, name: "update-manifest.json", state: "uploaded", size: Buffer.byteLength(body),
    url: "https://untrusted.example/ignored", browser_download_url: "https://untrusted.example/ignored",
    ...assetChanges,
  }] };
}
let calls, responses, cached;

beforeEach(() => {
  calls = [];
  responses = [];
  cached = new Map();
  globalThis.caches = { default: {
    async match(request) { return cached.get(request.url)?.clone(); },
    async put(request, response) { cached.set(request.url, response.clone()); },
  } };
  mock.method(globalThis, "fetch", async (url, options) => {
    calls.push({ url, options });
    const response = responses.shift();
    assert.ok(response, "Unexpected upstream request");
    if (response instanceof Error) throw response;
    return response;
  });
});
afterEach(() => { mock.restoreAll(); delete globalThis.caches; });

function request(path = "/latest.json", options = {}) {
  return worker.fetch(new Request(`${origin}${path}`, options), env);
}

test("missing manifest preserves the legacy feed without inventing commit metadata from a branch", async () => {
  responses.push(Response.json(release));
  const response = await request();
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), {
    release: "build-42", publishedAt: release.published_at,
    apkUrl: `${origin}/releases/build-42.apk`, sha256: "a".repeat(64), size: 8,
  });
  assert.equal(response.headers.get("Cache-Control"), "public, max-age=60");
  assert.equal(calls[0].url, `${api}/releases/latest`);
  assert.equal(calls[0].options.redirect, "manual");
  assert.equal(calls[0].options.headers.Authorization, `Bearer ${env.GITHUB_TOKEN}`);
  const head = await request("/latest.json", { method: "HEAD" });
  assert.equal(head.status, 200);
  assert.equal(await head.text(), "");
  assert.equal(calls.length, 1);
  assert.doesNotMatch(await cached.values().next().value.clone().text(), /DO NOT EXPOSE|local-test-only/);
});

test("valid manifest exposes full build identity and caches only slim validated fields", async () => {
  const body = JSON.stringify({ ...manifest, commitSha: manifest.commitSha.toUpperCase(), sha256: manifest.sha256.toUpperCase(), private: "DO NOT EXPOSE" });
  responses.push(Response.json(withManifest(body)), new Response(body));
  const response = await request();
  assert.equal(response.status, 200);
  const expected = {
    release: "build-42", publishedAt: release.published_at,
    apkUrl: `${origin}/releases/build-42.apk`, ...manifest,
  };
  assert.deepEqual(await response.json(), expected);
  assert.equal(calls[1].url, `${api}/releases/assets/8`);
  assert.equal(calls[1].options.headers.Accept, "application/octet-stream");
  assert.equal(calls[1].options.headers.Authorization, `Bearer ${env.GITHUB_TOKEN}`);
  assert.equal(calls[1].options.redirect, "manual");
  assert.ok(calls[1].options.signal instanceof AbortSignal);
  const { apkUrl, ...cacheFields } = expected;
  assert.deepEqual([...cached.keys()], [`${origin}/_release-cache/v2/latest`]);
  assert.deepEqual(await cached.values().next().value.clone().json(), { ...cacheFields, assetId: 7 });
  assert.deepEqual(await (await request()).json(), expected);
  assert.equal(await (await request("/latest.json", { method: "HEAD" })).text(), "");
  assert.equal(calls.length, 2);
});

test("manifest media redirect is fetched without GitHub or caller credentials or automatic redirects", async () => {
  const location = "https://media.git.hoodnet.work/manifest-test";
  const body = JSON.stringify(manifest);
  responses.push(Response.json(withManifest(body)), new Response(null, {
    status: 302, headers: { Location: location, "Set-Cookie": "private" },
  }), new Response(body, { headers: { "Content-Length": String(Buffer.byteLength(body)), "Set-Cookie": "private" } }));
  const response = await request("/latest.json", { headers: { Authorization: "untrusted", Cookie: "untrusted" } });
  assert.equal(response.status, 200);
  assert.equal((await response.json()).commitSha, manifest.commitSha);
  assert.equal(calls.length, 3);
  assert.equal(calls[2].url, location);
  assert.equal(calls[2].options.headers, undefined);
  assert.equal(calls[2].options.credentials, "omit");
  assert.equal(calls[2].options.redirect, "manual");
  assert.equal(response.headers.get("Location"), null);
  assert.equal(response.headers.get("Set-Cookie"), null);
  assert.doesNotMatch(await cached.values().next().value.clone().text(), /manifest-test|local-test-only|private/);
});

test("malformed manifests and mismatches fail closed without caching or leaking their bodies", async () => {
  const invalid = [
    null, [], {},
    ...[
      { commitSha: "master" }, { commitSha: "b".repeat(8) }, { commitSha: "g".repeat(40) },
      { commitSha: "0".repeat(40) }, { commitSha: null }, { commitSha: `${manifest.commitSha}\n` },
      { commitTimestamp: String(commitTimestamp) }, { commitTimestamp: commitTimestamp + 0.5 },
      { commitTimestamp: 1577836800, versionCode: 0 },
      { commitTimestamp: 1577836800 + 2147483648, versionCode: 2147483648 },
      { commitTimestamp: undefined }, { versionCode: 1 }, { versionCode: null },
      { versionCode: String(manifest.versionCode) }, { versionCode: -1 },
      { versionName: null }, { versionName: " " }, { packageName: "com.discord" },
      { packageName: undefined }, { sha256: "c".repeat(64) }, { sha256: null },
      { size: 9 }, { size: "8" },
    ].map(change => ({ ...manifest, ...change, private: "DO NOT EXPOSE" })),
  ].map(value => JSON.stringify(value));
  for (const body of ["DO NOT EXPOSE invalid JSON", ...invalid]) {
    responses.push(Response.json(withManifest(body)), new Response(body));
    const response = await request();
    assert.equal(response.status, 503);
    assert.equal(response.headers.get("Cache-Control"), "no-store");
    assert.doesNotMatch(await response.text(), /DO NOT EXPOSE|local-test-only/);
    assert.equal(cached.size, 0);
  }
});

test("versionCode accepts the positive Android int boundaries with the matching commit time", async () => {
  for (const versionCode of [1, 2147483647]) {
    cached.clear();
    const body = JSON.stringify({ ...manifest, versionCode, commitTimestamp: 1577836800 + versionCode });
    responses.push(Response.json(withManifest(body)), new Response(body));
    const response = await request();
    assert.equal(response.status, 200);
    assert.equal((await response.json()).versionCode, versionCode);
  }
});

test("only the exact named manifest asset is fetched and invalid or duplicate assets are not legacy", async () => {
  const irrelevant = withManifest(undefined, { name: "other.json" });
  responses.push(Response.json(irrelevant));
  assert.equal((await request()).status, 200);
  assert.equal(calls.length, 1);
  cached.clear();
  for (const change of [{ state: "starter" }, { id: 0 }, { id: "8" }, { size: 0 }, { size: 16385 }, { size: "10" }]) {
    responses.push(Response.json(withManifest(undefined, change)));
    const count = calls.length;
    assert.equal((await request()).status, 503);
    assert.equal(calls.length, count + 1);
    assert.equal(cached.size, 0);
  }
  const duplicate = withManifest();
  duplicate.assets.push({ ...duplicate.assets[1], id: 9 });
  responses.push(Response.json(duplicate));
  assert.equal((await request()).status, 503);
  assert.equal(cached.size, 0);
});

test("manifest bytes are bounded even without Content-Length and mismatched lengths are rejected", async () => {
  const body = JSON.stringify(manifest);
  for (const change of [{ size: Buffer.byteLength(body) - 1 }, { size: Buffer.byteLength(body) + 1 }]) {
    responses.push(Response.json(withManifest(body, change)), new Response(body));
    assert.equal((await request()).status, 503);
  }
  for (const length of ["16385", "0", "invalid"]) {
    responses.push(Response.json(withManifest(body)), new Response(body, { headers: { "Content-Length": length } }));
    assert.equal((await request()).status, 503);
  }
  let cancelled = false;
  const stream = new ReadableStream({
    start(controller) {
      controller.enqueue(new TextEncoder().encode(body));
      controller.enqueue(new Uint8Array(16385));
    },
    cancel() { cancelled = true; },
  });
  responses.push(Response.json(withManifest(body, { size: 16384 })), new Response(stream));
  assert.equal((await request()).status, 503);
  assert.equal(cancelled, true);
  assert.equal(cached.size, 0);
});

test("manifest redirects reject untrusted origins and never follow a second redirect", async () => {
  for (const location of ["https://evil.example/a", "https://media.git.hoodnet.work.evil.example/a", "http://media.git.hoodnet.work/a", "https://media.git.hoodnet.work:444/a", "https://user:secret@media.git.hoodnet.work/a", "/relative"]) {
    responses.push(Response.json(withManifest()), new Response(null, { status: 302, headers: { Location: location } }));
    const count = calls.length;
    const response = await request();
    assert.equal(response.status, 503);
    assert.equal(response.headers.get("Location"), null);
    assert.equal(calls.length, count + 2);
    assert.equal(cached.size, 0);
  }
  responses.push(Response.json(withManifest()), new Response(null, { status: 302, headers: { Location: "https://media.git.hoodnet.work/manifest-test" } }),
    new Response(null, { status: 302, headers: { Location: "https://evil.example/a" } }));
  const count = calls.length;
  assert.equal((await request()).status, 503);
  assert.equal(calls.length, count + 3);
  assert.equal(calls.at(-1).options.headers, undefined);
  assert.equal(cached.size, 0);
});

test("attached but unavailable manifests fail closed and private transport errors stay sanitized", async () => {
  for (const status of [301, 307, 401, 403, 404, 500]) {
    responses.push(Response.json(withManifest()), new Response("DO NOT EXPOSE", { status, headers: { Location: "https://evil.example/a" } }));
    const count = calls.length;
    const response = await request();
    assert.equal(response.status, 503);
    assert.doesNotMatch(await response.text(), /DO NOT EXPOSE|local-test-only/);
    assert.equal(calls.length, count + 2);
    assert.equal(cached.size, 0);
  }
  const warnings = [];
  mock.method(console, "warn", (...args) => warnings.push(args));
  responses.push(Response.json(withManifest()), new TypeError("DO NOT EXPOSE private transport details"));
  assert.equal((await request()).status, 503);
  assert.deepEqual(warnings, [["Update request failed", "TypeError"]]);
});

test("historical build-163 downloads remain pinned after the latest feed has a manifest", async () => {
  responses.push(Response.json(withManifest()), new Response(JSON.stringify(manifest)));
  assert.equal((await request()).status, 200);
  responses.push(Response.json({ ...release, tag_name: "build-163", assets: [{ ...release.assets[0], id: 42 }] }),
    new Response(null, { status: 302, headers: { Location: "https://media.git.hoodnet.work/legacy-test" } }));
  assert.equal((await request("/releases/build-163.apk", { method: "HEAD" })).status, 302);
  assert.equal(calls[2].url, `${api}/releases/tags/build-163`);
  assert.equal(calls[3].url, `${api}/releases/assets/42`);
  assert.equal(calls[3].options.method, "HEAD");
  responses.push(new Response(new Uint8Array([80, 75, 3, 4]), { headers: { "Content-Type": "application/octet-stream" } }));
  assert.equal((await request("/releases/build-163.apk")).status, 200);
  assert.equal(calls[4].url, `${api}/releases/assets/42`);
  assert.equal(calls.length, 5);
});

test("old cache schema cannot bypass manifest lookup and cached feeds still require the secret", async () => {
  cached.set(`${origin}/_release-cache/latest`, Response.json({ release: "build-99", assetId: 99 }));
  responses.push(Response.json(withManifest()), new Response(JSON.stringify(manifest)));
  assert.equal((await (await request()).json()).commitSha, manifest.commitSha);
  assert.equal(calls.length, 2);
  assert.equal((await worker.fetch(new Request(`${origin}/latest.json`), {})).status, 503);
  assert.equal((await request("/_release-cache/v2/latest")).status, 404);
  assert.equal(calls.length, 2);
});

test("download resolves only the requested tag and redirects without forwarding user credentials", async () => {
  const signedUrl = "https://media.git.hoodnet.work/asset?signature=temporary-test-value";
  responses.push(Response.json(release), new Response(null, { status: 302, headers: { Location: signedUrl } }));
  const response = await request("/releases/build-42.apk", {
    headers: { Authorization: "untrusted", Cookie: "untrusted", Range: "bytes=4-" },
  });
  assert.equal(response.status, 302);
  assert.equal(response.headers.get("Location"), signedUrl);
  assert.equal(response.headers.get("Cache-Control"), "no-store");
  assert.equal(response.headers.get("Authorization"), null);
  assert.equal(calls[0].url, `${api}/releases/tags/build-42`);
  assert.equal(calls[1].url, `${api}/releases/assets/7`);
  assert.equal(calls[1].options.redirect, "manual");
  assert.equal(calls[1].options.headers.Range, "bytes=4-");
  assert.equal(calls[1].options.headers.Cookie, undefined);
  assert.equal(calls.length, 2);
});

test("binary and range responses stream with only approved headers", async () => {
  for (const status of [200, 206]) {
    cached.clear();
    responses.push(Response.json(release), new Response(new Uint8Array([80, 75, 3, 4]), {
      status,
      headers: {
        "Content-Type": "application/octet-stream", "Content-Length": "4",
        "Content-Range": "bytes 0-3/8", "Accept-Ranges": "bytes", "ETag": "test-etag",
        "Set-Cookie": "private", "X-Private-Header": "private",
      },
    }));
    const response = await request("/releases/build-42.apk", { headers: { Range: "bytes=0-3", "If-Range": "test-etag" } });
    assert.equal(response.status, status);
    assert.deepEqual([...new Uint8Array(await response.arrayBuffer())], [80, 75, 3, 4]);
    assert.equal(response.headers.get("Content-Type"), "application/vnd.android.package-archive");
    assert.equal(response.headers.get("Content-Range"), "bytes 0-3/8");
    assert.equal(response.headers.get("Set-Cookie"), null);
    assert.equal(response.headers.get("X-Private-Header"), null);
    assert.equal(calls.at(-1).options.headers["If-Range"], "test-etag");
    assert.equal(calls.at(-1).options.signal, undefined);
  }
});

test("unknown routes, query parameters, methods, malformed ranges and missing configuration fail before fetching", async () => {
  for (const path of ["/", "/_release-cache/latest", "/releases/42.apk", "/releases/build-0.apk", "/releases/build-42.zip", "/latest.json?url=https://evil.example"]) {
    assert.equal((await request(path)).status, 404);
  }
  const post = await request("/latest.json", { method: "POST" });
  assert.equal(post.status, 405);
  assert.equal(post.headers.get("Allow"), "GET, HEAD");
  assert.equal((await request("/releases/build-42.apk", { headers: { Range: "bytes=0-1,4-5" } })).status, 416);
  assert.equal((await worker.fetch(new Request(`${origin}/latest.json`), {})).status, 503);
  assert.equal(calls.length, 0);
});

test("drafts, prereleases, mismatched tags and non-APK assets are never served", async () => {
  for (const change of [{ draft: true }, { prerelease: true }, { tag_name: "build-43" }, { assets: [{ ...release.assets[0], name: "source.zip" }] }]) {
    responses.push(Response.json({ ...release, ...change }));
    assert.equal((await request("/releases/build-42.apk")).status, 404);
    assert.equal(cached.size, 0);
  }
});

test("missing digests and invalid asset identities fail closed without publishing private metadata", async () => {
  for (const change of [{ digest: null }, { digest: "sha256:bad" }, { id: -1 }, { id: "7" }, { size: 0 }]) {
    responses.push(Response.json({ ...release, assets: [{ ...release.assets[0], ...change }] }));
    const response = await request();
    assert.equal(response.status, 503);
    assert.doesNotMatch(await response.text(), /DO NOT EXPOSE|local-test-only/);
    assert.equal(cached.size, 0);
  }
});

test("unexpected redirect hosts, schemes, ports and credentials are rejected", async () => {
  for (const location of ["https://evil.example/a", "https://media.git.hoodnet.work.evil.example/a", "http://media.git.hoodnet.work/a", "https://media.git.hoodnet.work:444/a", "https://user:secret@media.git.hoodnet.work/a"]) {
    cached.clear();
    responses.push(Response.json(release), new Response(null, { status: 302, headers: { Location: location } }));
    const response = await request("/releases/build-42.apk");
    assert.equal(response.status, 503);
    assert.equal(response.headers.get("Location"), null);
  }
});

test("lookup errors and non-binary download bodies are sanitized and never cached", async () => {
  for (const status of [401, 403, 404, 500]) {
    responses.push(new Response("DO NOT EXPOSE private upstream error", { status }));
    const response = await request();
    assert.equal(response.status, status === 404 ? 404 : 503);
    assert.equal(response.headers.get("Cache-Control"), "no-store");
    assert.doesNotMatch(await response.text(), /DO NOT EXPOSE/);
  }
  responses.push(new Error("DO NOT EXPOSE credential in transport error"));
  assert.doesNotMatch(await (await request()).text(), /DO NOT EXPOSE/);
  responses.push(Response.json(release), Response.json({ private: "DO NOT EXPOSE" }));
  const response = await request("/releases/build-42.apk");
  assert.equal(response.status, 503);
  assert.doesNotMatch(await response.text(), /DO NOT EXPOSE/);
});
