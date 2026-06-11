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
        if (path.endsWith("/v1/search")) {
            return handleSearch(request, env);
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

async function handleSearch(request, env) {
    let body;
    try {
        body = await request.json();
    } catch {
        return json({ error: "Invalid JSON body" }, 400);
    }

    const query = `${body.query || ""}`.trim().slice(0, 300);
    if (!query) {
        return json({ error: "query required" }, 400);
    }

    const results = [];

    // Live weather via wttr.in (free, no key) when the query is weather-related.
    if (/\bweather\b|\bforecast\b|\btemperature\b/i.test(query)) {
        try {
            const loc = query.replace(/.*\b(weather|forecast|temperature)\b/i, "")
                .replace(/^\s*(in|for|at|of|today|tomorrow|now)\b/gi, "")
                .replace(/\b(today|tomorrow|right now|now)\b/gi, "")
                .trim();
            const wx = await fetch(`https://wttr.in/${encodeURIComponent(loc)}?format=j1`, {
                headers: { accept: "application/json", "user-agent": "curl/8" }
            });
            if (wx.ok) {
                const j = await wx.json();
                const cur = j?.current_condition?.[0];
                if (cur) {
                    const place = j?.nearest_area?.[0]?.areaName?.[0]?.value || (loc || "your area");
                    const desc = cur?.weatherDesc?.[0]?.value || "";
                    results.push({
                        title: `Weather in ${place}`,
                        snippet: `${desc}, ${cur.temp_C}°C (feels like ${cur.FeelsLikeC}°C), humidity ${cur.humidity}%, wind ${cur.windspeedKmph} km/h.`,
                        url: `https://wttr.in/${encodeURIComponent(loc)}`,
                        source: "wttr.in"
                    });
                }
            }
        } catch {}
    }

    // DuckDuckGo Instant Answer API (fetched server-side; the worker has reliable
    // internet even when the user's network blocks these domains directly).
    try {
        const ddgUrl = `https://api.duckduckgo.com/?q=${encodeURIComponent(query)}&format=json&no_html=1&skip_disambig=1`;
        const ddg = await fetch(ddgUrl, { headers: { accept: "application/json" } });
        if (ddg.ok) {
            const d = await ddg.json();
            if (d.AbstractText) {
                results.push({ title: d.Heading || query, snippet: d.AbstractText, url: d.AbstractURL || "", source: "DuckDuckGo" });
            }
            if (d.Answer) {
                results.push({ title: "Instant answer", snippet: `${d.Answer}`, url: "", source: "DuckDuckGo" });
            }
            for (const t of (d.RelatedTopics || []).slice(0, 6)) {
                if (t && t.Text) {
                    results.push({ title: (t.Text.split(" - ")[0] || "").slice(0, 80), snippet: t.Text, url: t.FirstURL || "", source: "DuckDuckGo" });
                }
            }
        }
    } catch {}

    // Wikipedia summary when DuckDuckGo is sparse.
    if (results.length < 2) {
        try {
            const wikiUrl = `https://en.wikipedia.org/api/rest_v1/page/summary/${encodeURIComponent(query.replace(/\s+/g, "_"))}`;
            const wiki = await fetch(wikiUrl, { headers: { accept: "application/json", "user-agent": "DoraPilot/1.0 (web search)" } });
            if (wiki.ok) {
                const w = await wiki.json();
                if (w.extract) {
                    results.push({ title: w.title || query, snippet: w.extract, url: w?.content_urls?.desktop?.page || "", source: "Wikipedia" });
                }
            }
        } catch {}
    }

    // Ground a concise answer on the fetched snippets (avoids hallucination).
    let answer = "";
    const weather = results.find((r) => r.source === "wttr.in");
    if (weather) {
        // The weather snippet is already a clean, direct answer - no synthesis needed.
        answer = `${weather.title}: ${weather.snippet}`;
    } else if (results.length > 0) {
        const context = results.slice(0, 4)
            .map((r) => `[${r.source}] ${r.title}: ${r.snippet}`)
            .join("\n")
            .slice(0, MAX_CONTENT_CHARS);
        try {
            const synth = await env.AI.run(DEFAULT_MODEL, {
                messages: [
                    { role: "system", content: "Answer the user's query in 1-3 sentences. Use the provided web snippets when relevant; if they lack the specific answer, answer from your own knowledge. Be direct and concise. Do NOT mention the snippets or your sources, never say you couldn't find it, and never ask whether to search." },
                    { role: "user", content: `Query: ${query}\n\nWeb snippets:\n${context}` }
                ],
                temperature: 0.2,
                max_tokens: 300
            });
            answer = `${synth?.response || ""}`.trim();
        } catch {}
    }

    return json({
        ok: true,
        query,
        answer,
        results: results.slice(0, 6)
    });
}

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
