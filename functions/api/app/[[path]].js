const json = (value, status = 200) => new Response(JSON.stringify(value), {
  status,
  headers: {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "public, max-age=60, stale-while-revalidate=300",
    "X-Content-Type-Options": "nosniff",
  },
});

/**
 * Read-only app API for the managed Android distribution.
 * Values are configured as public JSON variables in the Cloudflare Pages project.
 */
export async function onRequest({ request, env, params }) {
  if (request.method !== "GET") {
    return new Response(null, { status: 405, headers: { Allow: "GET" } });
  }

  const path = Array.isArray(params.path) ? params.path.join("/") : params.path || "";
  try {
    if (path === "config") {
      return env.APP_CONFIG_JSON
        ? json(JSON.parse(env.APP_CONFIG_JSON))
        : new Response(null, { status: 404 });
    }

    if (path === "messages") {
      return json(env.APP_MESSAGES_JSON ? JSON.parse(env.APP_MESSAGES_JSON) : { messages: [] });
    }

    const releaseMatch = path.match(/^releases\/(latest|[1-9][0-9]*)$/);
    if (releaseMatch) {
      if (!env.APP_RELEASES_JSON) return new Response(null, { status: 404 });
      const releases = JSON.parse(env.APP_RELEASES_JSON);
      const release = releaseMatch[1] === "latest"
        ? releases.latest
        : releases.versions?.[releaseMatch[1]];
      return release ? json(release) : new Response(null, { status: 404 });
    }
  } catch {
    return json({ error: "Invalid API configuration" }, 500);
  }

  return json({ error: "Not found" }, 404);
}
