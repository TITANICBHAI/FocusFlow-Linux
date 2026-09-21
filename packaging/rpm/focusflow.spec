Name:           focusflow
Version:        2.0.1
Release:        1%{?dist}
Summary:        Focus and productivity app with real app blocking
License:        GPL-2.0-or-later
URL:            https://github.com/TITANICBHAI/FocusFlow-Linux
Source0:        https://github.com/TITANICBHAI/FocusFlow-Linux/releases/download/v%{version}/FocusFlow-%{version}-x86_64.AppImage
Source1:        https://raw.githubusercontent.com/TITANICBHAI/FocusFlow-Linux/v%{version}/packaging/flatpak/com.focusflow.FocusFlow.desktop
Source2:        https://raw.githubusercontent.com/TITANICBHAI/FocusFlow-Linux/v%{version}/src/main/resources/focusflow_256.png
BuildArch:      x86_64
Requires:       xdotool
Requires:       wmctrl

%description
FocusFlow is a local-first focus timer and distraction blocker for Linux.

%prep

%build

%install
install -D -m 0755 %{SOURCE0} %{buildroot}%{_libexecdir}/focusflow/FocusFlow.AppImage
install -D -m 0644 %{SOURCE1} %{buildroot}%{_datadir}/applications/com.focusflow.FocusFlow.desktop
install -D -m 0644 %{SOURCE2} %{buildroot}%{_datadir}/icons/hicolor/256x256/apps/com.focusflow.FocusFlow.png
install -d %{buildroot}%{_bindir}
cat > %{buildroot}%{_bindir}/focusflow <<'EOF'
#!/bin/sh
exec /usr/libexec/focusflow/FocusFlow.AppImage "$@"
EOF
chmod 0755 %{buildroot}%{_bindir}/focusflow

%files
%{_bindir}/focusflow
%{_libexecdir}/focusflow/FocusFlow.AppImage
%{_datadir}/applications/com.focusflow.FocusFlow.desktop
%{_datadir}/icons/hicolor/256x256/apps/com.focusflow.FocusFlow.png

%changelog
* Mon Sep 21 2026 TBTechs <support@tbtechs.dev> - 2.0.1-1
- Add a Fedora-family RPM recipe backed by the signed GitHub release asset.