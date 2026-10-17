import assert from "node:assert/strict";
import { afterEach, beforeEach, mock, test } from "node:test";
import worker from "./worker.mjs";

const origin = "https://updates.example.com";
const api = "https://git.hoodnet.work/api/v3/repos/microcord/microcord";
const env = { GITHUB_TOKEN: "local-test-only" };
const release = {
  tag_name: "build-42", draft: false, prerelease: false, published_at: "2026-09-06T12:00:00Z",
  target_commitish: "master",
  body: "Public release notes",
  author: { login: "DO NOT EXPOSE" },
  assets: [{ id: 7, name: "app-release.apk", state: "uploaded", size: 8, digest: `sha256:${"a".repeat(64)}` }],
};
const commitTimestamp = Date.parse("2026-09-06T11:00:00Z") / 1000;
const manifest = {
  commitSha: "b".repeat(40), commitTimestamp, versionCode: commitTimestamp - 1577836800,
  versionName: "1.0", sha256: "a".repeat(64), size: 8, packageName: "dev.mantikafasi.manticord",
};
const fromSha = "c".repeat(40);
const legacyOrigin = "https://updates.example.workers.dev";
const apkBytes = [80, 75, 3, 4, 65, 80, 75, 33];

function upstreamApk(headers = {}) {
  return new Response(new Uint8Array(apkBytes), {
    headers: { "Content-Type": "application/octet-stream", "Content-Length": String(apkBytes.length), ...headers },
  });
}
const changesPath = `/releases/build-42/changes.json?from=${fromSha}`;

function ahead(from = fromSha, to = manifest.commitSha) {
  return {
    status: "ahead", base_commit: { sha: from }, merge_base_commit: { sha: from }, total_commits: 2,
    commits: [
      { sha: "d".repeat(40), commit: { message: "First title\nDO NOT EXPOSE commit body", author: { email: "DO NOT EXPOSE" } } },
      { sha: to, commit: { message: "Second title\r\nDO NOT EXPOSE", committer: { email: "DO NOT EXPOSE" } }, author: { login: "DO NOT EXPOSE" } },
    ],
    files: [{ filename: "DO NOT EXPOSE", patch: "DO NOT EXPOSE" }],
    diff_url: "https://untrusted.example/DO-NOT-EXPOSE",
  };
}

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
    // A fresh body per match, as the real cache gives: cancelling a cloned (teed) stream never settles in Node.
    async match(request) {
      const entry = cached.get(request.url);
      return entry && new Response(await entry.clone().arrayBuffer(), entry);
    },
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

