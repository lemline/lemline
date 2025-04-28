-- Verify database connection
-- This migration will fail if we can't connect to the configured database
-- Error messages will be shown in the application logs
SELECT 1;
-- If this fails, it means either:
-- 1. The database doesn't exist
-- 2. We don't have permission to access it
-- 3. The connection details are incorrect
-- Please ensure the database exists and you have the necessary permissions 