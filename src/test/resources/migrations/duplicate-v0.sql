PRAGMA user_version = 0;
CREATE TABLE block_rules (id TEXT, process_name TEXT, display_name TEXT, enabled INTEGER, block_network INTEGER);
CREATE TABLE daily_allowances (process_name TEXT, display_name TEXT, allowance_minutes INTEGER);
CREATE TABLE settings (key TEXT, value TEXT);
INSERT INTO block_rules VALUES ('duplicate-1', 'Discord.exe', 'Discord', 1, 0);
INSERT INTO block_rules VALUES ('duplicate-2', 'discord.exe', 'Discord duplicate', 1, 0);
INSERT INTO daily_allowances VALUES ('Spotify.exe', 'Spotify', 30);
INSERT INTO daily_allowances VALUES ('spotify.exe', 'Spotify duplicate', 45);
INSERT INTO settings VALUES ('unknown_future_key', 'preserve-me');