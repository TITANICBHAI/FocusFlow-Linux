INSERT INTO block_rules(id, process_name, display_name) VALUES ('stale-rule', 'removed-app.exe', 'Removed app');
INSERT INTO block_schedules(id, name, days_of_week, start_hour, start_minute, end_hour, end_minute, process_names)
  VALUES ('stale-schedule', 'Stale schedule', '1,2,3', 9, 0, 10, 0, 'removed-app.exe,current-app');
INSERT INTO tasks(id, title, focus_blocked_apps, created_at) VALUES
  ('stale-task', 'Stale focus task', 'removed-app.exe,current-app', '2026-09-27T00:00:00');
INSERT INTO network_cutoff_rules(id, pattern, mode, target_process, target_display_name)
  VALUES ('stale-network', 'example.test', 'DOMAIN', 'removed-app.exe', 'Removed app');
INSERT INTO custom_block_presets(id, name, emoji, process_names, created_at)
  VALUES ('stale-preset', 'Stale preset', '🚫', 'removed-app.exe', '2026-09-27T00:00:00');
INSERT INTO focus_launcher_presets(id, name, process_names, created_at)
  VALUES ('stale-launcher', 'Stale launcher', 'removed-app.exe', '2026-09-27T00:00:00');