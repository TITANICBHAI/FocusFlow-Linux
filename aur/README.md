# FocusFlow AUR Package

This directory contains the `PKGBUILD` for the [Arch User Repository](https://aur.archlinux.org/) package `focusflow-bin`.

Once submitted, Arch Linux users can install FocusFlow with:

```bash
yay -S focusflow-bin
# or
paru -S focusflow-bin
```

---

## Submitting to the AUR (one-time setup)

**You need to do this — it requires an AUR account and SSH key.**

1. Create an account at https://aur.archlinux.org/register/
2. Add your SSH public key at https://aur.archlinux.org/account/ → Edit Account → SSH Public Key
3. Clone the (empty) AUR package repo:
   ```bash
   git clone ssh://aur@aur.archlinux.org/focusflow-bin.git aur-focusflow
   ```
4. Copy `PKGBUILD` and `.SRCINFO` into `aur-focusflow/`
5. Commit and push:
   ```bash
   cd aur-focusflow
   cp /path/to/this/repo/aur/PKGBUILD .
   cp /path/to/this/repo/aur/.SRCINFO .
   git add PKGBUILD .SRCINFO
   git commit -m "Initial release v2.0.1"
   git push
   ```

---

## Updating for a new release

1. Bump `pkgver` in `PKGBUILD` and `.SRCINFO` to the new version.
2. Update the versioned `source=` URLs in both files.
3. Confirm the release contains both the AppImage and `SHA256SUMS`. The
   `prepare()` function verifies the AppImage against that manifest before
   package creation; it intentionally does not disable integrity checking.
4. Push to the AUR:
   ```bash
   cd aur-focusflow
   # copy updated PKGBUILD + .SRCINFO
   git add -A && git commit -m "Update to vX.Y.Z" && git push
   ```

---

## Testing the PKGBUILD locally (on Arch)

```bash
cd aur/
makepkg -si
```

This builds and installs the package locally without needing to push to the AUR first.
The versioned GitHub release must already exist and publish its `SHA256SUMS`
manifest; a missing or mismatched entry stops the build.
