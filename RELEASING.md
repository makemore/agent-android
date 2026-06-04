# Releasing `agent-android`

Distribution is via **[JitPack](https://jitpack.io)**: it builds this public repo
on demand from a git tag and serves both modules anonymously (no PAT, no GitHub
Packages, no publish CI). A release is therefore just **a pushed semver tag**.

- `com.github.makemore.agent-android:agent-client` — headless runtime/transport core
- `com.github.makemore.agent-android:agent-frontend` — Compose chat widget (depends on `agent-client`)

Both ship from the **same tag** (JitPack injects it as `VERSION`) so consumers pin
a single number.

> **Audience:** library maintainers. If you are *consuming* the library in an app,
> see [`README.md`](README.md) / [`TEMPLATE_APP_SETUP.md`](TEMPLATE_APP_SETUP.md).

---

## Versioning

- Semantic versioning, tag form `MAJOR.MINOR.PATCH` (e.g. `0.8.0`) — **no** `v`
  prefix.
- On JitPack the build reads `GROUP` (`com.github.makemore.agent-android`) and
  `VERSION` (the tag) from the environment; `build.gradle.kts` derives the Gradle
  `group` / `version` from them. Locally it falls back to `com.makemore` and the
  `agentVersion` property. Artifact ids are `agent-client` / `agent-frontend`.
- JDK / build environment is pinned in [`jitpack.yml`](jitpack.yml) (OpenJDK 17).

---

## Cutting a release

1. **Update version references in docs** so consumers copy the right number:
   - `README.md` (install snippets)
   - `TEMPLATE_APP_SETUP.md` (dependency lines)

2. **Commit** the doc bump (open a PR if your branch protection requires it):

   ```bash
   git add README.md TEMPLATE_APP_SETUP.md
   git commit -m "Release 0.8.0"
   ```

3. **Tag and push** — that's the release:

   ```bash
   git tag 0.8.0
   git push origin 0.8.0
   ```

4. **Trigger the build** by requesting the version once (the first consumer to ask
   for the tag triggers it, but you should validate it yourself). Either:
   - Visit `https://jitpack.io/#makemore/agent-android` and click **Get it** on the
     new tag (watch the build log go green), or
   - Hit the build URL directly:
     `https://jitpack.io/com/github/makemore/agent-android/agent-frontend/0.8.0/`

5. **Verify** by bumping a consuming app to the new tag and resolving Gradle.

> **Tip:** add a JitPack webhook (`https://jitpack.io/api/webhooks`) to the GitHub
> repo so tags build ahead of time.

---

## Smoke-test before tagging

Simulate the JitPack build locally — set the same env vars it injects and publish
to the local Maven cache:

```bash
GROUP=com.github.makemore.agent-android VERSION=0.8.0 \
  ./gradlew :agent-client:publishToMavenLocal :publishToMavenLocal -x test --no-daemon
# Inspect: ~/.m2/repository/com/github/makemore/agent-android/agent-{client,frontend}/0.8.0/
# Confirm agent-frontend's POM depends on agent-client under the same group.
```

This mirrors exactly what [`jitpack.yml`](jitpack.yml) runs.

---

## After releasing

- Update [`README.md`](README.md) and the consuming-app
  [`TEMPLATE_APP_SETUP.md`](TEMPLATE_APP_SETUP.md) if the install snippets still
  point at an older version.
- A git tag is effectively **immutable** for consumers (JitPack caches built
  artifacts per tag). To fix a bad release, push a new patch tag.
- If a build failed, fix the issue, then re-tag (or use JitPack's **Rebuild** after
  signing in) so the cached failure is cleared.
