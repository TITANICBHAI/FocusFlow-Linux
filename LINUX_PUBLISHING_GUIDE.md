# Publishing FocusFlow for Linux

Updated: September 2026

## The short answer

Linux does not have one universal equivalent of the Microsoft Store. Users
install software through several app stores, distribution repositories, and
direct-download channels. A serious Linux release normally publishes to more
than one of them.

For FocusFlow, use this order:

1. **GitHub Releases** — the canonical download page for `.deb`, `.rpm`,
   `.AppImage`, `install.sh`, and checksums.
2. **Flathub** — the broadest graphical Linux app store. This should be the
   main store target after a Flatpak build is prepared.
3. **Snap Store** — the main store for Ubuntu and other Snap-enabled systems.
4. **AUR** — the important community repository for Arch Linux. The repository
   already contains a `focusflow-bin` package template.
5. **Fedora COPR** — a practical Fedora/RHEL-family repository before applying
   for Fedora's official repositories.
6. **Open Build Service (OBS)** — useful for building and publishing packages
   for openSUSE and multiple distributions.
7. **Launchpad PPA** — useful for Ubuntu users when a proper source `.deb`
   package is available.
8. **Official distribution repositories** — highest trust, but the slowest and
   most review-heavy route.

AppImage is a distribution format, not a single store. Keep publishing it on
GitHub Releases and optionally list it in community catalogs.

## Channel comparison

| Channel | Best audience | Package required | Review model | Update model |
|---|---|---|---|---|
| GitHub Releases | Everyone | `.deb`, `.rpm`, `.AppImage` | Project-controlled | Release workflow |
| Flathub | Most desktop Linux users | Flatpak manifest | Curated pull request | Git commits to app repo |
| Snap Store | Ubuntu and Snap users | Snap | Automated checks plus store controls | Snap channels |
| AUR | Arch and EndeavourOS users | `PKGBUILD` | Community review | AUR git repository |
| Fedora COPR | Fedora-family users | RPM spec/source | Repository owner controls | COPR builds |
| OBS | openSUSE and multi-distro users | Spec/package sources | Project/repository controls | OBS rebuilds |
| Launchpad PPA | Ubuntu users | Signed Debian source package | PPA build checks | New source uploads |
| Debian/Ubuntu official | Distribution users | Policy-compliant source package | Maintainer and archive review | Distribution releases |
| Fedora official | Fedora users | Fedora-compliant source RPM | Package review | Fedora updates |
| Arch official | Arch users | Arch package | Arch package review | Arch repositories |
| Nixpkgs | NixOS and Nix users | Nix expression | Pull request review | Nixpkgs updates |

GNOME Software and KDE Discover are usually storefront interfaces, not
separate upload destinations. They display packages from sources such as
Flathub, Snap, and the distribution's own repositories.

## What must be ready before publishing

Prepare these once and reuse them across every channel:

- A stable reverse-DNS application ID, such as `com.focusflow.FocusFlow`.
- A stable product name and binary/package name.
- A version policy. This project currently uses `2.0.1`.
- A desktop entry with correct `Name`, `Exec`, `Icon`, and categories.
- A scalable icon and PNG fallbacks.
- An AppStream metainfo file containing description, homepage, license,
  screenshots, release history, and supported architectures.
- A short and long description that do not claim unsupported platform behavior.
- A changelog for each release.
- SHA-256 checksums for every direct-download artifact.
- A public source repository and a reproducible build path where the channel
  requires building from source.
- A clear permissions explanation. FocusFlow can inspect processes and may use
  hosts-file or firewall enforcement, so sandbox restrictions must be tested
  honestly.
- A support page and a way to report security issues.

Do not reuse a Windows installer as a Linux store submission. Each channel
expects its own package metadata and may apply different sandbox and
dependency rules.

## 1. GitHub Releases: canonical direct downloads

This is already configured for FocusFlow.

The release workflow publishes:

- Debian/Ubuntu `.deb`
- Fedora/RPM `.rpm`
- Portable `.AppImage`
- Flatpak bundle for local installation and Flathub review
- Snap bundle for local installation and Snap Store review
- AUR recipe archive
- COPR/OBS RPM recipe archive
- Launchpad source-package artifacts
- `install.sh`
- `SHA256SUMS`

### Release procedure

1. Bump the version in `build.gradle.kts`, including `packageVersion`.
2. Update the application version shown in the UI and crash reporter.
3. Update the changelog and packaging metadata.
4. Push to `main`.
5. Wait for **Build Linux Packages**, **Build Linux Distribution Channels**,
   and **Linux Smoke Tests** to pass.
6. Run **Publish Linux Release** with the successful native build run ID and
   distribution-channels run ID.
7. Verify the tag, release notes, asset names, and checksums.

Direct downloads are the fallback for every distribution and remain important
even after Flathub or Snap publication.

### What CI covers

The repository separates native package builds from community-channel builds
so a Flathub or Snap toolchain problem does not hide a broken `.deb`, `.rpm`,
or AppImage:

- `.github/workflows/build-linux.yml` builds and validates native Linux
  packages.
