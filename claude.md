# DEX — Dependency Explorer

## Workflow Rules

- **Never commit without explicit approval.** After making changes, describe what was done and let the user verify before committing. Only commit when the user says "commit".

Interactive browser-based tool for exploring JVM dependency graphs. Resolves dependencies from `deps.edn` (via tools.deps) or `project.clj` (via leiningen-core APIs), then renders a three-column graph with navigable artifact boxes and SVG connection arrows.

## Architecture

**Server (Clojure):** Hyper framework (`dynamic-alpha/hyper`) provides server-side rendering with live updates via SSE. Datastar on the client handles DOM morphing and reactivity via `data-*` attributes.

**Client (JS):** A single ES module (`resources/public/js/main.js`) with three Datastar `attribute` plugins: `data-draw-arrows` (SVG arrows + FLIP animation), `data-track-height` (viewport sizing), and `data-accel` (keyboard shortcuts).

**CSS:** Tailwind CSS v4 — source at `public/style.css`, built via `bb tailwind` into `generated-resources/public/style.css`.

## Namespace Guide

| Namespace | Role |
|---|---|
| `dex.deps` | Core data model — `build-db` indexes artifacts with labels, dependants, and label search index |
| `dex.deps-reader` | Reads `deps.edn` via `tools.deps`, walks trace tree to produce flat artifact map |
| `dex.lein-reader` | Reads `project.clj` via leiningen-core APIs, resolves per-artifact deps via Aether |
| `dex.layout` | Computes three-column layout: windowed columns, connection graph, version match colors, box annotations |
| `dex.views` | Hiccup rendering — toolbar, columns, boxes, search, arrow connection JSON |
| `dex.service` | HTTP lifecycle — routes, Hyper handler, `start!`/`stop!`; db passed as option, seeded into Hyper app-state |
| `dex.main` | CLI entry point — auto-detects project type, resolves deps, launches server + browser |

## Key Data Structures

**Artifact map** (input to `deps/build-db`):
```clojure
{artifact-key -> {:version string, :label string?, :deps {artifact-key -> {:version string}}}}
```
Special key `'ROOT` represents the project itself.

**Database** (output of `deps/build-db`):
```clojure
{:artifacts  {key -> {:version :label :deps ...}}  ; :label guaranteed on all
 :dependants {key -> [{:from key :requested-version v}]}
 :by-label   {lowercase-label -> key}}             ; search index
```

**View cursor** (Hyper tab-cursor `:view`):
```clojure
{:selected artifact-key, :left-offset int, :right-offset int,
 :nav-history [{:selected key :left-offset n :right-offset n} ...],  ; stack (vector, peek/pop)
 :hidden-libs #{...}, :max-visible int?}
```

**Layout** (output of `layout/compute-layout`):
```clojure
{:selected-box box-descriptor
 :left  {:boxes [...] :before n :after n}  ; windowed dependants column
 :right {:boxes [...] :before n :after n}  ; windowed dependencies column
 :connections [{:from :to :from-id :to-id :color :connection-type ...}]}
```

## Conventions

- **Data flow:** The db value is passed to `service/start!` as `:db`, seeded into Hyper's app-state atom, and extracted in views via `(:db @(:hyper/app-state req))`. Views receive db as a parameter — they never access a global atom.
- **Navigation:** All artifact selection changes (box clicks, search, home) go through `navigate!` which pushes current state onto `:nav-history` before switching. `navigate-back!` pops the stack and restores `{:selected :left-offset :right-offset}`.
- **Column scrolling:** Mouse wheel scrolling on columns uses Datastar's built-in `data-on:wheel` with `$scroll-delta-y` (a custom Hyper client param) and `scroll-offset` for bounds-clamped offset updates.
- **Rendering:** Hiccup vectors with Tailwind utility classes. Use `h/action` for server-side actions triggered by Datastar `data-on:*` attributes.
- **Client params:** `$value`, `$key`, `$form-data`, `$scroll-delta-y` etc. are Hyper macros that extract DOM values client-side and send them to the server action. They appear as unresolvable symbols in the editor — this is expected. Custom params (like `$scroll-delta-y`) are added via `alter-var-root` on `h/client-param-registry` at the top of `views.clj`.
- **Idiomorph caveat:** Datastar's DOM morpher preserves focused input values. Use `el.value = ''; el.blur()` appended to action expressions when inputs need clearing.
- **SVG arrows:** Drawn client-side via `data-draw-arrows` plugin. Connection data (including version-match colors) is serialized to JSON by the server.
- **FLIP animation:** Box transitions use the Web Animations API. The `data-draw-arrows` plugin's `apply()` callback serves as the morph signal — no MutationObserver needed.
- **Version compatibility:** Classified by `layout/version-match` using `version-clj`: exact (black), compatible (green), incompatible (red), unknown/git-sha (yellow).

## Running

```bash
# REPL (dev) — see dev/demo.clj for load + start examples
clojure -M:dev

# CLI
clojure -M:run           # auto-detects deps.edn in current dir
clojure -M:run -f /path/to/project.clj -a dev

# Tailwind (separate terminal)
bb tailwind

# Tests
bb test
```

**REPL workflow:** Load dependency data into the local `*db` atom in `demo.clj`, then call `(service/start! {:db @*db})`. The db value is seeded into Hyper's app-state and flows to views via the request — views never access a global atom directly.

## Keyboard Shortcuts

- **⌘F** / **Ctrl+F** — Focus artifact search field
- **⌘H** / **Ctrl+H** — Navigate to root node
- **⌘B** / **Ctrl+B** — Navigate back (undo last navigation)

## Future Work

### Leiningen plugin for dependency collection

The current lein-reader uses leiningen-core APIs in-process (project/read, classpath/get-dependencies) and performs per-artifact Aether resolution to discover immediate children (since Aether's hierarchy tree prunes already-resolved subtrees). This works but has two drawbacks:

1. **Per-artifact resolution is slow** — each of the N resolved artifacts requires a separate Aether call.
2. **Plugin/middleware compatibility** — leiningen-core's `project/read` calls `init-project` which loads plugins and applies middleware (e.g., `managed-dependencies`, `plug-n-play`). This works for most projects but can fail when plugins require private Maven repos or have classloader assumptions that don't hold when leiningen-core runs embedded in a different host application.

A more robust approach: create a small **Leiningen plugin** (a jar on the classpath or injected via `:plugins`) that dex launches as a subprocess via `lein`. The plugin would:

1. Run inside a normal `lein` invocation, so all project middleware (managed-dependencies, etc.) has already been applied to the project map.
2. Use the vizdeps approach: call `classpath/get-dependencies` for the whole project to get the resolved version map, then call it per-artifact to discover each artifact's true immediate children.
3. Write the flat artifact map as EDN to stdout (or a temp file).

Dex would then launch `lein run-plugin` (or similar) as a subprocess, read the EDN output, and pass it to `deps/build-db` as usual. This cleanly separates Leiningen's classloader world from dex's, and guarantees that all plugins and middleware run in their expected environment.
