---
name: FocusFlow Replit desktop verification
description: Environment-specific build and preview constraints for this JVM desktop project.
---

The imported Gradle wrapper may not have its executable bit, while the configured Replit workflow uses system Gradle with the project’s Java 19 environment. The desktop workflow is VNC-based and can run successfully without exposing port 5000 to the browser screenshot tool.

**Why:** A wrapper invocation can fail before Gradle starts, and a browser screenshot connection refusal does not necessarily mean the Compose desktop application failed to launch.

**How to apply:** Use the configured system `gradle` command with the workflow Java environment for checks, inspect workflow logs/process state for desktop startup, and record browser screenshot unavailability as a VNC limitation rather than changing the app to add a web server.