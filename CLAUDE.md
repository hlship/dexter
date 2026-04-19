# DEX — Dependency Explorer

## Workflow Rules

- **Never commit without explicit approval.** After making changes, describe what was done and let the user verify before committing. Only commit when the user says "commit".

Interactive browser-based tool for exploring JVM dependency graphs. Resolves dependencies from `deps.edn` (via tools.deps) or `project.clj` (via leiningen-core APIs), then renders a three-column graph with navigable artifact boxes and SVG connection arrows.

## Architecture

**Server (Clojure):** Hyper framework (`dynamic-alpha/hyper`) provides server-side rendering with live updates via SSE. Datastar on the client handles DOM morphing and reactivity via `data-*` attributes.

**Client (JS):** A single ES module (`resources/public/js/main.js`) with four Datastar `attribute` plugins: `data-draw-arrows` (SVG arrows + FLIP animation), `data-track-height` (viewport sizing), `data-arrow-nav` (arrow-key navigation for focusable lists), and `data-accel` (keyboard shortcuts). Also includes a `datastar-fetch` event listener for server disconnect detection (see below).

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

**View cursor** (Hyper tab-cursor `:view`) — supports multiple UI tabs, each with independent navigation:
```clojure
{:tabs        [{:id 0 :root 'ROOT :label "Project Root"} ...]  ; tab definitions
 :active-tab  0                                                  ; id of selected tab
 :next-id     1                                                  ; counter for new tab ids
 :tab-history [0 ...]                                            ; tab ids ordered by recency (most recent last)
 :views       {0 {:selected 'ROOT                               ; per-tab view state
                   :left-offset 0
                   :right-offset 0
                   :nav-history [{:selected key :left-offset n :right-offset n} ...]}}
 :hidden-libs #{...}                                             ; shared across all tabs
 :max-visible int?                                               ; shared (viewport-level)
 :footer-popup nil}                                              ; :compatible, :incompatible, :unknown, or nil
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
- **Tabs:** Multiple UI tabs allow independent views into the dependency tree. The ROOT tab (id 0) can't be closed. `open-tab!` creates a new tab rooted at an artifact; `close-tab!` removes one and selects the most recently viewed; `select-tab!` switches between tabs. Artifact boxes show a ⊕ icon to open a new tab unless the artifact already has one. `tab-root-set` returns the set of artifact keys that have tabs.
- **Navigation:** All artifact selection changes (box clicks, search, home) go through `navigate!` which pushes current state onto the active tab's `:nav-history` before switching. `navigate-back!` pops the stack and restores `{:selected :left-offset :right-offset}`. The home button navigates to the root artifact of the current tab (via `active-tab-root`), not necessarily `'ROOT`.
- **Column scrolling:** Mouse wheel scrolling on columns uses Datastar's built-in `data-on:wheel` with `$scroll-delta-y` (a custom Hyper client param) and `scroll-offset` for bounds-clamped offset updates.
- **Rendering:** Hiccup vectors with Tailwind utility classes. Use `h/action` for server-side actions triggered by Datastar `data-on:*` attributes.
- **Client params:** `$value`, `$key`, `$form-data`, `$scroll-delta-y` etc. are Hyper client params that extract DOM values client-side and send them to the server action. They appear as unresolvable symbols in the editor — this is expected. Custom params (like `$scroll-delta-y`) are defined via the `hyper.client-params/client-param` multimethod at the top of `views.clj`.
- **Action `:when` guards:** `(h/action {:when "js-expr"} ...)` emits a client-side guard that skips the `@post()` when the JS expression is falsy. Useful for key filtering, but note that the guard only gates the POST — any code appended via `str` after the action will still execute unconditionally. When `preventDefault()` or other side-effects must share the same guard, use an explicit JS `if` block wrapping both the action and side-effects.
- **Signals:** Hyper supports `signal` and `local-signal` for reactive Datastar signals with two-way binding. Not yet used in Dexter but available for future UI state management.
- **Idiomorph caveat:** Datastar's DOM morpher preserves focused input values. Use `el.value = ''; el.blur()` appended to action expressions when inputs need clearing.
- **SVG arrows:** Drawn client-side via `data-draw-arrows` plugin. Connection data (including version-match colors) is serialized to JSON by the server.
- **FLIP animation:** Box transitions use the Web Animations API. The `data-draw-arrows` plugin's `apply()` callback serves as the morph signal — no MutationObserver needed.
- **Version compatibility:** Classified by `layout/version-match` using `version-clj`: exact (black), compatible (green), incompatible (red), unknown/git-sha (yellow).
- **Footer category popups:** The footer's colored indicators (compatible/incompatible/unknown) are clickable. Clicking one sets `:footer-popup` in the view cursor, which triggers a server-rendered DaisyUI modal listing all artifacts in that category (via `layout/artifacts-by-match`). The popup includes a search/filter field (when >8 items), scrollable list, and closes on backdrop click, ✕ button, or Escape key. Clicking an artifact navigates to it and closes the popup.
- **Server disconnect modal:** A DaisyUI modal (`#disconnect-modal` inside `#modal-container`) is rendered hidden in the page by `home-page`. The container has `data-ignore-morph` so Datastar's DOM morph won't revert JS changes. Client-side JS listens for `datastar-fetch` custom events; on `"retrying"` or `"retries-failed"` it adds `modal-open` to show "You may close this window now."
- **Modal suppression:** The `data-accel` plugin checks `.modal.modal-open` to suppress keyboard shortcuts when any modal is visible (both server-rendered popups and client-side modals).

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

