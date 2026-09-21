#!/usr/bin/env bash
# FocusFlow Linux installer
# Usage: curl -fsSL https://raw.githubusercontent.com/TITANICBHAI/FocusFlow-Linux/main/install.sh | bash
set -euo pipefail

REPO="TITANICBHAI/FocusFlow-Linux"
INSTALL_DIR="$HOME/.local/share/focusflow"
BIN_DIR="$HOME/.local/bin"
DESKTOP_DIR="$HOME/.local/share/applications"

# ── Helpers ────────────────────────────────────────────────────────────────────
info()  { echo -e "\033[1;34m[focusflow]\033[0m $*"; }
ok()    { echo -e "\033[1;32m[focusflow]\033[0m $*"; }
warn()  { echo -e "\033[1;33m[focusflow]\033[0m $*"; }
die()   { echo -e "\033[1;31m[focusflow]\033[0m $*" >&2; exit 1; }

need() { command -v "$1" &>/dev/null || die "Required tool not found: $1. Please install it first."; }
need curl
need grep
need sha256sum

# ── Detect distro ──────────────────────────────────────────────────────────────
PKG_TYPE=""
if command -v dpkg &>/dev/null; then
  PKG_TYPE="deb"
elif command -v rpm &>/dev/null; then
  PKG_TYPE="rpm"
else
  PKG_TYPE="appimage"
fi

info "Detected package type: $PKG_TYPE"

# ── Fetch latest release ───────────────────────────────────────────────────────
info "Fetching latest release from GitHub..."
RELEASE_JSON=$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest")
VERSION=$(echo "$RELEASE_JSON" | grep '"tag_name"' | head -1 | sed 's/.*"tag_name": *"\([^"]*\)".*/\1/')
[ -z "$VERSION" ] && die "Could not determine latest release version."
info "Latest release: $VERSION"

# ── Find the right asset ───────────────────────────────────────────────────────
case "$PKG_TYPE" in
  deb)
    ASSET_URL=$(echo "$RELEASE_JSON" | grep '"browser_download_url"' | grep '\.deb"' | head -1 | sed 's/.*"browser_download_url": *"\([^"]*\)".*/\1/')
    ;;
  rpm)
    ASSET_URL=$(echo "$RELEASE_JSON" | grep '"browser_download_url"' | grep '\.rpm"' | head -1 | sed 's/.*"browser_download_url": *"\([^"]*\)".*/\1/')
    ;;
  appimage)
    ASSET_URL=$(echo "$RELEASE_JSON" | grep '"browser_download_url"' | grep '\.AppImage"' | head -1 | sed 's/.*"browser_download_url": *"\([^"]*\)".*/\1/')
    ;;
esac

[ -z "$ASSET_URL" ] && die "No $PKG_TYPE asset found in release $VERSION. Try again after the release assets are uploaded."

FILENAME=$(basename "$ASSET_URL")
TMPDIR=$(mktemp -d "${TMPDIR:-/tmp}/focusflow-install.XXXXXX")
TMPFILE="$TMPDIR/$FILENAME"
CHECKSUM_FILE="$TMPDIR/SHA256SUMS"
cleanup() {
  rm -rf "$TMPDIR"
}
trap cleanup EXIT

# ── Download ───────────────────────────────────────────────────────────────────
info "Downloading $FILENAME..."
curl -fsSL --progress-bar -o "$TMPFILE" "$ASSET_URL"

# ── Verify release checksum ─────────────────────────────────────────────────────
CHECKSUM_URL="https://github.com/$REPO/releases/download/$VERSION/SHA256SUMS"
info "Verifying SHA-256 checksum..."
curl -fsSL -o "$CHECKSUM_FILE" "$CHECKSUM_URL" \
  || die "Release $VERSION has no downloadable SHA256SUMS manifest."
