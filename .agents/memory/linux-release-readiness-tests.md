---
name: Linux release-readiness tests
description: Privileged Linux integration checks are opt-in and must run only in disposable environments.
---

Privileged hosts and firewall integration tests must default to non-destructive skips and require an explicit disposable-environment flag; cleanup belongs in `finally` blocks.

**Why:** `/etc/hosts`, PolicyKit, and iptables are machine-wide resources, and the normal Replit container cannot prove their real success or cancellation behavior safely.

**How to apply:** Keep parser, resolver, timeout, lifecycle, and quoting checks runnable in the regular suite. Run real hosts/firewall checks only with the documented opt-in variables on a disposable Linux VM/container, then record the distro, session, and tool results.