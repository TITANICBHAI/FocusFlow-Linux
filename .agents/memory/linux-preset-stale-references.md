---
name: Linux preset stale references
description: Why missing Linux onboarding preset entries remain persisted instead of being dropped.
---

Linux onboarding presets intentionally retain a normalized process reference when the installed-app catalog cannot resolve it.

**Why:** The preset selection must survive an incomplete catalog scan or a later app installation; dropping the entry silently changes the user's onboarding choice. Existing tests rely on this contract.

**How to apply:** Keep missing references visible as stale entries, but normalize known Windows-shaped values before persistence. Do not replace this with “drop every missing app” behavior without revising the product contract and tests.