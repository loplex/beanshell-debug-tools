# Releasing

One tag drives three independently-gated artifacts, all via
[`.github/workflows/release.yml`](../.github/workflows/release.yml):

| Artifact | Changelog gate | Built by | Published to |
|---|---|---|---|
| IDEA plugin | `plugin/CHANGELOG.md` | `:plugin:buildPlugin` | GitHub Release + JetBrains Marketplace |
| Debug agent | `agent/CHANGELOG.md` | `:agent:instrument:shadowJar` | GitHub Release only |
| VS Code extension | `editors/vscode/CHANGELOG.md` | `vsce package` | GitHub Release + VS Code Marketplace |

Pushing `vX.Y.Z` runs all three build jobs, but **each one only builds and publishes
if its own changelog has a `## [X.Y.Z]` section.** An artifact with nothing new this
round is skipped entirely — no build, no upload, nothing added to the release — and
its last-published version stays current wherever it's distributed. This is why
there's no single "the project version" anymore: `gradle.properties`' `version` and
`editors/vscode/package.json`'s `"version"` are local dev defaults only. Every
workflow build passes `-Pversion=X.Y.Z` (Gradle) or an explicit version argument
(`vsce package`), both of which override the committed file — so what's committed
there never has to match the tag, and is free to sit at a `-SNAPSHOT`-style dev
placeholder between releases.

Everything under "One-time setup" is done once by whoever holds the two Marketplace
vendor accounts and the signing key. After that, releasing is just "Cutting a
release" below.

## Cutting a release

1. For each artifact you want to release this round, move its changelog's entries
   from `## [Unreleased]` to a new `## [X.Y.Z] - YYYY-MM-DD` heading, and commit
   that. Leave an artifact's changelog with an empty (or absent) `[X.Y.Z]` section to
   skip it this round — its job then does nothing and its current published version
   stands.

   The plugin's check (`:plugin:getChangelog --no-unreleased --project-version=X.Y.Z`)
   and the agent/VS Code check (`scripts/changelog-section.sh <file> X.Y.Z`) both key
   off the exact version string, so a typo or a missing heading just means that
   artifact quietly gets skipped rather than failing the whole workflow.

2. Tag and push:

   ```bash
   git tag vX.Y.Z
   git push origin vX.Y.Z
   ```

3. Watch the **Release** workflow in the Actions tab. `publish-jetbrains-marketplace`
   and `publish-vscode-marketplace` each pause for manual
   approval — approve whichever ran, once you're happy with the GitHub Release that
   just went out. A skipped artifact's publish job doesn't run at all, so there's
   nothing to approve for it.

## One-time setup

### 1. JetBrains Marketplace: token + first upload

- Generate a Marketplace API token at
  https://plugins.jetbrains.com/author/me/tokens and add it as the
  `JETBRAINS_MARKETPLACE_TOKEN` repository secret (Settings -> Secrets and
  variables -> Actions).
- **The very first version of a brand-new plugin must be uploaded by hand** through
  the Marketplace web UI (New plugin -> upload the ZIP from
  `plugin/build/distributions/`) and pass JetBrains' manual review. `publishPlugin`
  only works for versions *after* that first approval — run a tag through the
  pipeline for real only once this is done.

### 2. Plugin signing

JetBrains recommends every Marketplace plugin be signed, so its origin is verifiable.
Generate a self-signed certificate locally (never paste the private key into a chat
tool or commit it anywhere):

```bash
openssl genpkey -aes256 -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096
openssl req -key private_encrypted.pem -new -x509 -days 3650 -out chain.crt -subj "/CN=loplex"
```

`openssl req` will prompt for the passphrase chosen in the first command. Add three
repository secrets:

- `PRIVATE_KEY` — contents of `private_encrypted.pem` (stays password-protected)
- `CERTIFICATE_CHAIN` — contents of `chain.crt`
- `PRIVATE_KEY_PASSWORD` — the passphrase from the first command

Then delete both files locally — only the secrets need to persist.

### 3. VS Code Marketplace: publisher + token

- Create the `loplex` publisher at https://marketplace.visualstudio.com/manage (needs
  an Azure DevOps organization) if it doesn't exist yet.
- Generate an Azure DevOps Personal Access Token scoped to **Marketplace ->
  Manage**, and add it as the `VSCODE_MARKETPLACE_TOKEN` repository secret. See
  https://code.visualstudio.com/api/working-with-extensions/publishing-extension#get-a-personal-access-token
  for the exact steps.
- Unlike JetBrains, `vsce publish` works for a brand-new extension on the first try —
  there's no separate manual-review gate to clear first.

### 4. Approval gates

Two GitHub Environments (Settings -> Environments), each with yourself as a required
reviewer, so every Marketplace publish needs an explicit approval click in the
Actions UI before it goes out:

- `jetbrains-marketplace` — targeted by `publish-jetbrains-marketplace`
- `vscode-marketplace` — targeted by `publish-vscode-marketplace` (VS Code)

Neither the `github-release` job nor the debug agent's build has this gate — that
side is easily undone (delete the release), while a bad Marketplace publish is not.

## The standalone debug agent

`bsh-debug-agent-X.Y.Z.jar` (`:agent:instrument:shadowJar`, with `:agent:hook` shaded
in) is only ever attached as a GitHub Release asset — there's no separate registry
it's published to today. VS Code, Neovim and Eclipse users who don't want the IDEA
plugin download it straight from the Release page. If that ever needs to change (e.g.
Maven Central), that's a materially bigger one-time setup (GPG signing, POM
metadata, group ID verification) than anything above.
