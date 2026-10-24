/** Minimal server-rendered HTML for the Admin Console. No scripts, no external resources. */

export function esc(value: unknown): string {
  return String(value ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

const STYLE = `
  :root { color-scheme: light dark; --fg:#1a1c1f; --bg:#fafafc; --muted:#5b5f67; --line:#d9dce3; --accent:#1f3a5f; --danger:#b3261e; }
  @media (prefers-color-scheme: dark) { :root { --fg:#e3e2e6; --bg:#121316; --muted:#a8abb3; --line:#33363d; --accent:#a9c7f0; --danger:#f2b8b5; } }
  * { box-sizing: border-box; }
  body { font: 15px/1.5 system-ui, sans-serif; color: var(--fg); background: var(--bg); margin: 0; }
  header { border-bottom: 1px solid var(--line); padding: 12px 24px; display: flex; gap: 20px; align-items: center; flex-wrap: wrap; }
  header strong { margin-right: auto; }
  header a { color: var(--accent); text-decoration: none; }
  main { max-width: 960px; margin: 0 auto; padding: 24px; }
  table { width: 100%; border-collapse: collapse; margin: 12px 0 24px; }
  th, td { text-align: left; padding: 8px; border-bottom: 1px solid var(--line); vertical-align: top; }
  th { color: var(--muted); font-weight: 500; }
  form.inline { display: inline; }
  button { font: inherit; padding: 6px 12px; border-radius: 6px; border: 1px solid var(--line); background: transparent; color: var(--fg); cursor: pointer; }
  button.primary { background: var(--accent); color: var(--bg); border-color: var(--accent); }
  button.danger { color: var(--danger); border-color: var(--danger); }
  input { font: inherit; padding: 8px; border-radius: 6px; border: 1px solid var(--line); background: transparent; color: var(--fg); width: 100%; }
  label { display: block; margin: 12px 0 4px; color: var(--muted); }
  .card { border: 1px solid var(--line); border-radius: 10px; padding: 20px; margin: 16px 0; }
  .muted { color: var(--muted); }
  .error { color: var(--danger); }
  code.secret { font-size: 20px; letter-spacing: 1px; padding: 8px 12px; border: 1px dashed var(--line); border-radius: 6px; display: inline-block; user-select: all; }
`;

export function page(title: string, body: string, nav?: { csrf: string; username: string }): string {
  const header = nav
    ? `<header><strong>Vault Chat Admin</strong>
        <a href="/admin/users">Users</a><a href="/admin/health">Health</a><a href="/admin/audit">Audit log</a>
        <span class="muted">${esc(nav.username)}</span>
        <form class="inline" method="post" action="/admin/logout"><input type="hidden" name="csrf" value="${esc(nav.csrf)}"><button>Log out</button></form>
      </header>`
    : `<header><strong>Vault Chat Admin</strong></header>`;
  return `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="no-referrer">
<title>${esc(title)} · Vault Chat Admin</title><style>${STYLE}</style></head>
<body>${header}<main>${body}</main></body></html>`;
}

export function csrfField(csrf: string): string {
  return `<input type="hidden" name="csrf" value="${esc(csrf)}">`;
}

export function fmt(date: Date | null | undefined): string {
  if (!date) return "—";
  return `${date.toISOString().slice(0, 16).replace("T", " ")} UTC`;
}