## Release & Startup Optimization

**Packaging:** `bb package` builds the uberjar, launcher script, and release zip (including `aot-training/deps.edn`). `bb release` tags a GitHub release and generates the Homebrew formula from `release/templates/dexter.rb`.

**JDK 25+ AOT cache (Project Leyden):** The launcher script (`release/templates/dexter`) supports `--aot-train` to create a JDK 25+ AOT cache (JEP 483, JEP 514, JEP 515) for faster startup. The cache filename includes a hash of `java -version` output (e.g. `dexter-a1b2c3d4e5f6.aot`), so switching JDKs silently bypasses a stale cache — no errors, no manual cleanup. Running `--aot-train` again cleans up old caches and creates a fresh one.

**AOT training mechanism:** The `--aot-train` flag passes `-Ddexter.dry-run=true` as a JVM system property and `--file aot-training/` (a bundled copy of dexter's own `deps.edn`). The `dexter.dry-run` property triggers a dry-run mode in `main.clj`: the server starts, several HTTP requests exercise the rendering pipeline (page loads, static resources), then the server stops and the JVM exits cleanly — writing the AOT cache on shutdown.

**Homebrew formula:** The `post_install` block runs `dexter --aot-train` so every Homebrew install gets a pre-trained cache. Wrapped in `rescue` so older JDKs don't fail the install.

**Lazy loading is critical for startup:** `main.clj` uses `requiring-resolve` for heavy namespaces (`service`, `browse`, `deps-reader`, `lein-reader`). This keeps the `--help` path fast (~350ms) by deferring the web stack (Hyper, Ring, Reitit, Cheshire — ~150 namespaces) and dep resolution (tools.deps, leiningen-core) until actually needed. Replacing these with static requires would pull everything in eagerly and roughly 4x the startup time.

## Keyboard Shortcuts

- **⌘F** / **Ctrl+F** — Focus artifact search field
- **⌘H** / **Ctrl+H** — Navigate to root node
- **⌘B** / **Ctrl+B** — Navigate back (undo last navigation)
- **⌘1** / **Ctrl+1** — Open compatible dependencies popup
- **⌘2** / **Ctrl+2** — Open incompatible dependencies popup
- **⌘3** / **Ctrl+3** — Open unknown dependencies popup
- **Escape** — Close open popup
- **Enter** (in popup filter) — Navigate to first matching artifact

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
