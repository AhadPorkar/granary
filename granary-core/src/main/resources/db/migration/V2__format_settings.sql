-- Format-specific repository settings (a JSON object of string values), declared per format.
ALTER TABLE repositories ADD COLUMN format_settings TEXT;
