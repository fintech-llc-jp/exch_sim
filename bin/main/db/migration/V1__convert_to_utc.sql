-- Convert existing LocalTime data to UTC
-- This assumes the existing data was recorded in JST (UTC+9)
-- Adjust the offset based on your actual system timezone

UPDATE executions 
SET created_at = DATEADD('HOUR', -9, created_at)
WHERE created_at IS NOT NULL;