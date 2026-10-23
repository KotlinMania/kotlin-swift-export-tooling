# Immutable release procedure

1. Run the manual `ci.yml` workflow at the final PR revision. It rebuilds twice,
   checks the actual POM's complete worker closure, audits the packaged SDK helper,
   and executes the pinned real Native/Swift consumer. Preserve every raw log.
2. Merge the PR through GitHub, then run that same workflow on exact merged main.
   Both runs must succeed on identical source trees. A source change requires a
   new final-revision check.
3. Use the matching verified `distribution-a` from CI or reproduce it locally.
   `verification/release-artifacts.json` must match every byte.
4. Publish with existing authentication and an existing signing key only:

   ```sh
   python3 scripts/publish.py --distribution build/distribution-a \
     --output build/publication --pr-ci-run PR_RUN_ID --main-ci-run MAIN_RUN_ID
   ```

   Required environment names are `MAVEN_CENTRAL_USERNAME`,
   `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY`, `SIGNING_KEY_PASSWORD` and optionally
   `SIGNING_KEY_ID`. Alternatively `--existing-secrets-dir` reads the existing
   local `creds.xml`, `private.maven.asc` and `passphrase.txt` directly into memory.
   This does not create credentials, import a keyring or configure GitHub secrets.
   Do not print these values, put them in command arguments, commit them, or
   upload them as workflow artifacts. Signing uses Gradle's in-memory signing API.
5. Preserve the returned Central deployment ID and poll its Publisher status
   using the existing authentication. An upload is not a publication.
6. After Central reports publication, independently retrieve:

   ```sh
   python3 scripts/verify_published.py --output build/central-retrieval
   ```

   Verify each detached signature against the existing trusted signing public
   key as well as the recorded checksums. Retain the public key fingerprint and
   verification result. The retrieval script checks contents/checksums and
   preserves signatures; signature presence alone is not cryptographic proof.
7. Create the matching `v<version>` GitHub release using the version in `inputs.lock.json` at the verified main revision.
   `publish.yml` independently verifies the already published immutable files; it
   deliberately does not create or configure persistent access credentials.
8. Hand the immutable Maven coordinate, six checksums, helper invocation, SDK
   audit and exact CI/retrieval receipts to the existing consumer owners. The
   ByteArray update requires Byteorder's three unchanged real Swift cases with
   the published bytes, followed by that repository's final release gates. The
   ProcMacro2 three-case regression remains required by this tooling's CI.
   Neither consumer acceptance nor broader selection follows automatically
   from the producer fixtures.

## Local validation install (not a publication)

`python3 scripts/publish_local.py --distribution build/distribution-a`
(or `./gradlew publishToMavenLocal -PdistributionDirectory=build/distribution-a`)
installs the six verified artifacts into a local Maven repository, defaulting to
`~/.m2/repository`, after re-checking every byte against the frozen
`verification/release-artifacts.json` fingerprints. It writes standard Maven
checksums, never overwrites a previous install without `--replace`, and accepts
an isolated absolute `--repository` path (Gradle's `mavenLocal()` follows the
`maven.repo.local` system property, restricted to `io.github.kotlinmania`).
This exists only for consumer validation of the exact release bytes; it is not
a publication, produces no release evidence, and never replaces step 4 above
or the independent Central retrieval checks.
