# Releasing paylod-jvm

**Publishing is not automated, and this SDK is not cleared to publish.** This document exists so
that whoever wires it up later does not have to reverse-engineer what is missing.

## Why this one is different

The npm SDKs (`@paylod/node`, `@paylod/cli`, `@paylod/mcp`) and the Python SDK (`paylod`) publish
through **Trusted Publishing (OIDC)**: GitHub Actions mints a short-lived token, and no long-lived
credential is ever stored in the repo or in GitHub secrets.

Maven Central, via Sonatype, has no equivalent. It authenticates with a **username/password token
pair**, and it **rejects unsigned artifacts** — so a release also needs a **GPG key**. Both are
long-lived secrets that must be created by the owner and stored as GitHub Actions secrets. There
is no way to make this repo's release path credential-free the way the others are.

## What `.github/workflows/release.yml` does today

It runs on a published GitHub Release only, verifies the release tag matches
`gradle.properties:version`, runs the tests, builds the jar / sources jar / javadoc jar, stages
them into `build/repo` via the `localStaging` repository already configured in `build.gradle.kts`,
and uploads that directory as a workflow artifact.

It **does not publish**. The publish step is commented out at the bottom of the file and is not
credential-complete.

## What the owner must set up before publishing is possible

1. **Sonatype Central account and namespace verification for `dev.paylod`.**
   Register at <https://central.sonatype.com>, claim the `dev.paylod` namespace, and complete the
   DNS TXT-record verification proving control of the `paylod.dev` domain. Until this is done the
   coordinates cannot be published by anyone.

2. **A GPG signing key.**
   Generate a key, publish the public half to a keyserver (`keys.openpgp.org`), and export the
   private half in a form the build can consume (an ASCII-armoured in-memory key, not a keyring
   file on disk).

3. **Four GitHub Actions secrets**, scoped to the `maven-central` environment:

   | Secret | What it is |
   | --- | --- |
   | `SONATYPE_USERNAME` | Central portal user token username (not the account email) |
   | `SONATYPE_PASSWORD` | Central portal user token password |
   | `GPG_SIGNING_KEY` | ASCII-armoured private key block |
   | `GPG_SIGNING_PASSPHRASE` | Passphrase for that key |

4. **A build change.** `build.gradle.kts` currently configures only the `localStaging` repository
   and sets `signing { isRequired = false }`. Publishing needs a real Central repository (either
   the `com.vanniktech.maven.publish` plugin or Sonatype's own `central-publishing` plugin) and
   signing made mandatory for release builds.

5. **An environment protection rule** on the `maven-central` GitHub Environment (required
   reviewers), matching the `npm` and `pypi` environments used by the other SDKs.

Only once all five are done should the commented publish step be enabled — and only after the
independent security review of the payments code has cleared.