EXPECTED_CHECKSUM=$(awk -v file="$FILENAME" '
  $2 == file || $2 == "*" file { print $1; exit }
' "$CHECKSUM_FILE")
[ -n "$EXPECTED_CHECKSUM" ] \
  || die "SHA256SUMS does not contain an entry for $FILENAME."
printf '%s  %s\n' "$EXPECTED_CHECKSUM" "$TMPFILE" | sha256sum -c - \
  || die "Checksum verification failed for $FILENAME."
ok "Checksum verified."

# ── Install ────────────────────────────────────────────────────────────────────
case "$PKG_TYPE" in
  deb)
    info "Installing .deb package (requires sudo)..."
    sudo dpkg -i "$TMPFILE" || sudo apt-get install -f -y
    ok "FocusFlow $VERSION installed. Run: focusflow"
    ;;
  rpm)
    info "Installing .rpm package (requires sudo)..."
    if command -v dnf &>/dev/null; then
      sudo dnf install -y "$TMPFILE"
    else
      sudo rpm -U --replacepkgs "$TMPFILE"
    fi
    ok "FocusFlow $VERSION installed. Run: focusflow"
    ;;
  appimage)
    info "Installing AppImage to $INSTALL_DIR ..."
    mkdir -p "$INSTALL_DIR" "$BIN_DIR" "$DESKTOP_DIR"
    install -m 0755 "$TMPFILE" "$INSTALL_DIR/FocusFlow.AppImage"

    # Launcher shim
    cat > "$BIN_DIR/focusflow" <<SHIM
#!/usr/bin/env bash
exec "$INSTALL_DIR/FocusFlow.AppImage" "\$@"
SHIM
    chmod +x "$BIN_DIR/focusflow"

    # .desktop file
    cat > "$DESKTOP_DIR/focusflow.desktop" <<DESKTOP
[Desktop Entry]
Name=FocusFlow
Comment=Focus & productivity app with real app blocking
Exec="$BIN_DIR/focusflow"
Icon=$INSTALL_DIR/focusflow.png
Type=Application
Categories=Utility;
StartupNotify=true
DESKTOP
    chmod +x "$DESKTOP_DIR/focusflow.desktop"

    # Extract the validated icon into the XDG icon path used above. Keep the
    # extraction in the temporary directory so repeated installs leave no
    # squashfs-root debris in the caller's working directory.
    if ! (cd "$TMPDIR" && APPIMAGE_EXTRACT_AND_RUN=1 \
      "$INSTALL_DIR/FocusFlow.AppImage" --appimage-extract >/dev/null); then
      die "Could not extract the FocusFlow icon from the AppImage."
    fi
    ICON_SOURCE=$(find "$TMPDIR/squashfs-root" -type f \
      \( -iname 'focusflow.png' -o -iname 'focusflow.svg' \) -print -quit)
    [ -n "$ICON_SOURCE" ] || die "The AppImage does not contain a FocusFlow icon."
    install -m 0644 "$ICON_SOURCE" "$INSTALL_DIR/focusflow.png"

    # Add ~/.local/bin to PATH hint
    if [[ ":$PATH:" != *":$BIN_DIR:"* ]]; then
      warn "$BIN_DIR is not in your PATH."
      warn "Add this to your ~/.bashrc or ~/.zshrc:"
      warn "  export PATH=\"\$HOME/.local/bin:\$PATH\""
    fi

    ok "FocusFlow $VERSION installed."
    ok "Launch from your app menu, or run: focusflow"
    ;;
esac

# ── Optional deps reminder ─────────────────────────────────────────────────────
if [ "$PKG_TYPE" = "appimage" ]; then
  echo ""
  info "Optional tools for full feature coverage:"
  command -v xdotool  &>/dev/null || warn "  xdotool missing  → install: sudo apt install xdotool   (window focus detection on X11)"
  command -v notify-send &>/dev/null || warn "  notify-send missing → install: sudo apt install libnotify-bin (desktop notifications)"
fi
ok "Done."
