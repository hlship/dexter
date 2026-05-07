# 0.1 -- UNRELEASED

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