- `.github/workflows/build-linux-channels.yml` builds the Flatpak and Snap
  bundles and prepares AUR, COPR/OBS, and Launchpad artifacts.
- `.github/workflows/release.yml` downloads both workflow outputs and attaches
  them to the GitHub release.

The channel workflow prepares artifacts; it does not impersonate a publisher.
Flathub and Snap still require store review, AUR/COPR/OBS require repository
accounts, and Launchpad requires a GPG-signed upload. Official distribution
repositories require human maintainers and review.

## 2. Flathub: the main graphical Linux store

Flathub is the closest broad Linux equivalent to a public app store. It is
used by many desktop environments and is discoverable through software-center
applications.

### What to do

1. Choose and reserve the stable application ID, for example
   `com.focusflow.FocusFlow`.
2. Create a Flatpak manifest and an AppStream metainfo file.
3. Make the app build from source in the Flatpak sandbox. Flathub normally
   expects a build recipe, not a manually uploaded prebuilt installer.
4. Test locally with `flatpak-builder`.
5. Create the Flathub app repository and submit the manifest through the
   Flathub GitHub workflow.
6. Respond to review comments and fix metadata, permissions, and build issues.
7. After acceptance, updates are normal commits to the app repository; they do
   not repeat the initial submission process.

### FocusFlow warning

FocusFlow's enforcement features need access to processes, desktop-session
state, hosts files, and possibly firewall tooling. Flatpak sandboxing may
prevent or limit those features. The Flatpak must either:

- expose only permissions that Flathub accepts and clearly document reduced
  enforcement behavior, or
- use an architecture that keeps privileged enforcement outside the sandbox
  with an explicit, auditable helper.

Do not claim that the Flatpak has the same enforcement strength as the native
`.deb`, `.rpm`, or AppImage until those permissions are tested on X11,
Wayland, and the supported desktop environments.

Official references:

