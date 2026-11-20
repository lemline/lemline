# Release Procedure

How to publish a Lemline version `vX.Y.Z`.

GitHub Actions builds the Linux and Windows native binaries and the jar when a version tag is
pushed. The macOS arm64 binary is built on an Apple Silicon Mac and attached by hand: the native
build needs about 6.5 GB of memory, and GitHub's free macOS runners have 7 GB in all, so
`native-image` runs out of heap there and aborts after about 45 minutes.

## Prerequisites

- An Apple Silicon Mac with GraalVM 25 (`native-image` on the `PATH`).
- The GitHub CLI (`gh`), authenticated with write access to `lemline/lemline`.
- Docker, to run the native tests that need PostgreSQL.

## 1. Prepare the version

1. Set `baseVersion` in `lemline-runner/build.gradle.kts` to `X.Y.Z`, then run
   `./gradlew :lemline-runner:syncDocsVersion` so the docs download links point at it.
2. Merge this change into `main` through a pull request.
3. Write the release notes with `/release X.Y.Z`. They are saved as `RELEASE-X.Y.Z.md` (not
   committed) and follow the four-part format of the `release-writer` skill, written from the
   user's point of view:
   - **Summary**: the theme and the headline capabilities, ending with a **Before you upgrade**
     block listing every action an upgrade requires (omitted when there is none);
   - **New Features**: what users can now do, with short examples for the main ones;
   - **Improvements**: existing behaviour made faster, clearer or more compliant;
   - **Bug Fixes**: what now works, and what users suffered before.

   There is no separate Breaking Changes or Dependencies section. Review the notes before tagging.

## 2. Check the native binaries before tagging

1. On GitHub, start Actions → **Native Binary Tests** → *Run workflow* on `main`. It builds and
   tests the Linux and Windows binaries.
2. On the Mac, from a clean checkout of `main`:

   ```bash
   ./gradlew :lemline-runner:testNative
   ```

## 3. Tag

```bash
git checkout main && git pull
git tag vX.Y.Z
git push origin vX.Y.Z
```

The push starts **Build & Release Lemline**. It creates the `vX.Y.Z` GitHub release and attaches
the Linux, Windows and jar archives with their `.sha256` files (about 15 minutes).

## 4. Attach the macOS binary

Once that run has succeeded, from a clean checkout of the tag on the Mac:

```bash
git checkout vX.Y.Z
scripts/release-macos.sh vX.Y.Z
```

The script refuses to run on a modified checkout or on a commit other than the tag. It builds the
binary with the tag's version, checks that `lemline --version` reports it, packages
`lemline-vX.Y.Z-macos-arm64.tar.gz` (`bin/lemline`, `LICENSE.md`, `README.md`, the Linux layout)
with its `.sha256`, and uploads both to the release. `--no-upload` builds and checks only.

## 5. Publish the notes

Paste `RELEASE-X.Y.Z.md` into the release body:

```bash
gh release edit vX.Y.Z --notes-file RELEASE-X.Y.Z.md
```

Then check that the release page lists five downloads, each with its `.sha256`: Linux, Windows,
macOS, the jar and the jar zip.

## Nightly builds

The nightly run of **Build & Release Lemline** publishes Linux, Windows and jar builds under the
`nightly` release. There is no nightly macOS build; Mac users install a tagged version.
