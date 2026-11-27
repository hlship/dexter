---
name: release
description: Create a new tagged release of dexter, including GitHub release, Homebrew formula, and Scoop manifest.
---

# Release Skill for Dexter

This skill guides the process of creating a new release of dexter.

A release pushes commits and a tag, publishes a GitHub release, and pushes to two other
repositories, so **get the user's approval before each commit/push/tag/publish step** (steps 3, 4, 5,
6, 7 and 8), and show them what is about to happen.

## Conventions

- **Paths:** this skill is run from the dexter repository; commands below are relative to the repo
  root (`git rev-parse --show-toplevel`). Do not hardcode a home directory: the repository lives
  in different places on different computers. Work out the real path first.
- **Sibling repositories:** `hlship/homebrew-brew` and `hlship/scoop-bucket` are expected to be
  cloned next to the dexter repository (as `../homebrew-brew` and `../scoop-bucket`). If one is
  missing, clone it there with `gh repo clone hlship/<name> ..` (from the dexter repo root). If the
  clone is somewhere else, ask the user.
- **Run git commands one at a time.** Never run `git add`, `git commit`, `git push`, `git tag`,
  etc. in parallel with each other: they race on `.git/index.lock` and a commit can silently fail
  after the push has already happened. Wait for each to finish before starting the next.
- **Commit messages** are concise conventional commits (e.g. `chore: update CHANGES.md for <tag>`).

## Prerequisites

- The workspace must be on the `main` branch with a clean working tree
- All changes for the release should already be merged to `main`
- The `CHANGES.md` file should be up to date with the release notes for this version
- Tools required: `git`, `gh` (GitHub CLI), `bb` (Babashka), `java`, `tailwindcss`

## Version and Tag Convention

Tags follow the pattern: `0.1-alpha-1`, `0.1-alpha-2`, ..., `0.1-beta-1`, `0.1-beta-2`, ..., `0.1`,
`0.2`, `1.0`, etc.

Previous tags can be listed with `git tag --list --sort=-creatordate`.

The next tag should be determined by asking the user, showing them the previous tags for context.

## Release Steps

### 1. Verify prerequisites

From the repository root:

```bash
git status -sb
git fetch origin --tags
git tag --list --sort=-creatordate
```

Confirm:
- On `main` branch
- Clean working tree (no uncommitted changes)
- Up to date with `origin/main`
- `bb`, `gh`, `java` and `tailwindcss` are on the PATH

Also list what has changed since the last release, to check the release notes against:

```bash
git log --oneline $(git describe --tags --abbrev=0)..HEAD
```

### 2. Update CHANGES.md

Read `CHANGES.md` and ensure the top section has the correct version (the tag being released) and is
not still marked `UNRELEASED`. If it is still the `# 0.1 -- UNRELEASED` section, change its heading to
the new tag, e.g. `# 0.1-beta-3`.

Compare the notes against the commits since the last release: every user-visible change should be
mentioned (bug fixes as well as features). Ask the user to confirm the release notes are complete.

### 3. Commit the CHANGES.md update

If `CHANGES.md` was modified, commit it to `main` and push before tagging. The tag must point to a commit that includes the final release notes.

```bash
git add CHANGES.md
git commit -m "chore: update CHANGES.md for <tag>"
git push origin main
```

### 4. Create and push the tag

Ask the user to confirm the tag name. Then:

```bash
git tag -a <tag> -m "Release <tag>"
git push origin <tag>
```

Check that the tag points at the commit that was just pushed:

```bash
git rev-parse <tag>^{commit} HEAD
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
- Render the Homebrew formula to `out/dexter.rb`
- Render the Scoop manifest to `out/dexter.json`

**Important:** The `bb release` command uses `git describe --tags --abbrev=0` to determine the current tag, so the tag must be created and pushed before running the release.

Only tags containing `alpha` are marked as GitHub pre-releases; a `beta` tag is published as a normal release.

Afterwards, verify the release and that the rendered files agree with the uploaded zip (the `sha256`
in the formula and the `hash` in the manifest must match):

```bash
gh release view <tag> --json tagName,isPrerelease,isDraft,url,assets
shasum -a 256 out/dexter-<tag>.zip
```

The `out/` directory is git-ignored.

### 6. Update Homebrew tap

The rendered formula is `out/dexter.rb`. It is copied to `Formula/dexter.rb` in the `hlship/homebrew-brew`
tap repository (find or clone it as described under Conventions). From the tap repo, make sure it is
up to date (`git pull --ff-only`), then, with `<dexter-repo>` the dexter repository root:

```bash
cp <dexter-repo>/out/dexter.rb Formula/dexter.rb
git diff
```

The diff should be only the version, `url`, `sha256` and the jar name. Then commit and push:

```bash
git add Formula/dexter.rb
git commit -m "chore: dexter <tag>"
git push origin main
```

### 7. Update Scoop bucket

The rendered manifest is `out/dexter.json`. It is copied to `dexter.json` at the root of the
`hlship/scoop-bucket` repository (find or clone it as described under Conventions). From that repo, make
sure it is up to date (`git pull --ff-only`), then:

```bash
cp <dexter-repo>/out/dexter.json dexter.json
git diff
```

The diff should be only the `version`, `url` and `hash`. Then commit and push:

```bash
git add dexter.json
git commit -m "chore: update dexter to <tag>"
git push origin main
```

### 8. Post-release

After the release, in the dexter repository:
- Add a new `# <next-version> -- UNRELEASED` section at the top of `CHANGES.md`. For an alpha or beta
  release the next version is the eventual release, e.g. `# 0.1 -- UNRELEASED` after `0.1-beta-3`.
- Commit (`chore: add UNRELEASED section to CHANGES.md`) and push to `main`
- Confirm the GitHub release page looks correct: `https://github.com/hlship/dexter/releases/tag/<tag>`
- Confirm each repository is clean and in sync with its `origin` (`git status -sb`)
