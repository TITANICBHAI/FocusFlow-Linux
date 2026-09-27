INSERT INTO tasks(id, title, tags, focus_blocked_apps, created_at) VALUES
  ('malformed-task', 'Malformed fixture', 'one,,two,', 'firefox.exe,, ,bad', '2026-09-27T00:00:00');
INSERT INTO block_schedules(id, name, days_of_week, start_hour, start_minute, end_hour, end_minute, process_names)
  VALUES ('malformed-schedule', 'Malformed schedule', '1,x,,7', 9, 0, 10, 0, 'missing.exe,,');
INSERT INTO settings(key, value) VALUES ('vpn_custom_processes', 'vpn.exe,, ,broken');