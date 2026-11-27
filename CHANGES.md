# 0.1 -- UNRELEASED

- Artifact boxes show a size bar: the artifact's own size and its footprint (itself plus all
  transitive dependencies); the properties panel shows the same sizes.
  Footprints count only visible artifacts; showing hidden dependencies (including `org.clojure/clojure`) grows them

- Added support for Babashka `bb.edn` files

- Bumped dependencies; adapted to Hyper's new `:datastar-url` option

# 0.1-beta-2

- Added floating action button (FAB) settings menu with toggles for fading
  exact connections and viewing hidden libraries

- Added per-tab Hide/Show button in the properties panel to customize
  which libraries are hidden

- Bumped dependencies

- Fixed Scoop manifest to use `suggest` instead of `depends` for Java,
  since Scoop can't resolve cross-bucket dependencies

- Added manual GitHub Actions workflow to smoke test the Windows Scoop install

# 0.1-beta-1

- Properties panel is now always visible as a fixed-width sidebar

- Footer version-match counts now show unique artifacts (matching the popup)
  and exclude hidden libraries

- Unresolved transitive dependencies are filtered out when building the
  dependency database

- `bb lint` task no longer produces a noisy stack trace on lint failures

- Added Windows support with `.cmd` launcher and Scoop distribution
