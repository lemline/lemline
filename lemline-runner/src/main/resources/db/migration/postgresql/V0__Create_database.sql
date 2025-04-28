-- Verify database connection
-- This migration will fail if we can't connect to the configured database
DO $$
BEGIN
    -- Simple query to verify connection
    PERFORM 1;
EXCEPTION
    WHEN insufficient_privilege THEN
        RAISE EXCEPTION 'Insufficient privileges. Please ensure you have access to the configured database.';
    WHEN undefined_database THEN
        RAISE EXCEPTION 'Database does not exist. Please create it first.';
    WHEN others THEN
        RAISE EXCEPTION 'Database connection failed: %', SQLERRM;
END
$$; 