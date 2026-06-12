// dora-pilot-model-registry
//
// Serves on-device model bundles from R2 to the Dora Pilot app.
//
//   GET  /v1/models                          -> manifest of available models
//   GET  /v1/models/<model_id>/<file>        -> file download (Range supported)
//   HEAD /v1/models/<model_id>/<file>        -> file metadata
//
// Publishing (requires Authorization: Bearer <ADMIN_TOKEN>):
//   POST /v1/admin/mpu/create?key=<key>                          -> { uploadId }
//   PUT  /v1/admin/mpu/part?key=<key>&uploadId=..&partNumber=N   -> { etag }
//   POST /v1/admin/mpu/complete?key=<key>&uploadId=..            -> { ok }
//        body: JSON array of { partNumber, etag }

const JSON_HEADERS = { "content-type": "application/json" };

function json(body, status = 200, extraHeaders = {}) {
    return new Response(JSON.stringify(body), {
        status,
        headers: { ...JSON_HEADERS, ...extraHeaders },
    });
}

function isAuthorized(request, env) {
    const token = (env.ADMIN_TOKEN || "").trim();
    if (!token) return false;
    const header = request.headers.get("authorization") || "";
    return header === `Bearer ${token}`;
}

async function handleManifest(env) {
    const models = new Map();
    let cursor;
    do {
        const page = await env.MODELS.list({ cursor, include: ["httpMetadata"] });
        for (const obj of page.objects) {
            const slash = obj.key.indexOf("/");
            if (slash <= 0) continue;
            const modelId = obj.key.slice(0, slash);
            const fileName = obj.key.slice(slash + 1);
            if (!fileName) continue;
            if (!models.has(modelId)) models.set(modelId, []);
            models.get(modelId).push({
                name: fileName,
                size: obj.size,
                etag: obj.etag,
            });
        }
        cursor = page.truncated ? page.cursor : undefined;
    } while (cursor);

    const out = [];
    for (const [id, allFiles] of models) {
        // _meta.json carries catalog metadata (display_name, params,
        // min_ram_gb, description); it is not part of the model bundle.
        const files = allFiles.filter((f) => f.name !== "_meta.json");
        files.sort((a, b) => a.name.localeCompare(b.name));
        let meta = {};
        if (allFiles.length !== files.length) {
            try {
                const obj = await env.MODELS.get(`${id}/_meta.json`);
                if (obj) meta = await obj.json();
            } catch (_e) {}
        }
        out.push({
            ...meta,
            id,
            total_size: files.reduce((sum, f) => sum + f.size, 0),
            files,
        });
    }
    return json({ ok: true, models: out });
}

async function handleDownload(request, env, key) {
    const isHead = request.method === "HEAD";
    const object = isHead
        ? await env.MODELS.head(key)
        : await env.MODELS.get(key, { range: request.headers });

    if (object === null) {
        return json({ ok: false, error: `not found: ${key}` }, 404);
    }

    const headers = new Headers();
    object.writeHttpMetadata(headers);
    headers.set("etag", object.httpEtag);
    headers.set("accept-ranges", "bytes");
    if (!headers.has("content-type")) {
        headers.set("content-type", "application/octet-stream");
    }

    if (isHead) {
        headers.set("content-length", String(object.size));
        return new Response(null, { status: 200, headers });
    }

    if (object.range) {
        const offset = object.range.offset ?? 0;
        const length = object.range.length ?? object.size - offset;
        headers.set("content-length", String(length));
        headers.set(
            "content-range",
            `bytes ${offset}-${offset + length - 1}/${object.size}`
        );
        return new Response(object.body, { status: 206, headers });
    }

    headers.set("content-length", String(object.size));
    return new Response(object.body, { status: 200, headers });
}

async function handleAdmin(request, env, url) {
    if (!isAuthorized(request, env)) {
        return json({ ok: false, error: "unauthorized" }, 401);
    }
    const key = url.searchParams.get("key") || "";
    if (!key || key.includes("..")) {
        return json({ ok: false, error: "invalid key" }, 400);
    }

    if (url.pathname === "/v1/admin/mpu/create" && request.method === "POST") {
        const upload = await env.MODELS.createMultipartUpload(key);
        return json({ ok: true, uploadId: upload.uploadId });
    }

    if (url.pathname === "/v1/admin/mpu/part" && request.method === "PUT") {
        const uploadId = url.searchParams.get("uploadId");
        const partNumber = Number(url.searchParams.get("partNumber"));
        if (!uploadId || !Number.isInteger(partNumber) || partNumber < 1) {
            return json({ ok: false, error: "uploadId and partNumber required" }, 400);
        }
        const upload = env.MODELS.resumeMultipartUpload(key, uploadId);
        const part = await upload.uploadPart(partNumber, request.body);
        return json({ ok: true, etag: part.etag, partNumber });
    }

    if (url.pathname === "/v1/admin/mpu/complete" && request.method === "POST") {
        const uploadId = url.searchParams.get("uploadId");
        if (!uploadId) {
            return json({ ok: false, error: "uploadId required" }, 400);
        }
        const parts = await request.json();
        const upload = env.MODELS.resumeMultipartUpload(key, uploadId);
        const object = await upload.complete(parts);
        return json({ ok: true, etag: object.httpEtag, size: object.size });
    }

    return json({ ok: false, error: "unknown admin endpoint" }, 404);
}

export default {
    async fetch(request, env) {
        const url = new URL(request.url);

        if (url.pathname.startsWith("/v1/admin/")) {
            return handleAdmin(request, env, url);
        }

        if (request.method !== "GET" && request.method !== "HEAD") {
            return json({ ok: false, error: "method not allowed" }, 405);
        }

        if (url.pathname === "/v1/models") {
            return handleManifest(env);
        }

        const match = url.pathname.match(/^\/v1\/models\/([^/]+)\/(.+)$/);
        if (match) {
            const key = `${decodeURIComponent(match[1])}/${decodeURIComponent(match[2])}`;
            if (key.includes("..")) {
                return json({ ok: false, error: "invalid key" }, 400);
            }
            return handleDownload(request, env, key);
        }

        return json({ ok: false, error: "not found" }, 404);
    },
};
