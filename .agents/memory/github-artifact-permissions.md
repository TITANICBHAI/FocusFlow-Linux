---
name: GitHub artifact executable permissions
description: Executable file modes can be lost when packaging files move through GitHub Actions artifacts.
---

GitHub Actions artifact upload/download should be treated as not preserving executable permissions for standalone binaries such as AppImages.

**Why:** A package build produced a valid executable AppImage, but the downstream validation job received it without the execute bit and failed before inspecting its contents.

**How to apply:** Restore required modes explicitly after `actions/download-artifact` and before validation or release packaging; do not rely on the source file mode surviving artifact transfer.