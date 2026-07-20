# Releasing paylod-jvm

Publishing **is** automated. `.github/workflows/release.yml` builds, tests, signs, stages and
uploads `dev.paylod:paylod` to the Sonatype Central Portal when a GitHub Release is published.

A Maven Central release is **permanent**. A published version can never be withdrawn, deleted or
replaced — only superseded by a higher version. Everything below is arranged around that fact.

## Why this one is different

The npm SDKs (`@paylod/node`, `@paylod/cli`, `@paylod/mcp`) and the Python SDK (`paylod`) publish
through **Trusted Publishing (OIDC)**: GitHub Actions mints a short-lived token, and no long-lived
credential is ever stored in the repo or in GitHub secrets.

Maven Central, via Sonatype, has no equivalent. It authenticates with a **username/password token
pair**, and it **rejects unsigned artifacts** — so a release also needs a **GPG key**. Both are
long-lived secrets. There is no way to make this repo's release path credential-free the way the
others are.

Note also that Sonatype's **Central Portal is not the old OSSRH `nexus-staging` API**. It accepts a
zipped bundle uploaded to the Publisher API, not a plain Maven repository PUT, which is why the
build uses a plugin that speaks that protocol rather than a hand-rolled `maven { url = ... }`
repository.

## How the build is wired

`build.gradle.kts` applies **`com.vanniktech.maven.publish` 0.34.0**.

That version is pinned between two hard ceilings, and moving it means moving a toolchain first:

- 0.36.0 and newer require Kotlin Gradle Plugin **2.2.0**; this project builds on Kotlin 2.0.21.
- 0.35.0 requires Gradle **8.13**; the wrapper here is 8.10.2.

0.34.0 is the newest release under both (Kotlin 1.9.20+, Gradle 8.5+), and it is already past the
point where the plugin dropped OSSRH and made the Central Portal its only host — so the pin costs
nothing. To upgrade: bump the Gradle wrapper, then Kotlin, then the plugin.

### Two publishing targets, both live

| Target | Task | What it does |
| --- | --- | --- |
| `localStaging` | `./gradlew publishAllPublicationsToLocalStagingRepository` | Writes the full artifact set to `build/repo`. Publishes nothing. |
| Central Portal | `./gradlew publishToMavenCentral` | Uploads a bundle to the Portal as a **draft** deployment. |

`publishToMavenCentral` is deliberately **not** configured to auto-release. It creates a draft that
a human reviews and releases at <https://central.sonatype.com/publishing/deployments>. The
irreversible step stays manual.

### Signing is conditional, and mandatory when configured

The build used to carry `signing { isRequired = false }`. That kept `./gradlew build` from
prompting a developer for a GPG key, but it bought that by making an unsigned *release* possible:
a mis-set secret would have staged unsigned artifacts and only failed later, at the Portal, with
the tag already cut.

Signing is now wired conditionally instead. `signAllPublications()` — which makes signing
mandatory — is called only when a key is actually configured, by any of:

| Mechanism | Property | Used by |
| --- | --- | --- |
| In-memory ASCII-armoured key | `signingInMemoryKey` (+ `signingInMemoryKeyPassword`) | CI, via `ORG_GRADLE_PROJECT_*` env vars |
| Keyring file | `signing.keyId` (+ `signing.secretKeyRingFile`, `signing.password`) | legacy local setups |
| Local gpg agent | `signing.gnupg.keyName` | developer dry runs |

With none of them set, the signing plugin is never asked to sign anything, so there is nothing to
prompt for — `./gradlew build` and `./gradlew test` work on a machine with no GPG key at all. With
any of them set, an artifact that cannot be signed **fails the build**.

## The release path

1. Bump `version=` in `gradle.properties`. That file is the single source of truth for the
   published coordinates; `build.gradle.kts` reads it rather than restating it.
2. Commit, tag `vX.Y.Z`, push.
3. Publish a GitHub Release on that tag.
4. The workflow requires an approval on the `maven-central` environment. Approve it.
5. It verifies the tag matches `gradle.properties`, runs the tests, stages **signed** artifacts to
   `build/repo`, uploads them as a workflow artifact, asserts every artifact has a `.asc`, and only
   then uploads to the Portal.
6. Go to <https://central.sonatype.com/publishing/deployments>, check the draft, and release it.
   **This step is irreversible.**

## Local dry run

Produces the exact artifact set Central will receive, signed, without publishing anything:

```sh
./gradlew publishAllPublicationsToLocalStagingRepository \
  -Psigning.gnupg.keyName=E53F3E21F6225618
find build/repo -type f | sort
gpg --verify build/repo/dev/paylod/paylod/*/paylod-*.jar.asc \
             build/repo/dev/paylod/paylod/*/paylod-*.jar
```

This prompts for the key's passphrase via gpg's pinentry, so it needs an interactive terminal.

The expected set is five artifacts — `.jar`, `-sources.jar`, `-javadoc.jar`, `.pom`, `.module` —
each with a `.asc` and md5/sha1/sha256/sha512 checksums.

Note the javadoc jar is empty apart from its manifest. `javadoc` is Gradle's Java tool and this is
a pure-Kotlin source set, so it has nothing to document. Central requires the *artifact* to be
present, not to have content, so this passes; producing real API docs would mean adding Dokka.

## Owner setup (all done)

1. **Sonatype Central account, `dev.paylod` namespace — VERIFIED.**
2. **GPG signing key** — key id `E53F3E21F6225618`, fingerprint
   `77A9 CC9F 9FCB 87FC 5741 29F7 E53F 3E21 F622 5618`. The public half is published to
   `keyserver.ubuntu.com` and `keys.openpgp.org`. Central checks the key against a keyserver, so
   the public half must stay published.
3. **Four GitHub Actions secrets**, scoped to the `maven-central` environment:

   | Secret | What it is |
   | --- | --- |
   | `SONATYPE_USERNAME` | Central Portal user token username (not the account email) |
   | `SONATYPE_PASSWORD` | Central Portal user token password |
   | `GPG_SIGNING_KEY` | ASCII-armoured private key block |
   | `GPG_SIGNING_PASSPHRASE` | Passphrase for that key |

   No other secret is needed. In particular there is **no** `MPESA_REPO_TOKEN`. An earlier
   revision of this file required one, with read access to the private `mosesmrima/mpesa-baas`
   monorepo, so that CI could clone the canonical Daraja error catalog for the drift guard to
   compare against. That was rejected and removed: it meant a long-lived credential with read
   access to the whole private monorepo, stored in four SDK repos, three of them public, to solve
   what is only a file-availability problem.

   The drift guard now verifies the vendored catalog against `daraja-catalog.sha256`, a committed
   pin recording the SHA-256 of the canonical file and of the vendored copy. It runs in every
   checkout with no sibling repo and no credential, and it FAILS CLOSED — a missing, malformed or
   unsatisfied pin is an error, never a skip. If you have the monorepo checked out at `../mpesa`
   (or `MPESA_REPO=/path`), the guard additionally compares against canonical and fails if the pin
   has gone stale.

4. **Environment protection rule** on the `maven-central` GitHub Environment (required reviewers),
   matching the `npm` and `pypi` environments used by the other SDKs. Keep it.

The key expires **2028-07-18**. Signing will start failing then; rotate the key and update
`GPG_SIGNING_KEY` / `GPG_SIGNING_PASSPHRASE` before that date.
