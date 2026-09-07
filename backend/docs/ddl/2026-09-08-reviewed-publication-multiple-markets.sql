-- A second provider callback must never authorize another creation POST for the same reviewed task.
ALTER TABLE sb_market_publication_task ADD COLUMN IF NOT EXISTS post_authorized boolean NOT NULL DEFAULT false;
UPDATE sb_market_publication_task SET post_authorized=true WHERE post_authorized=false
 AND state IN ('POST_STARTED','VERIFY','AWAITING_APPROVAL','REGISTERED','UNKNOWN_CREATE','ACTION_REQUIRED');

ALTER TABLE sb_market_publication_task ADD COLUMN IF NOT EXISTS setup_writes integer NOT NULL DEFAULT 0;
