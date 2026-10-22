-- handovers.excluded_count was declared in V1 but no code ever wrote it, so every handover reported 0. The warning it
-- was meant to carry (in-scope records still awaiting confirmation) is HandoverDetail.unconfirmedRecords, computed per
-- request with the reader's visibility rules. The column and the API field go away together.
ALTER TABLE handovers DROP COLUMN excluded_count;
