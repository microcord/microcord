const API = "https://git.hoodnet.work/api/v3/repos/firecord/microcord";
const DOWNLOAD_ORIGIN = "https://media.git.hoodnet.work";
const APK_NAME = "app-release.apk";
const MANIFEST_NAME = "update-manifest.json";
const MAX_MANIFEST_BYTES = 16 * 1024;
const PACKAGE_NAME = "dev.mantikafasi.manticord";
const VERSION_EPOCH = 1577836800;
const MAX_VERSION_CODE = 2147483647;
const RELEASE_TAG = /^build-[1-9]\d{0,9}$/;

function error(status, message) {
  return Response.json({ error: message }, {
    status,
    headers: { "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" },
  });
}

function githubHeaders(token, accept) {
  return {
    Authorization: `Bearer ${token}`,
    Accept: accept,
    "User-Agent": "manticord-updates",
    "X-GitHub-Api-Version": "2022-11-28",
  };
}

function downloadLocation(response) {
  const location = new URL(response.headers.get("Location"));
  if (location.origin !== DOWNLOAD_ORIGIN || location.username || location.password) {
    throw new Error("Unexpected download origin");
  }
  return location;
}

async function readManifest(asset, token, metadata) {
  if (asset.state !== "uploaded" || !Number.isSafeInteger(asset.id) || asset.id <= 0 ||
      !Number.isSafeInteger(asset.size) || asset.size <= 0 || asset.size > MAX_MANIFEST_BYTES) {
    throw new Error("Invalid manifest asset");
  }
  const signal = AbortSignal.timeout(10_000);
  let response = await fetch(`${API}/releases/assets/${asset.id}`, {
    headers: githubHeaders(token, "application/octet-stream"),
    redirect: "manual",
    signal,
  });
  if (response.status === 302) {
    await response.body?.cancel();
    const location = downloadLocation(response);
    // Only one observed media redirect is supported; never reuse authenticated API headers.
    response = await fetch(location.href, { redirect: "manual", credentials: "omit", signal });
  }
  const contentLength = response.headers.get("Content-Length");
  if (response.status !== 200 || !response.body ||
      (contentLength !== null && (!/^\d+$/.test(contentLength) || Number(contentLength) !== asset.size))) {
    await response.body?.cancel();
    throw new Error("Manifest download failed");
  }
  const bytes = new Uint8Array(asset.size);
  const reader = response.body.getReader();
  let size = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      if (size + value.byteLength > bytes.length) throw new Error("Manifest is too large");
      bytes.set(value, size);
      size += value.byteLength;
    }
  } finally {
    await reader.cancel();
    reader.releaseLock();
  }
  if (size !== asset.size) throw new Error("Manifest size mismatch");
  const manifest = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
  if (!manifest || typeof manifest !== "object" || Array.isArray(manifest) ||
      typeof manifest.commitSha !== "string" || manifest.commitSha.length !== 40 ||
      !/^[a-f0-9]{40}$/i.test(manifest.commitSha) || /^0{40}$/.test(manifest.commitSha) ||
      !Number.isSafeInteger(manifest.commitTimestamp) || manifest.commitTimestamp <= VERSION_EPOCH ||
      !Number.isSafeInteger(manifest.versionCode) || manifest.versionCode <= 0 || manifest.versionCode > MAX_VERSION_CODE ||
      manifest.versionCode !== manifest.commitTimestamp - VERSION_EPOCH ||
      typeof manifest.versionName !== "string" || !manifest.versionName.trim() ||
      manifest.packageName !== PACKAGE_NAME || typeof manifest.sha256 !== "string" ||
      manifest.sha256.toLowerCase() !== metadata.sha256 || manifest.size !== metadata.size) {
    throw new Error("Invalid update manifest");
  }
  return {
    commitSha: manifest.commitSha.toLowerCase(),
    commitTimestamp: manifest.commitTimestamp,
    versionCode: manifest.versionCode,
    versionName: manifest.versionName,
    packageName: manifest.packageName,
  };
}

