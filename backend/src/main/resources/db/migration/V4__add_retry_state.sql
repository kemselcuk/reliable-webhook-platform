ALTER TABLE deliveries
    ADD COLUMN run_attempt_count INTEGER NOT NULL DEFAULT 0;

-- A pre-V4 RETRY_SCHEDULED row may not have had a due time. Preserve it as
-- immediately due by using its last update time before adding the constraint.
UPDATE deliveries
SET next_retry_at = COALESCE(next_retry_at, updated_at)
WHERE status = 'RETRY_SCHEDULED';

ALTER TABLE deliveries
    ADD CONSTRAINT deliveries_run_attempt_count_check
        CHECK (run_attempt_count >= 0 AND run_attempt_count <= attempt_count),
    ADD CONSTRAINT deliveries_retry_schedule_state_check
        CHECK (
            (status = 'RETRY_SCHEDULED' AND next_retry_at IS NOT NULL)
            OR (status <> 'RETRY_SCHEDULED' AND next_retry_at IS NULL)
        );
