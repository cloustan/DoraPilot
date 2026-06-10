// Dora Pilot Clou Engine
// OpenAI-compatible chat proxy in front of Workers AI.
// ZDR posture: nothing is logged or stored; requests live only in memory.

const DEFAULT_MODEL = "@cf/meta/llama-3.3-70b-instruct-fp8-fast";
const TTS_MODEL = "@cf/myshell-ai/melotts";
const MAX_MESSAGES = 24;
const MAX_CONTENT_CHARS = 4000;
const MAX_TTS_CHARS = 900;

export default {
    async fetch(request, env) {
        if (request.method !== "POST") {
            return json({ error: "POST only" }, 405);
        }

        const auth = request.headers.get("Authorization") || "";
        const token = auth.startsWith("Bearer ") ? auth.slice(7).trim() : "";
        if (!env.CLIENT_KEY || token !== env.CLIENT_KEY) {
            return json({ error: "Unauthorized" }, 401);
        }

        const path = new URL(request.url).pathname;
        if (path.endsWith("/v1/tts")) {
            return handleTts(request, env);
        }

        let body;
        try {
            body = await request.json();
        } catch {
            return json({ error: "Invalid JSON body" }, 400);
        }

        const messages = sanitizeMessages(body.messages);
        if (messages.length === 0) {
            return json({ error: "messages required" }, 400);
        }

        const model = normalizeModel(body.model);
        const temperature = clampNumber(body.temperature, 0, 2, 0.2);

        let result;
        try {
            result = await env.AI.run(model, {
                messages,
                temperature,
                max_tokens: 512
            });
        } catch (error) {
            return json({ error: `Inference failed: ${error.message || "unknown"}` }, 502);
        }

        // OpenAI-compatible response shape for the app's MainBackendClient parser.
        return json({
            id: `clou-${crypto.randomUUID()}`,
            object: "chat.completion",
            created: Math.floor(Date.now() / 1000),
            model,
            choices: [
                {
                    index: 0,
                    message: { role: "assistant", content: result?.response ?? "" },
                    finish_reason: "stop"
                }
            ]
        });
    }
};

async function handleTts(request, env) {
    let body;
    try {
        body = await request.json();
    } catch {
        return json({ error: "Invalid JSON body" }, 400);
    }

    const text = `${body.text || body.prompt || ""}`.trim().slice(0, MAX_TTS_CHARS);
    if (!text) {
        return json({ error: "text required" }, 400);
    }

    let result;
    try {
        result = await env.AI.run(TTS_MODEL, {
            prompt: text,
            lang: "en"
        });
    } catch (error) {
        return json({ error: `TTS failed: ${error.message || "unknown"}` }, 502);
    }

    const audio = `${result?.audio || ""}`.replace(/^data:audio\/[^;]+;base64,/, "");
    if (!audio) {
        return json({ error: "TTS returned empty audio" }, 502);
    }

    return new Response(base64ToBytes(audio), {
        headers: {
            "content-type": "audio/wav",
            "cache-control": "no-store"
        }
    });
}

function sanitizeMessages(raw) {
    if (!Array.isArray(raw)) return [];
    const allowedRoles = new Set(["system", "user", "assistant"]);
    const cleaned = [];
    for (const item of raw.slice(-MAX_MESSAGES)) {
        if (!item || typeof item !== "object") continue;
        const role = `${item.role || ""}`.trim();
        const content = `${item.content ?? ""}`.trim();
        if (!allowedRoles.has(role) || !content) continue;
        cleaned.push({ role, content: content.slice(0, MAX_CONTENT_CHARS) });
    }
    return cleaned;
}

function normalizeModel(raw) {
    const value = `${raw || ""}`.trim();
    if (!value) return DEFAULT_MODEL;
    const stripped = value.startsWith("workers-ai/") ? value.slice("workers-ai/".length) : value;
    return stripped.startsWith("@cf/") ? stripped : DEFAULT_MODEL;
}

function clampNumber(value, min, max, fallback) {
    const num = Number(value);
    if (!Number.isFinite(num)) return fallback;
    return Math.min(max, Math.max(min, num));
}

function json(data, status = 200) {
    return new Response(JSON.stringify(data), {
        status,
        headers: { "content-type": "application/json" }
    });
}

function base64ToBytes(value) {
    const binary = atob(value);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i += 1) {
        bytes[i] = binary.charCodeAt(i);
    }
    return bytes;
}
