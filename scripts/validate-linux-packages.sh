#!/usr/bin/env bash
# Validate the Linux release outputs before they can be uploaded.
set -euo pipefail

OUT_ROOT="${1:?usage: validate-linux-packages.sh <compose-output-root> <version>}"
EXPECTED_VERSION="${2:?usage: validate-linux-packages.sh <compose-output-root> <version>}"

die() {
  echo "::error::$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "Required validation command is missing: $1"
}

require_command dpkg-deb
require_command rpm
require_command rpm2cpio
require_command cpio
require_command file

shopt -s nullglob
debs=("$OUT_ROOT/deb"/*.deb)
rpms=("$OUT_ROOT/rpm"/*.rpm)
appimages=("$OUT_ROOT/appImage"/*.AppImage)

[[ ${#debs[@]} -eq 1 ]] || die "Expected exactly one .deb, found ${#debs[@]}"
[[ ${#rpms[@]} -eq 1 ]] || die "Expected exactly one .rpm, found ${#rpms[@]}"
[[ ${#appimages[@]} -eq 1 ]] || die "Expected exactly one AppImage, found ${#appimages[@]}"

validate_desktop_and_icon() {
  local root="$1"
  local desktop
  desktop="$(find "$root/usr/share/applications" "$root" -type f -name '*.desktop' -print -quit 2>/dev/null || true)"
  [[ -n "$desktop" ]] || die "No desktop entry found in package payload"
  grep -Eq '^Exec=[^[:space:]]' "$desktop" || die "Desktop entry has no usable Exec= value: $desktop"
  grep -Eq '^Icon=[^[:space:]]' "$desktop" || die "Desktop entry has no usable Icon= value: $desktop"
  find "$root" -type f \( -iname '*.png' -o -iname '*.svg' -o -iname '*.xpm' \) -print -quit | grep -q . \
    || die "No icon file found in package payload"
}

echo "Validating Debian package: ${debs[0]}"
deb_root=""
rpm_root=""
app_root=""
deb_package="$(dpkg-deb -f "${debs[0]}" Package)"
deb_version="$(dpkg-deb -f "${debs[0]}" Version)"
deb_arch="$(dpkg-deb -f "${debs[0]}" Architecture)"
deb_depends="$(dpkg-deb -f "${debs[0]}" Depends)"
[[ "$deb_package" == "focusflow" ]] || die "Unexpected .deb package name: $deb_package"
[[ "$deb_version" == "$EXPECTED_VERSION" ]] || die ".deb version $deb_version != $EXPECTED_VERSION"
[[ "$deb_arch" == "amd64" ]] || die ".deb architecture $deb_arch != amd64"
[[ "$deb_depends" == *xdotool* && "$deb_depends" == *wmctrl* ]] \
  || die ".deb dependencies must include xdotool and wmctrl: $deb_depends"
deb_root="$(mktemp -d)"
trap 'rm -rf "$deb_root" "$rpm_root" "$app_root"' EXIT
dpkg-deb -x "${debs[0]}" "$deb_root"
validate_desktop_and_icon "$deb_root"

echo "Validating RPM package: ${rpms[0]}"
rpm_package="$(rpm -qp --queryformat '%{NAME}' "${rpms[0]}")"
rpm_version="$(rpm -qp --queryformat '%{VERSION}' "${rpms[0]}")"
rpm_arch="$(rpm -qp --queryformat '%{ARCH}' "${rpms[0]}")"
rpm_requires="$(rpm -qp --requires "${rpms[0]}")"
[[ "$rpm_package" == "focusflow" ]] || die "Unexpected .rpm package name: $rpm_package"
[[ "$rpm_version" == "$EXPECTED_VERSION" ]] || die ".rpm version $rpm_version != $EXPECTED_VERSION"
[[ "$rpm_arch" == "x86_64" ]] || die ".rpm architecture $rpm_arch != x86_64"
printf '%s\n' "$rpm_requires" | grep -qx 'xdotool' || die ".rpm dependencies do not include xdotool"
printf '%s\n' "$rpm_requires" | grep -qx 'wmctrl' || die ".rpm dependencies do not include wmctrl"
rpm_root="$(mktemp -d)"
(
  cd "$rpm_root"
  rpm2cpio "${rpms[0]}" | cpio -idm --quiet
)
validate_desktop_and_icon "$rpm_root"

echo "Validating AppImage: ${appimages[0]}"
[[ -x "${appimages[0]}" ]] || die "AppImage is not executable"
file "${appimages[0]}" | grep -Eqi 'x86-64|amd64' || die "AppImage is not an x86_64 executable"
app_root="$(mktemp -d)"
(
  cd "$app_root"
  APPIMAGE_EXTRACT_AND_RUN=1 "${appimages[0]}" --appimage-extract >/dev/null
)
[[ -d "$app_root/squashfs-root" ]] || die "AppImage extraction did not produce squashfs-root"
validate_desktop_and_icon "$app_root/squashfs-root"

echo "Linux package validation passed for version $EXPECTED_VERSION"