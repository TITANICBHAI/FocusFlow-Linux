---
name: Protected Replit config edits
description: The validated replacement path for protected .replit configuration files.
---

When direct edits to `.replit` are rejected, write the complete intended TOML to a temporary file inside the workspace, then call `verifyAndReplaceDotReplit` from CodeExecution with that file's absolute path. Remove the temporary file afterward and verify the resulting workspace state.

**Why:** Direct file patching is blocked so Replit can validate its configuration schema before replacement.

**How to apply:** Use the validator instead of retrying direct edits whenever a protected `.replit` change is actually needed.