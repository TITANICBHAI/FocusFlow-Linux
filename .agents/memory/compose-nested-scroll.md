---
name: Compose nested scroll containers
description: Avoid placing a vertically scrolling picker inside a parent LazyColumn on desktop Compose.
---

## The Rule
Components that render a vertical `LazyColumn` must provide a non-scrolling rendering mode when embedded inside another vertical scroller. The parent list must own scrolling in that composition.

**Why:** Compose measures the nested list with an infinite height and throws `IllegalStateException` before the screen can render.

**How to apply:** For picker/list components embedded as a parent-list item, render their rows in a regular `Column` and keep the standalone use case lazy and scrollable.