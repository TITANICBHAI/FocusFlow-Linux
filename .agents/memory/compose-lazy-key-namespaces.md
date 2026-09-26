---
name: Compose lazy-list key namespaces
description: Prevent duplicate-key crashes when one Compose lazy list contains multiple keyed item sections.
---

Compose lazy-list keys share one namespace across the entire `LazyColumn` or `LazyVerticalGrid`, not just within each `items`/`itemsIndexed` call. If multiple sections can contain the same domain identifier, give each section a distinct key prefix and keep a deterministic per-section uniqueness guard such as the item index.

**Why:** A key made from `processName + index` can still collide when two separate item blocks both have the same process at the same index. Compose throws during measurement and the desktop app crashes.

**How to apply:** Whenever a lazy list has multiple keyed sections, use namespaced keys such as `available_app_...` and `search_result_...`; audit the whole parent list rather than validating each block independently.