# Releasing updates

Installed copies of Workshop Manager update themselves from GitHub Releases. This page covers the one-time setup, the everyday release, and the rules for changes that touch the database.

## One-time setup

1. **Repository.** The code lives in a GitHub repository. Downloads must be public, because the workshop PCs fetch updates without logging in. Choose one:
   - **Simplest:** one public repository, which holds the code and the releases.
   - **Code kept private:** a private repository for the code, plus a second, public, empty repository just for the downloads (for example `workshop-manager-releases`). In the code repository:
     - add a variable `RELEASES_REPO` = `owner/workshop-manager-releases` (Settings → Secrets and variables → Actions → Variables)
     - add a secret `RELEASES_TOKEN` holding a fine-grained token with **Contents: read and write** on the releases repository
2. **Signing key.** Every update is signed, and the app refuses anything not signed with your key.
   - The public half is in `packaging/update-public-key.txt`. It gets built into the app.
   - Save the private half as the repository secret **`UPDATE_SIGNING_KEY`** (Settings → Secrets and variables → Actions → New repository secret).
   - Keep a copy of the private key somewhere safe and offline. If it's lost, make a new pair (below) and install the full package once on each PC.

   To make a new key pair:
   ```
   java packaging/UpdateKeys.java gen
   ```
   This writes the new public key to `packaging/update-public-key.txt` (commit it) and prints the private key (put it in the secret).

3. **First install.** Run the **Release** workflow once, then install the full zip from that release on each PC (see README). Every version after that arrives by itself.

## Publishing a new version

- Push your changes. The **Tests** workflow runs on every push; make sure it's green.
- Go to **Actions → Release → Run workflow**.
  - **Notes:** say what changed, in words the workshop will understand. The app shows this text before installing.
  - **Version:** leave it empty for the next number (1.2.3 → 1.2.4), or type one (for example 1.3.0).
- Or, from a terminal: `git tag -a v1.3.0 -m "What changed"` then `git push origin v1.3.0`.

The workflow then:
1. runs the tests
2. builds the Windows and Mac packages
3. signs the list of program files
4. publishes the release, uploading `latest.json` last

Installed copies check every 6 hours, and a few seconds after login.

**On the workshop PC:**
- The update downloads in the background. Only the jars that changed come down, usually just the app's own 2–3 MB.
- A backup of the records is made.
- The menu shows **Update ready → Restart now**. If nobody clicks it, the update installs the next time the app is opened.

**If something goes wrong:**
- A version that fails to start three times is rolled back automatically, and that version is never downloaded again.
- A version that can't upgrade the database leaves the records untouched and rolls back on the next start.
- To ship a fix, release a new, higher version. Never re-use a version number.

## Changing the database

The schema is upgraded by numbered steps in `src/main/kotlin/pk/workshop/db/Schema.kt` (`MIGRATIONS`).

- **Add** a new entry at the end. Never edit or reorder existing ones, because PCs in the field have already run them.
- Each step runs once, inside a transaction. A copy of the database (`workshop.db.before-vN`) is kept in the data folder before upgrading.
- Prefer additive changes: new tables, or new columns with defaults. Old versions can't open data that a newer version has upgraded; the app says so instead of guessing.

```kotlin
private val MIGRATIONS: List<() -> Unit> = listOf(
    { Db.script(V1); SeedAccounts.seed() },
    { Db.script("ALTER TABLE parts ADD COLUMN hs_code TEXT") },   // version 2
)
```

## What needs a full reinstall

Updates replace the program's jars. They don't replace the launcher or the bundled Java. If a change ever needs those, for example a newer Java, then:
- raise `minJava` in `packaging/UpdateKeys.java`
- the app then tells the user to download the full package once

## Building locally

```
packaging/package.sh 1.3.0-test     # build/release/: both zips and the update jars
```
Add `UPDATE_SIGNING_KEY=… RELEASE_BASE=… UPDATE_FEED=…` to also produce a signed `latest.json`, which is useful for testing updates against a local web server.
