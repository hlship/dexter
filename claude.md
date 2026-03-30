# DEX — Dependency Explorer

## Workflow Rules

- **Never commit without explicit approval.** After making changes, describe what was done and let the user verify before committing. Only commit when the user says "commit".

Interactive browser-based tool for exploring JVM dependency graphs. Resolves dependencies from `deps.edn` (via tools.deps) or `project.clj` (via `lein deps :tree-data`), then renders a three-column graph with navigable artifact boxes and SVG connection arrows.

## Architecture

**Server (Clojure):** Hyper framework (`dynamic-alpha/hyper`) provides server-side rendering with live updates via SSE. Datastar on the client handles DOM morphing and reactivity via `data-*` attributes.

**Client (JS):** A single ES module (`resources/public/js/main.js`) with three Datastar `attribute` plugins: `data-draw-arrows` (SVG arrows + FLIP animation), `data-track-height` (viewport sizing), and `data-accel` (keyboard shortcuts).

**CSS:** Tailwind CSS v4 — source at `public/style.css`, built via `bb tailwind` into `generated-resources/public/style.css`.

## Namespace Guide

| Namespace | Role |
|---|---|
| `dex.deps` | Core data model — `build-db` indexes artifacts with labels, dependants, and label search index |
| `dex.deps-reader` | Reads `deps.edn` via `tools.deps`, walks trace tree to produce flat artifact map |
| `dex.lein-reader` | Reads `project.clj` via `lein deps :tree-data` subprocess, parses nested tree |
| `dex.layout` | Computes three-column layout: windowed columns, connection graph, version match colors, box annotations |
| `dex.views` | Hiccup rendering — toolbar, columns, boxes, search, arrow connection JSON |
| `dex.service` | HTTP lifecycle — routes, Hyper handler, `start!`/`stop!` |
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

**Layout** (output of `layout/compute-layout`):
```clojure
{:selected-box box-descriptor
 :left  {:boxes [...] :before n :after n}  ; windowed dependants column
 :right {:boxes [...] :before n :after n}  ; windowed dependencies column
 :connections [{:from :to :from-id :to-id :color :connection-type ...}]}
```

## Conventions

- **Rendering:** Hiccup vectors with Tailwind utility classes. Use `h/action` for server-side actions triggered by Datastar `data-on:*` attributes.
- **Client params:** `$value`, `$key`, `$form-data` etc. are Hyper macros that extract DOM values client-side and send them to the server action. They appear as unresolvable symbols in the editor — this is expected.
- **Idiomorph caveat:** Datastar's DOM morpher preserves focused input values. Use `el.value = ''; el.blur()` appended to action expressions when inputs need clearing.
- **SVG arrows:** Drawn client-side via `data-draw-arrows` plugin. Connection data (including version-match colors) is serialized to JSON by the server.
- **FLIP animation:** Box transitions use the Web Animations API. The `data-draw-arrows` plugin's `apply()` callback serves as the morph signal — no MutationObserver needed.
- **Version compatibility:** Classified by `layout/version-match` using `version-clj`: exact (black), compatible (green), incompatible (red), unknown/git-sha (yellow).

## Running

```bash
# REPL (dev)
clojure -M:dev           # then (service/start!) in REPL

# CLI
clojure -M:run           # auto-detects deps.edn in current dir
clojure -M:run -f /path/to/project.clj -a dev

# Tailwind (separate terminal)
bb tailwind

# Tests
bb test
```

## Keyboard Shortcuts

- **⌘F** / **Ctrl+F** — Focus artifact search field
- **⌘H** / **Ctrl+H** — Navigate to root node
