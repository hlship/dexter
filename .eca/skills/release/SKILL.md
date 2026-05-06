---
name: release
description: Create a new tagged release of dexter, including GitHub release, Homebrew formula, and Scoop manifest.
---

# Release Skill for Dexter

This skill guides the process of creating a new release of dexter.

## Prerequisites

- The workspace must be on the `main` branch with a clean working tree
- All changes for the release should already be merged to `main`
- The `CHANGES.md` file should be up to date with the release notes for this version
- Tools required: `git`, `gh` (GitHub CLI), `bb` (Babashka), `java`, `tailwindcss`

## Version and Tag Convention

Tags follow the pattern: `0.1-alpha-1`, `0.1-alpha-2`, ..., `0.1`, `0.2`, `1.0`, etc.

Previous tags can be listed with `git tag --list`.

The next tag should be determined by asking the user, showing them the previous tags for context.

## Release Steps

### 1. Verify prerequisites

```bash
git -C /Users/howard.lewisship/workspaces/hlship/dexter status
git -C /Users/howard.lewisship/workspaces/hlship/dexter branch --show-current
git -C /Users/howard.lewisship/workspaces/hlship/dexter tag --list
```

Confirm:
- On `main` branch
- Clean working tree (no uncommitted changes)
- Up to date with `origin/main`

### 2. Update CHANGES.md

Read `CHANGES.md` and ensure the top section has the correct version and is not still marked `UNRELEASED`. 
Ask the user to confirm the release notes are complete. If the version heading needs updating, edit it.

### 3. Commit the CHANGES.md update

If `CHANGES.md` was modified, commit it to `main` and push before tagging. The tag must point to a commit that includes the final release notes.

```bash
git -C /Users/howard.lewisship/workspaces/hlship/dexter add CHANGES.md
git -C /Users/howard.lewisship/workspaces/hlship/dexter commit -m "Update CHANGES.md for <tag>"
git -C /Users/howard.lewisship/workspaces/hlship/dexter push origin main
```

### 4. Create and push the tag

Ask the user to confirm the tag name. Then:

```bash
git -C /Users/howard.lewisship/workspaces/hlship/dexter tag -a <tag> -m "Release <tag>"
git -C /Users/howard.lewisship/workspaces/hlship/dexter push origin <tag>
```

### 5. Run the release task

```bash
bb release
```

This will:
- Build CSS with tailwindcss
- Build the uberjar
- Render the `dexter` (bash) and `dexter.cmd` (Windows) launcher scripts
- Package everything into a zip: `out/dexter-<tag>.zip`
- Create a GitHub release and upload the zip
- Render the Homebrew formula to `out/build/dexter.rb`
- Render the Scoop manifest to `out/build/dexter.json`

**Important:** The `bb release` command uses `git describe --tags --abbrev=0` to determine the current tag, so the tag must be created and pushed before running the release.

### 6. Update Homebrew tap

The rendered formula is at `out/build/dexter.rb`. It needs to be copied to the `hlship/homebrew-brew` Homebrew tap repository.

Ask the user where their local clone of `hlship/homebrew-brew` is (likely at `/Users/howard.lewisship/workspaces/hlship/homebrew-brew`), then:

```bash
cp out/build/dexter.rb <brew-repo>/Formula/dexter.rb
```

Commit and push the updated formula in that repo.

### 7. Update Scoop bucket

The rendered manifest is at `out/build/dexter.json`. It needs to be copied to the `hlship/scoop-bucket` repository.

The local clone is likely at `/Users/howard.lewisship/workspaces/hlship/scoop-bucket`.

```bash
cp out/build/dexter.json <scoop-bucket-repo>/dexter.json
```

Commit and push the updated manifest in that repo.

### 8. Post-release

After the release:
- Add a new `# <next-version> -- UNRELEASED` section at the top of `CHANGES.md`
- Commit and push to `main`
- Confirm the GitHub release page looks correct: `https://github.com/hlship/dexter/releases/tag/<tag>`
