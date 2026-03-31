# Agents Guide

## Commands
- **Tests:** `bb test` (all) · `clojure -M:test -v net.lewisship.dex.deps-test` (single ns)
- **Lint:** `bb lint` (runs clj-kondo on src/test/dev)
- **Tailwind:** `bb tailwind` (watches `public/style.css` → `generated-resources/public/style.css`)
- **REPL:** `clojure -M:dev` then load data into `*db` and `(service/start! {:db @*db})` — use `:reload` with `require` to pick up changes

## Code Style
- **Clojure 1.12**, deps.edn project, Babashka for tasks
- Namespaces under `net.lewisship.dex.*`; tests mirror at `net.lewisship.dex.*-test`
- Private helpers use `defn-`; public fns get docstrings
- Destructuring preferred: `{:keys [version label deps]}`
- `reduce-kv` over `reduce` when iterating maps; `(fnil conj [])` for accumulation
- Hiccup vectors with Tailwind utility classes for all rendering (`dex.views`)
- `$value`, `$key`, `$form-data`, `$scroll-delta-y` are Hyper client-side macros — unresolved-symbol warnings expected
- Server actions via `(h/action ...)` wired to Datastar `data-on:*` attributes
- Tests use `clojure.test` (`deftest`, `is`, `testing`); test data as `^:private` defs
- Keep `claude.md` as the canonical architecture reference; update it when adding namespaces or data structures

## Key Files
| Path | Role |
|---|---|
| `src/net/lewisship/dex/views.clj` | Hiccup UI — toolbar, columns, boxes, search |
| `src/net/lewisship/dex/layout.clj` | Three-column layout computation, windowing, version colors |
| `src/net/lewisship/dex/service.clj` | HTTP routes, Hyper handler, `start!`/`stop!` — db passed as option, seeded into Hyper app-state |
| `resources/public/js/main.js` | Client-side Datastar plugins (arrows, viewport, keyboard) |
| `public/style.css` | Tailwind source (built by `bb tailwind`) |