async function releaseMetadata(tag, token, origin) {
  const cacheKey = new Request(`${origin}/_release-cache/v2/${tag ?? "latest"}`);
  const cache = caches.default;
  const cached = await cache.match(cacheKey);
  if (cached) return cached.json();

  const response = await fetch(`${API}/releases/${tag ? `tags/${tag}` : "latest"}`, {
    headers: githubHeaders(token, "application/vnd.github+json"),
    redirect: "manual",
    signal: AbortSignal.timeout(10_000),
  });
  if (response.status === 404) return null;
  if (!response.ok) {
    console.warn("Release API status", response.status);
    throw new Error("Release lookup failed");
  }
  const release = await response.json();
  if (release.draft !== false || release.prerelease !== false ||
      !RELEASE_TAG.test(release.tag_name) || (tag && release.tag_name !== tag)) return null;
  const apk = release.assets?.find(asset => asset.name === APK_NAME && asset.state === "uploaded");
  if (!apk) return null;
  const digest = /^sha256:([a-f0-9]{64})$/i.exec(apk.digest);
  if (!digest || !Number.isSafeInteger(apk.id) || apk.id <= 0 ||
      !Number.isSafeInteger(apk.size) || apk.size <= 0 ||
      typeof release.published_at !== "string" || !Number.isFinite(Date.parse(release.published_at))) {
    throw new Error("Invalid release metadata");
  }
  // Cache only the public APK metadata, never private release notes or signed download URLs.
  const metadata = {
    release: release.tag_name,
    publishedAt: release.published_at,
    assetId: apk.id,
    sha256: digest[1].toLowerCase(),
    size: apk.size,
  };
  const manifests = release.assets.filter(asset => asset.name === MANIFEST_NAME);
  if (manifests.length > 1) throw new Error("Ambiguous update manifest");
  // Legacy releases have no source metadata; a branch-valued target_commitish proves nothing.
  if (manifests.length === 1) Object.assign(metadata, await readManifest(manifests[0], token, metadata));
  await cache.put(cacheKey, Response.json(metadata, { headers: { "Cache-Control": "public, max-age=60" } }));
  return metadata;
}

async function handle(request, env) {
  const url = new URL(request.url);
  const latest = url.pathname === "/latest.json";
  const download = /^\/releases\/(build-[1-9]\d{0,9})\.apk$/.exec(url.pathname);
  if (url.search || (!latest && !download)) return error(404, "Not found");
  if (request.method !== "GET" && request.method !== "HEAD") {
    const response = error(405, "Only GET and HEAD are supported");
    response.headers.set("Allow", "GET, HEAD");
    return response;
  }
  if (!env.GITHUB_TOKEN) return error(503, "Updates are not configured");
  const range = request.headers.get("Range");
  if (range && (range.length > 80 || !/^bytes=(?:\d+-\d*|-\d+)$/.test(range))) {
    return error(416, "Invalid byte range");
  }
  const release = await releaseMetadata(download?.[1], env.GITHUB_TOKEN, url.origin);
  if (!release) return error(404, "No published APK available");

  if (latest) {
    return Response.json({
      release: release.release,
      publishedAt: release.publishedAt,
      apkUrl: `${url.origin}/releases/${release.release}.apk`,
      sha256: release.sha256,
      size: release.size,
      commitSha: release.commitSha,
      commitTimestamp: release.commitTimestamp,
      versionCode: release.versionCode,
      versionName: release.versionName,
      packageName: release.packageName,
    }, { headers: { "Cache-Control": "public, max-age=60", "X-Content-Type-Options": "nosniff" } });
  }

  const headers = githubHeaders(env.GITHUB_TOKEN, "application/octet-stream");
  if (range) headers.Range = range;
  if (range && request.headers.has("If-Range")) headers["If-Range"] = request.headers.get("If-Range");
  const response = await fetch(`${API}/releases/assets/${release.assetId}`, {
    method: request.method,
    headers,
    redirect: "manual",
  });
  if (response.status === 302) {
    await response.body?.cancel();
    const location = downloadLocation(response);
    return new Response(null, {
      status: 302,
      headers: {
        Location: location.href,
        "Cache-Control": "no-store",
        "Referrer-Policy": "no-referrer",
      },
    });
  }
  if (response.status === 404) return error(404, "APK is no longer available");
  if (response.status === 416) return error(416, "Requested range is not available");
  const contentType = response.headers.get("Content-Type")?.split(";")[0].trim().toLowerCase();
  if (![200, 206].includes(response.status) ||
      !["application/octet-stream", "application/vnd.android.package-archive"].includes(contentType)) {
    await response.body?.cancel();
    throw new Error("APK download failed");
  }
  const outgoing = new Headers({
    "Content-Type": "application/vnd.android.package-archive",
    "Content-Disposition": `attachment; filename="manticord-${release.release}.apk"`,
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
  });
  for (const name of ["Content-Length", "Content-Range", "Accept-Ranges", "ETag", "Last-Modified"]) {
    if (response.headers.has(name)) outgoing.set(name, response.headers.get(name));
  }
  // Do not buffer APKs or attach a short timeout to a potentially long-running download.
  return new Response(response.body, { status: response.status, headers: outgoing });
}

export default {
  async fetch(request, env) {
    let response;
    try {
      response = await handle(request, env);
    } catch (cause) {
      // Upstream errors can contain private URLs or credentials; do not relay or log them.
      const kind = ["TypeError", "SyntaxError", "TimeoutError", "AbortError"].includes(cause?.name) ? cause.name : "Error";
      console.warn("Update request failed", kind);
      response = error(503, "Updates are temporarily unavailable");
    }
    if (request.method === "HEAD") {
      await response.body?.cancel();
      return new Response(null, { status: response.status, headers: response.headers });
    }
    return response;
  },
};