- [Flathub submission](https://docs.flathub.org/docs/for-app-authors/submission)
- [Flathub requirements](https://docs.flathub.org/docs/for-app-authors/requirements)

## 3. Snap Store: Ubuntu's app store

Snap Store is the strongest store option for Ubuntu users and is also
available on other distributions with Snap support.

### What to do

1. Create a `snap/snapcraft.yaml` with the app name, version, summary,
   description, architectures, grade, confinement, plugs, and app command.
2. Register the snap name in a Snapcraft account.
3. Build it with Snapcraft and test it locally.
4. Upload it to the Snap Store.
5. Start with a private or edge channel.
6. Test installation, startup, updates, and every enforcement feature.
7. Promote the tested revision to stable:

   ```bash
   snapcraft upload --release=stable focusflow_<version>_amd64.snap
   ```

### FocusFlow warning

Snap confinement and interfaces affect process inspection, desktop control,
hosts-file access, and firewall operations. Start with strict confinement if
possible. If a feature requires classic confinement, document why and expect
additional review. Never silently disable enforcement when a permission is
missing; show the user which capability is unavailable.

Official reference:

- [Publish a snap](https://documentation.ubuntu.com/snapcraft/stable/how-to/publishing/publish-a-snap)

## 4. AUR: Arch Linux community repository

The Arch User Repository is a git repository of build recipes, not a binary
store. Users build the package locally with `makepkg`.

FocusFlow already has an AUR binary package template:

- `aur/PKGBUILD`
- `aur/.SRCINFO`

### What to do for each release

1. Wait until the matching GitHub release asset exists.
2. Update `pkgver` in `PKGBUILD`.
3. Update `.SRCINFO`.
4. Test the package with `makepkg` and inspect the generated package.
5. Push the package files to the AUR repository over SSH.
6. Watch user comments and update promptly when the release asset changes.

The package name uses the `-bin` convention because it downloads the
prebuilt AppImage. A source-built AUR package would use a different recipe
and should not pretend that it is source-built.

Official reference:

- [AUR submission guidelines](https://wiki.archlinux.org/title/AUR_submission_guidelines)

## 5. Fedora COPR: practical RPM distribution

COPR provides Fedora-family users with a repository maintained by the project.
It is faster to start than Fedora's official package review process.

### What to do

1. Create a COPR project.
2. Choose Fedora releases and architectures to build.
3. Add an RPM spec file or connect the project to a source build.
4. Build first in a test project or non-stable repository.
5. Install the RPM on a clean Fedora VM and test desktop integration.
6. Publish the successful build and document the repository enable command.
7. Update the spec and rebuild for every release.

Use COPR for early Fedora availability. Apply to Fedora's official
repositories only after the package is mature and has an active maintainer.

Official reference:

- [Publish RPM packages on COPR](https://docs.fedoraproject.org/en-US/quick-docs/publish-rpm-on-copr/)

## 6. Open Build Service: openSUSE and multi-distribution builds

Open Build Service can build packages for openSUSE and selected target
distributions from one project. It is useful when maintaining RPM and Debian
variants separately becomes repetitive.

### What to do

1. Create an OBS account and project.
2. Add the package source, spec file, and build instructions.
3. Select the target distributions and architectures.
4. Let OBS build the package for each target.
5. Fix target-specific dependency or policy errors.
6. Publish the repository and add installation instructions to the website.

Official reference:

- [OBS user guide](https://openbuildservice.org/help/manuals/obs-user-guide)
- [Publishing upstream binaries](https://openbuildservice.org/help/manuals/obs-user-guide/cha-obs-best-practices-upstream)

## 7. Launchpad PPA: Ubuntu apt repository

A PPA is a personal or organization-owned Ubuntu repository. It is not the
same as uploading a `.deb` file: Launchpad builds from a signed Debian source
package for selected Ubuntu series.

### What to do

1. Create a Launchpad account and PPA.
2. Create a proper `debian/` source package with control, rules, changelog,
   copyright, and source format files.
3. Create a GPG key and configure `debuild`/`dput`.
4. Build and sign the source package.
5. Upload it to the PPA for each supported Ubuntu series.
6. Wait for Launchpad builds and fix build-dependency errors.
7. Test installation and upgrades from a clean Ubuntu machine.

PPAs are useful for Ubuntu-focused users, but they require more Debian
packaging work than the current direct `.deb` release.

Official reference:

- [Launchpad PPA guide](https://help.launchpad.net/PPA)
- [Uploading to a PPA](https://help.launchpad.net/Packaging/PPA/Uploading)

## 8. Official Debian, Ubuntu, Fedora, and Arch repositories

These are the most trusted package sources, but they are not self-service
stores where a publisher uploads a binary.

- **Debian:** file an intent-to-package bug, prepare a policy-compliant source
  package, and find a Debian sponsor. Start with Debian Mentors.
- **Ubuntu:** Ubuntu packages normally come through Debian or Ubuntu's
  packaging and review processes. A PPA is the practical interim channel.
- **Fedora:** prepare a Fedora-compliant package and go through package review.
  COPR is the practical interim channel.
- **Arch:** submit to the official repositories through the Arch packaging
  process. AUR is the practical interim channel.

Official distribution packages should be a later goal, not the first launch
target. They require long-term maintenance, timely security updates, and a
maintainer who understands that distribution's policies.

Reference:

- [Debian Mentors: getting a package into Debian](https://mentors.debian.net/intro-maintainers/)
- [Debian Work-Needing and Prospective Packages](https://www.debian.org/devel/wnpp/)

## 9. Nixpkgs: NixOS and Nix users

Nixpkgs is a source-reviewed package collection rather than a graphical app
store. A package expression can make FocusFlow available to NixOS users and
users of the Nix package manager on other distributions.

This is worthwhile after the release process is stable. It requires a Nix
expression, hash updates for new releases, and pull-request review.

Reference:

- [Nixpkgs contributing guide](https://github.com/NixOS/nixpkgs/blob/master/CONTRIBUTING.md)

## 10. AppImage catalogs and direct distribution

AppImage has no single official central store equivalent to the Microsoft
Store. The reliable distribution pattern is:

1. Publish the AppImage on GitHub Releases.
2. Publish a checksum manifest beside it.
3. Provide a desktop entry and icon inside the AppImage.
4. Document update behavior and supported architectures.
5. Optionally submit or request a listing in community AppImage catalogs.

Treat third-party catalogs as discovery sites, not as the security source.
Users should still verify downloads against the project checksum and release
page.

Reference:

- [AppImage distribution documentation](https://docs.appimage.org/packaging-guide/distribution.html)
- [AppImage applications catalog](https://appimage.github.io/)

## Recommended FocusFlow rollout

### Now

- Keep GitHub Releases as the source of truth.
- Publish `.deb`, `.rpm`, and `.AppImage`.
- Keep `install.sh` and `SHA256SUMS`.
- Update the AUR `focusflow-bin` recipe after each release.

### Next

1. Add a Flatpak build and test which enforcement features survive sandboxing.
2. Add a Snap build and test confinement/interfaces on Ubuntu.
3. Add Fedora COPR once the RPM spec is stable.
4. Add OBS targets for openSUSE and other requested distributions.

### Later

- Launchpad PPA for Ubuntu users who want apt upgrades.
- Nixpkgs package.
- Fedora, Debian, Ubuntu, and Arch official repository submissions.

## Release checklist

- [ ] Version is identical in Gradle, package metadata, UI, crash reporter,
      changelog, and channel-specific recipes.
- [ ] GitHub package and smoke-test workflows are green.
- [ ] `.deb`, `.rpm`, and AppImage install on clean test machines.
- [ ] The AppImage is executable after every artifact download.
- [ ] Desktop entry, icon, and AppStream metadata are present.
- [ ] Checksums are generated from the final release files.
- [ ] The GitHub tag and release assets match the version.
- [ ] AUR `PKGBUILD` and `.SRCINFO` point to the new release.
- [ ] Flatpak and Snap permissions are tested instead of assumed.
- [ ] Installation and upgrade instructions are updated.
- [ ] Security and enforcement limitations are stated honestly for each
      packaging format.