function request(path = "/latest.json", options = {}, base = origin) {
  return worker.fetch(new Request(`${base}${path}`, options), env);
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

test("latest APK redirects without caching the selected version and follows newly published releases", async () => {
  responses.push(Response.json(release));
  const response = await request("/latest.apk");
  assert.equal(response.status, 302);
  assert.equal(response.headers.get("Location"), `${origin}/releases/build-42.apk`);
  assert.equal(response.headers.get("Cache-Control"), "no-store");
  assert.equal(calls[0].url, `${api}/releases/latest`);
  const head = await request("/latest.apk", { method: "HEAD" });
  assert.equal(head.status, 302);
  assert.equal(await head.text(), "");
  assert.equal(head.headers.get("Location"), response.headers.get("Location"));
  assert.equal(calls.length, 1);
  cached.clear(); // The existing latest-release metadata cache expires after 60 seconds.
  responses.push(Response.json({ ...release, tag_name: "build-43" }));
  assert.equal((await request("/latest.apk")).headers.get("Location"), `${origin}/releases/build-43.apk`);
  for (const path of ["/latest.apk?tag=build-42", "/latest.apk?from=bad"]) {
    assert.equal((await request(path)).status, 404);
  }
  assert.equal((await request("/latest.apk", { method: "POST" })).status, 405);
  assert.equal(calls.length, 2);
});

test("latest APK does not redirect to missing or unpublished releases", async () => {
  responses.push(new Response(null, { status: 404 }), Response.json({ ...release, draft: true }));
  for (let i = 0; i < 2; i++) {
    const response = await request("/latest.apk");
    assert.equal(response.status, 404);
    assert.equal(response.headers.get("Location"), null);
  }
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
  assert.deepEqual([...cached.keys()], [`${origin}/_release-cache/v4/latest`]);
  assert.deepEqual(await cached.values().next().value.clone().json(), {
    ...cacheFields, assetId: 7, abis: {}, notes: release.body, notesTruncated: false,
  });
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
  responses.push(upstreamApk());
  assert.equal((await request("/releases/build-163.apk")).status, 200);
  assert.equal(calls[4].url, `${api}/releases/assets/42`);
  assert.equal(calls.length, 5);
});

test("old cache schema cannot bypass manifest lookup and cached feeds still require the secret", async () => {
  cached.set(`${origin}/_release-cache/latest`, Response.json({ release: "build-99", assetId: 99 }));
  cached.set(`${origin}/_release-cache/v2/latest`, Response.json({ release: "build-99", assetId: 99 }));
  cached.set(`${origin}/_release-cache/v3/latest`, Response.json({ release: "build-99", assetId: 99 }));
  responses.push(Response.json(withManifest()), new Response(JSON.stringify(manifest)));
  assert.equal((await (await request()).json()).commitSha, manifest.commitSha);
  assert.equal(calls.length, 2);
  assert.equal((await worker.fetch(new Request(`${origin}/latest.json`), {})).status, 503);
  assert.equal((await request("/_release-cache/v4/latest")).status, 404);
  assert.equal(calls.length, 2);
});

test("download resolves only the requested tag and redirects without forwarding user credentials", async () => {
  const signedUrl = "https://media.git.hoodnet.work/asset?signature=temporary-test-value";
  responses.push(Response.json(release), new Response(null, { status: 302, headers: { Location: signedUrl } }));
  const response = await request("/releases/build-42.apk", {
    headers: { Authorization: "untrusted", Cookie: "untrusted", Range: "bytes=4-" },
  }, legacyOrigin);
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
    const response = await request("/releases/build-42.apk", { headers: { Range: "bytes=0-3", "If-Range": "test-etag" } }, legacyOrigin);
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

test("changes compare the exact installed SHA to the validated published SHA and cache only public fields", async () => {
  responses.push(Response.json(withManifest()), new Response(JSON.stringify(manifest)), Response.json(ahead(), {
    headers: { "Set-Cookie": "DO NOT EXPOSE", "Location": "https://untrusted.example/DO-NOT-EXPOSE" },
  }));
  const response = await request(changesPath, { headers: { Authorization: "untrusted", Cookie: "untrusted" } });
  assert.equal(response.status, 200);
  const expected = {
    release: "build-42", fromSha, toSha: manifest.commitSha, notes: release.body, notesTruncated: false,
    comparison: "compared", totalCommits: 2, commitsTruncated: false,
    commits: [{ sha: "d".repeat(40), title: "First title" }, { sha: manifest.commitSha, title: "Second title" }],
  };
  assert.deepEqual(await response.json(), expected);
  assert.equal(response.headers.get("Cache-Control"), "public, max-age=60");
  assert.equal(response.headers.get("X-Content-Type-Options"), "nosniff");
  assert.equal(response.headers.get("Set-Cookie"), null);
  assert.equal(response.headers.get("Location"), null);
  assert.equal(calls[0].url, `${api}/releases/tags/build-42`);
  assert.equal(calls[1].url, `${api}/releases/assets/8`);
  assert.equal(calls[2].url, `${api}/compare/${fromSha}...${manifest.commitSha}?per_page=100&page=1`);
  assert.equal(calls[2].options.redirect, "manual");
  assert.ok(calls[2].options.signal instanceof AbortSignal);
  assert.equal(calls[2].options.headers.Authorization, `Bearer ${env.GITHUB_TOKEN}`);
  assert.equal(calls[2].options.headers.Accept, "application/vnd.github+json");
  assert.equal(calls[2].options.headers.Cookie, undefined);
  const comparisonCache = cached.get(`${origin}/_compare-cache/v2/${fromSha}/${manifest.commitSha}`);
  assert.equal(comparisonCache.headers.get("Cache-Control"), "public, max-age=600");
  const { commits, comparison, totalCommits, commitsTruncated } = expected;
  assert.deepEqual(await comparisonCache.clone().json(), { commits, comparison, totalCommits, commitsTruncated });
  for (const entry of cached.values()) assert.doesNotMatch(await entry.clone().text(), /DO NOT EXPOSE|untrusted|local-test-only/);
  assert.deepEqual(await (await request(changesPath.replace(fromSha, fromSha.toUpperCase()))).json(), expected);
  const head = await request(changesPath, { method: "HEAD" });
  assert.equal(head.status, 200);
  assert.equal(await head.text(), "");
  assert.equal(calls.length, 3);
  assert.equal((await worker.fetch(new Request(`${origin}${changesPath}`), {})).status, 503);
  assert.equal((await request(`/_compare-cache/v2/${fromSha}/${manifest.commitSha}`)).status, 404);
  // Notes/compare failures cannot change the original feed or version-pinned APK path.
  responses.push(Response.json(withManifest()), new Response(JSON.stringify(manifest)));
  const feed = await (await request()).json();
  assert.equal(feed.notes, undefined);
  assert.equal(feed.commits, undefined);
  responses.push(upstreamApk());
  assert.deepEqual([...new Uint8Array(await (await request("/releases/build-42.apk")).arrayBuffer())], apkBytes);
  assert.equal(calls.at(-1).url, `${api}/releases/assets/7`);
});

test("comparison cache separates both SHAs and origins but reuses immutable pairs across tags", async () => {
  responses.push(Response.json(withManifest()), new Response(JSON.stringify(manifest)), Response.json(ahead()));
  await request(changesPath);
  const otherFrom = "e".repeat(40);
  responses.push(Response.json(ahead(otherFrom)));
  assert.equal((await (await request(changesPath.replace(fromSha, otherFrom))).json()).fromSha, otherFrom);
  assert.equal(calls.length, 4);

  const otherManifest = { ...manifest, commitSha: "f".repeat(40) };
  const body = JSON.stringify(otherManifest);
  responses.push(Response.json({ ...withManifest(body), tag_name: "build-43" }), new Response(body), Response.json(ahead(fromSha, otherManifest.commitSha)));
  assert.equal((await (await request(changesPath.replace("build-42", "build-43"))).json()).toSha, otherManifest.commitSha);
  assert.equal(calls.length, 7);
  assert.equal(calls[6].url, `${api}/compare/${fromSha}...${otherManifest.commitSha}?per_page=100&page=1`);

  responses.push(Response.json({ ...withManifest(), tag_name: "build-44", body: "Other release notes" }), new Response(JSON.stringify(manifest)));
  assert.equal((await (await request(changesPath.replace("build-42", "build-44"))).json()).notes, "Other release notes");
  assert.equal(calls.length, 9);
  const otherOrigin = "https://other-updates.example.com";
  responses.push(Response.json(withManifest()), new Response(JSON.stringify(manifest)), Response.json(ahead()));
  assert.equal((await worker.fetch(new Request(`${otherOrigin}${changesPath}`), env)).status, 200);
  assert.ok(cached.has(`${otherOrigin}/_compare-cache/v2/${fromSha}/${manifest.commitSha}`));
  assert.equal(calls.length, 12);

  // Metadata expiry refreshes owner-edited notes without refetching an immutable comparison.
  cached.delete(`${origin}/_release-cache/v4/build-42`);
  responses.push(Response.json({ ...withManifest(), body: "Edited notes" }), new Response(JSON.stringify(manifest)));
  assert.equal((await (await request(changesPath)).json()).notes, "Edited notes");
  assert.equal(calls.length, 14);
});

test("legacy and identical releases expose notes without comparing or fabricating source identity", async () => {
  responses.push(Response.json(release));
  assert.deepEqual(await (await request(changesPath)).json(), {
    release: "build-42", fromSha, toSha: null, notes: release.body, notesTruncated: false,
    comparison: "unavailable", totalCommits: null, commits: [], commitsTruncated: false,
  });
  assert.equal(calls.length, 1);
  cached.clear();
  responses.push(Response.json(withManifest()), new Response(JSON.stringify(manifest)));
  assert.deepEqual(await (await request(changesPath.replace(fromSha, manifest.commitSha.toUpperCase()))).json(), {
    release: "build-42", fromSha: manifest.commitSha, toSha: manifest.commitSha, notes: release.body, notesTruncated: false,
    comparison: "identical", totalCommits: 0, commits: [], commitsTruncated: false,
  });
  assert.equal(calls.length, 3);
  assert.equal(cached.size, 1);
});

test("notes are bounded in UTF-16 and cached only after release and manifest validation", async () => {
  for (const body of [null, undefined, 7, { private: "DO NOT EXPOSE" }, "", "n".repeat(4000), "n".repeat(4001), "\ud83d\ude00".repeat(2001)]) {
    cached.clear();
    responses.push(Response.json({ ...release, body }));
    const changes = await (await request(changesPath)).json();
    assert.equal(changes.notes, typeof body === "string" ? body.slice(0, 4000) : "");
    assert.equal(changes.notesTruncated, typeof body === "string" && body.length > 4000);
    const entry = cached.get(`${origin}/_release-cache/v4/build-42`);
    assert.equal(entry.headers.get("Cache-Control"), "public, max-age=60");
    assert.equal((await entry.clone().json()).notes, changes.notes);
  }
  cached.clear();
  cached.set(`${origin}/_release-cache/v3/build-42`, Response.json({ release: "build-42", notes: "DO NOT EXPOSE" }));
  responses.push(Response.json(release));
  assert.equal((await (await request(changesPath)).json()).notes, release.body);
  cached.clear();
  for (const change of [{ draft: true }, { prerelease: true }, { tag_name: "build-43" }, { assets: [] }]) {
    responses.push(Response.json({ ...withManifest(), ...change, body: "DO NOT EXPOSE" }));
    const response = await request(changesPath);
    assert.equal(response.status, 404);
    assert.doesNotMatch(await response.text(), /DO NOT EXPOSE/);
    assert.equal(cached.size, 0);
  }
  responses.push(Response.json({ ...withManifest(), body: "DO NOT EXPOSE" }), new Response("Invalid manifest"));
  const response = await request(changesPath);
  assert.equal(response.status, 503);
  assert.doesNotMatch(await response.text(), /DO NOT EXPOSE/);
  assert.equal(cached.size, 0);
});

test("titles use only the first line, normalize SHAs, and cap at 200 UTF-16 units", async () => {
  const messages = [
    ...["\n", "\r", "\r\n", "\v", "\f", "\u0085", "\u2028", "\u2029"].map(separator => `Title${separator}DO NOT EXPOSE`),
    "\nDO NOT EXPOSE", "", "x".repeat(201), "\ud83d\ude00".repeat(101),
  ];
  const comparison = { ...ahead(), total_commits: messages.length, commits: messages.map(message => ({ sha: manifest.commitSha.toUpperCase(), commit: { message } })) };
  responses.push(Response.json(withManifest()), new Response(JSON.stringify(manifest)), Response.json(comparison));
  const changes = await (await request(changesPath)).json();
  assert.deepEqual(changes.commits.map(commit => commit.title), [...Array(8).fill("Title"), "", "", "x".repeat(200), "\ud83d\ude00".repeat(100)]);
  for (const commit of changes.commits) assert.equal(commit.sha, manifest.commitSha);
  for (const entry of cached.values()) assert.doesNotMatch(await entry.clone().text(), /DO NOT EXPOSE/);
});

test("rebased histories return target-only commits and bypass the old negative cache", async () => {
  cached.set(`${origin}/_compare-cache/v1/${fromSha}/${manifest.commitSha}`, Response.json({
    commits: [], comparison: "unavailable", totalCommits: null, commitsTruncated: false,
  }));
  const diverged = { ...ahead(), status: "diverged", merge_base_commit: { sha: "e".repeat(40) }, behind_by: 2 };
  responses.push(Response.json(withManifest()), new Response(JSON.stringify(manifest)), Response.json(diverged));
  const changes = await (await request(changesPath)).json();
  assert.equal(changes.comparison, "compared");
  assert.equal(changes.totalCommits, 2);
  assert.equal(changes.commitsTruncated, false);
  assert.deepEqual(changes.commits, [
    { sha: "d".repeat(40), title: "First title" },
    { sha: manifest.commitSha, title: "Second title" },
  ]);
  assert.equal(calls.length, 3);
  assert.deepEqual(await (await request(changesPath)).json(), changes);
  assert.equal(calls.length, 3);
});

test("behind and malformed comparisons are unavailable with a short sanitized negative cache", async () => {
  const invalid = [
    null, [], {},
    ...[
      { status: "behind" }, { status: "diverged" }, { status: "identical" }, { status: "unknown" },
      { status: "diverged", merge_base_commit: { sha: manifest.commitSha } },
      { status: "diverged", merge_base_commit: { sha: "0".repeat(40) } },
      { status: "diverged", merge_base_commit: null },
      { status: "diverged", merge_base_commit: { sha: "e".repeat(40) }, base_commit: { sha: manifest.commitSha } },
      { base_commit: { sha: manifest.commitSha } }, { merge_base_commit: { sha: manifest.commitSha } },
      { base_commit: null }, { merge_base_commit: { sha: `${fromSha}\n` } },
      ...[-1, 0, 1.5, "2", null, Number.MAX_SAFE_INTEGER + 1, 3].map(total_commits => ({ total_commits })),
      { commits: null }, { commits: [] }, { commits: {} }, { commits: Array(101).fill(ahead().commits[0]), total_commits: 101 },
      ...[null, {}, { sha: "short" }, { sha: "g".repeat(40) }, { sha: "0".repeat(40) },
        { sha: `${manifest.commitSha}\n` }, { sha: manifest.commitSha, commit: { message: null } },
        { sha: manifest.commitSha, commit: { message: 4 } },
      ].map(commit => ({ commits: [ahead().commits[0], commit] })),
    ].map(change => ({ ...ahead(), ...change, private: "DO NOT EXPOSE" })),
  ];
  for (const comparison of invalid) {
    cached.clear();
    const count = calls.length;
    responses.push(Response.json(withManifest()), new Response(JSON.stringify(manifest)), Response.json(comparison));
    const response = await request(changesPath);
    assert.equal(response.status, 200);
    const changes = await response.json();
    assert.equal(changes.notes, release.body);
    assert.equal(changes.comparison, "unavailable");
    assert.equal(changes.totalCommits, null);
    assert.equal(changes.commitsTruncated, false);
    assert.deepEqual(changes.commits, []);
    const entry = cached.get(`${origin}/_compare-cache/v2/${fromSha}/${manifest.commitSha}`);
    assert.equal(entry.headers.get("Cache-Control"), "public, max-age=60");
    assert.deepEqual(await entry.clone().json(), { commits: [], comparison: "unavailable", totalCommits: null, commitsTruncated: false });
    assert.equal((await request(changesPath)).status, 200);
    assert.equal(calls.length, count + 3);
  }
});

test("unknown bases, throttling, server/parse/timeout failures and redirects never hide notes or leak details", async () => {
  const warnings = [];
  mock.method(console, "warn", (...args) => warnings.push(args));
  const failures = [
    ...[401, 403, 404, 422, 429, 500, 502, 503].map(status => new Response("DO NOT EXPOSE", { status })),
    ...[301, 302, 303, 307, 308].map(status => new Response("DO NOT EXPOSE", { status, headers: { Location: "https://evil.example/DO-NOT-EXPOSE" } })),
    new Response(null, { status: 302, headers: { Location: "https://media.git.hoodnet.work/compare-test" } }),
    new TypeError("DO NOT EXPOSE"), new DOMException("DO NOT EXPOSE", "TimeoutError"), new DOMException("DO NOT EXPOSE", "AbortError"),
    new Response("DO NOT EXPOSE invalid JSON"), new Response(new Uint8Array([0xff])),
    new Response(new ReadableStream({ start(controller) { controller.error(new Error("DO NOT EXPOSE body failure")); } })),
  ];
  for (const failure of failures) {
    cached.clear();
    const count = calls.length;
    responses.push(Response.json(withManifest()), new Response(JSON.stringify(manifest)), failure);
    const response = await request(changesPath);
    assert.equal(response.status, 200);
    assert.equal(response.headers.get("Location"), null);
    assert.equal(response.headers.get("Set-Cookie"), null);
    const changes = await response.json();
    assert.equal(changes.notes, release.body);
    assert.equal(changes.comparison, "unavailable");
    assert.deepEqual(changes.commits, []);
    assert.equal(calls.length, count + 3);
    for (const entry of cached.values()) assert.doesNotMatch(await entry.clone().text(), /DO NOT EXPOSE|evil\.example|local-test-only/);
    cached.delete(`${origin}/_apk-cache/v1/build-42/universal/${"a".repeat(64)}`);
    responses.push(upstreamApk());
    assert.deepEqual([...new Uint8Array(await (await request("/releases/build-42.apk")).arrayBuffer())], apkBytes);
  }
  assert.deepEqual(warnings, []);
});

test("comparison bodies are capped at 4 MiB even with missing or dishonest Content-Length", async () => {
  const limit = 4 * 1024 * 1024;
  const body = JSON.stringify(ahead());
  responses.push(Response.json(withManifest()), new Response(JSON.stringify(manifest)), new Response(body.padEnd(limit)));
  assert.equal((await (await request(changesPath)).json()).comparison, "compared");
  for (const length of [null, "1", String(limit + 1), "invalid"]) {
    cached.clear();
    let cancelled = false;
    let reads = 0;
    const stream = new ReadableStream({
      pull(controller) { reads++; controller.enqueue(new Uint8Array(64 * 1024)); },
      cancel() { cancelled = true; },
    });
    responses.push(Response.json(withManifest()), new Response(JSON.stringify(manifest)), new Response(stream, {
      headers: length === null ? {} : { "Content-Length": length },
    }));
    const response = await request(changesPath);
    const changes = await response.json();
    assert.equal(changes.comparison, "unavailable");
    assert.equal(changes.notes, release.body);
    assert.equal(cancelled, true);
    assert.ok(reads <= 66);
  }
});

test("commit pagination and JSON expansion signal truncation and stay within the app's 128 KiB cap", async () => {
  for (const total of [99, 100, 101, 500]) {
    cached.clear();
    const comparison = { ...ahead(), total_commits: total, commits: Array(Math.min(total, 100)).fill(ahead().commits[0]) };
    responses.push(Response.json(withManifest()), new Response(JSON.stringify(manifest)), Response.json(comparison));
    const response = await request(changesPath);
    const text = await response.text();
    const changes = JSON.parse(text);
    assert.equal(changes.commits.length, Math.min(total, 100));
    assert.equal(changes.totalCommits, total);
    assert.equal(changes.commitsTruncated, total > 100);
    assert.ok(Buffer.byteLength(text) <= 128 * 1024);
  }
  cached.clear();
  const comparison = { ...ahead(), total_commits: 100, commits: Array(100).fill({ sha: manifest.commitSha, commit: { message: "\ud800".repeat(200) } }) };
  responses.push(Response.json({ ...withManifest(), body: "\ud800".repeat(4001) }), new Response(JSON.stringify(manifest)), Response.json(comparison));
  const text = await (await request(changesPath)).text();
  const changes = JSON.parse(text);
  assert.ok(Buffer.byteLength(text) <= 128 * 1024);
  assert.equal(changes.notes.length, 4000);
  assert.equal(changes.notesTruncated, true);
  assert.equal(changes.commitsTruncated, true);
  assert.equal(changes.totalCommits, 100);
  assert.ok(changes.commits.length > 0 && changes.commits.length < 100);
  assert.equal(changes.comparison, "compared");
  assert.equal(await (await request(changesPath)).text(), text);
});

test("changes reject missing, duplicate, malformed or extra query inputs without any upstream request", async () => {
  for (const query of ["", "?from=", "?from=master", "?from=build-42", `?from=${fromSha.slice(1)}`, `?from=${fromSha}0`,
    `?from=${"g".repeat(40)}`, `?from=${fromSha}%0A`, `?from=${fromSha}%00`, `?from=${fromSha}%20`,
    `?from=${fromSha}...master`, "?from=https://evil.example", `?from=${fromSha}&from=${fromSha}`,
    `?from=${fromSha}&%66rom=${fromSha}`, `?from=${fromSha}&repo=other/repo`, `?from=${fromSha}&tag=build-43`,
    `?from=${fromSha}&branch=master`, `?from=${fromSha}&url=https://evil.example`, `?From=${fromSha}`,
  ]) assert.equal((await request(`/releases/build-42/changes.json${query}`)).status, 404);
  for (const path of ["master", "build-0", "build-01", "build-10000000000", "build-42%2Fother", "%62uild-42"]) {
    assert.equal((await request(`/releases/${path}/changes.json?from=${fromSha}`)).status, 404);
  }
  assert.equal((await request(`/latest.json?from=${fromSha}`)).status, 404);
  assert.equal((await request(`/releases/build-42.apk?from=${fromSha}`)).status, 404);
  const post = await request(changesPath, { method: "POST" });
  assert.equal(post.status, 405);
  assert.equal(post.headers.get("Allow"), "GET, HEAD");
  const head = await request("/releases/build-42/changes.json", { method: "HEAD" });
  assert.equal(head.status, 404);
  assert.equal(await head.text(), "");
  assert.equal((await worker.fetch(new Request(`${origin}${changesPath}`), {})).status, 503);
  assert.equal(calls.length, 0);
});

test("comparison cache failures do not prevent release notes from being returned", async () => {
  responses.push(Response.json(withManifest()), new Response(JSON.stringify(manifest)), Response.json(ahead()));
  mock.method(globalThis.caches.default, "put", async (key, response) => {
    if (key.url.includes("_compare-cache")) throw new Error("DO NOT EXPOSE");
    cached.set(key.url, response.clone());
  });
  assert.equal((await (await request(changesPath)).json()).comparison, "compared");
  mock.method(globalThis.caches.default, "match", async key => {
    if (key.url.includes("_compare-cache")) throw new Error("DO NOT EXPOSE");
    return cached.get(key.url)?.clone();
  });
  const changes = await (await request(changesPath)).json();
  assert.equal(changes.notes, release.body);
  assert.equal(changes.comparison, "unavailable");
  assert.deepEqual(changes.commits, []);
});

const split = abi => ({ id: 70 + ABIS.indexOf(abi), name: `app-${abi}-release.apk`, state: "uploaded", size: 8, digest: `sha256:${String(ABIS.indexOf(abi)).repeat(64)}` });
const ABIS = ["arm64-v8a", "armeabi-v7a", "x86_64", "x86"];
const splitRelease = { ...release, assets: [...release.assets, ...ABIS.map(split)] };

test("APKs are cached at the edge so repeat downloads never reach GHES", async () => {
  const media = "https://media.git.hoodnet.work/asset?signature=temporary-test-value";
  responses.push(Response.json(release), new Response(null, { status: 302, headers: { Location: media } }), upstreamApk());
  const first = await request("/releases/build-42.apk", { headers: { Cookie: "untrusted" } });
  assert.equal(first.status, 200);
  assert.deepEqual([...new Uint8Array(await first.arrayBuffer())], apkBytes);
  assert.equal(first.headers.get("Content-Disposition"), 'attachment; filename="microcord-build-42.apk"');
  assert.equal(first.headers.get("ETag"), `"${"a".repeat(64)}"`);
  // The media fetch carries no GitHub or caller credentials and follows no further redirects.
  assert.equal(calls[2].url, media);
  assert.equal(calls[2].options.headers, undefined);
  assert.equal(calls[2].options.redirect, "manual");
  const entry = cached.get(`${origin}/_apk-cache/v1/build-42/universal/${"a".repeat(64)}`);
  assert.equal(entry.headers.get("Cache-Control"), "public, max-age=31536000, immutable");
  assert.equal(entry.headers.get("Content-Length"), "8");
  for (let i = 0; i < 3; i++) {
    const again = await request("/releases/build-42.apk");
    assert.deepEqual([...new Uint8Array(await again.arrayBuffer())], apkBytes);
  }
  assert.equal((await request("/releases/build-42.apk", { method: "HEAD" })).status, 200);
  assert.equal(calls.length, 3);
});

test("cached ranges are asked of the edge only while the client's validator still matches", async () => {
  const ranges = [];
  const match = globalThis.caches.default.match;
  mock.method(globalThis.caches.default, "match", async key => {
    if (key.url.includes("_apk-cache")) ranges.push(key.headers.get("Range"));
    return match(key);
  });
  responses.push(Response.json(release), upstreamApk());
  await request("/releases/build-42.apk", { headers: { Range: "bytes=4-" } });
  await request("/releases/build-42.apk", { headers: { Range: "bytes=4-", "If-Range": `"${"a".repeat(64)}"` } });
  await request("/releases/build-42.apk", { headers: { Range: "bytes=4-", "If-Range": '"stale"' } });
  assert.deepEqual(ranges, ["bytes=4-", "bytes=4-", "bytes=4-", null]);
});

test("a short, ranged or non-APK upstream body is never cached", async () => {
  for (const upstream of [
    new Response("short", { headers: { "Content-Type": "application/octet-stream", "Content-Length": "5" } }),
    upstreamApk({ "Content-Type": "text/html" }),
    new Response(new Uint8Array(apkBytes), { status: 206, headers: { "Content-Type": "application/octet-stream", "Content-Length": "8" } }),
  ]) {
    cached.clear();
    // The failed fill falls back to the redirect, which costs a second asset lookup.
    responses.push(Response.json(release), upstream,
      new Response(null, { status: 302, headers: { Location: "https://media.git.hoodnet.work/asset" } }));
    const response = await request("/releases/build-42.apk");
    assert.equal(response.status, 302);
    assert.deepEqual([...cached.keys()], [`${origin}/_release-cache/v4/build-42`]);
  }
});

test("releases list their per-architecture APKs, which download and redirect by architecture", async () => {
  responses.push(Response.json(withManifestOf(splitRelease)), new Response(JSON.stringify(manifest)));
  const feed = await (await request()).json();
  assert.equal(feed.apkUrl, `${origin}/releases/build-42.apk`);
  assert.deepEqual(feed.apks["arm64-v8a"], {
    apkUrl: `${origin}/releases/build-42-arm64-v8a.apk`, sha256: "0".repeat(64), size: 8,
  });
  assert.deepEqual(Object.keys(feed.apks), ABIS);
  const latest = await request("/latest-arm64-v8a.apk");
  assert.equal(latest.headers.get("Location"), `${origin}/releases/build-42-arm64-v8a.apk`);
  assert.equal((await request("/latest.apk")).headers.get("Location"), `${origin}/releases/build-42.apk`);
  responses.push(Response.json(splitRelease), upstreamApk());
  const apk = await request("/releases/build-42-x86.apk");
  assert.equal(apk.status, 200);
  assert.equal(apk.headers.get("Content-Disposition"), 'attachment; filename="microcord-build-42-x86.apk"');
  assert.equal(calls.at(-1).url, `${api}/releases/assets/73`);
  assert.ok(cached.has(`${origin}/_apk-cache/v1/build-42/x86/${"3".repeat(64)}`));
  for (const path of ["/latest-mips.apk", "/releases/build-42-mips.apk", "/releases/build-42-arm64-v8a/changes.json"]) {
    assert.equal((await request(path)).status, 404);
  }
});

test("releases from before the split fall back to the universal APK", async () => {
  responses.push(Response.json(release));
  assert.equal((await request("/latest-arm64-v8a.apk")).headers.get("Location"), `${origin}/releases/build-42.apk`);
  assert.equal((await (await request()).json()).apks, undefined);
  responses.push(Response.json(release));
  const missing = await request("/releases/build-42-arm64-v8a.apk");
  assert.equal(missing.status, 404);
  // A split with a broken digest fails the whole release closed, like a broken universal APK.
  responses.push(Response.json({ ...release, assets: [...release.assets, { ...split("x86"), digest: null }] }));
  assert.equal((await request("/releases/build-42.apk", {}, legacyOrigin)).status, 503);
});

function withManifestOf(base) {
  const body = JSON.stringify(manifest);
  return { ...base, assets: [...base.assets, { id: 8, name: "update-manifest.json", state: "uploaded", size: Buffer.byteLength(body) }] };
}
