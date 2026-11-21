import { route } from "./_lib/panel.js";

export async function onRequest({ request, env, params }) {
  const path = Array.isArray(params.path) ? params.path.join("/") : params.path || "";
  return route(request, env, `api/${path}`);
}
