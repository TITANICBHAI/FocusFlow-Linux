---
name: FocusFlow Linux migration
description: Durable direction and guardrails for the planned Linux port.
---

FocusFlow's next major workstream is completing the Linux desktop port while keeping the production Windows implementation intact. The root-level Linux plan is the code-grounded source for findings and priorities; the HTML and Markdown trackers are the execution checklists.

**Why:** Linux support already has substantial platform-specific code, but the remaining gaps affect build readiness, enforcement reliability, Wayland security limitations, desktop integration, Linux-facing UI text, and CI coverage. Replacing Windows paths would risk the live Windows product.

**How to apply:** Start with the five tracked phases in order, add Linux behavior beside guarded Windows behavior, and do not call the migration complete until the X11, Wayland, packaging, desktop-environment, and Windows regression checks are covered. Post-ship work includes AppImage updates, native Wayland kiosk integration, and Snap packaging.