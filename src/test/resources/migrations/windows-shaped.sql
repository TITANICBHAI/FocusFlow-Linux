INSERT INTO block_rules(id, process_name, display_name) VALUES ('windows-rule', 'Discord.exe', 'Discord');
INSERT INTO daily_allowances(process_name, display_name, allowance_minutes)
  VALUES ('Spotify.exe', 'Spotify', 30);
INSERT INTO settings(key, value) VALUES
  ('vpn_custom_processes', 'Discord.exe,Spotify.exe'),
  ('launcher_selected_apps', 'Discord.exe,Spotify.exe');
INSERT INTO focus_launcher_session
  (id, session_start_ms, session_end_ms, pin_hash)
  VALUES (1, 1000, 0, 'windows-shaped-test-hash');
INSERT INTO focus_launcher_session_apps(session_id, position, process_name, display_name, exe_path)
  VALUES (1, 0, 'Discord.exe', 'Discord', 'C:\Program Files\Discord\Discord.exe');