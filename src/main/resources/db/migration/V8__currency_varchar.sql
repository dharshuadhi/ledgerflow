-- V8: align currency columns with the JPA mapping.
--
-- V1..V6 declared currency as CHAR(3); Hibernate maps the entity fields to
-- VARCHAR(3) and ddl-auto=validate rejects the mismatch (bpchar vs varchar).
-- VARCHAR is also the better type here: CHAR pads values with spaces, which
-- is a perennial source of subtle comparison bugs. New migration rather than
-- editing V1..V6: applied migrations are immutable.

ALTER TABLE accounts            ALTER COLUMN currency         TYPE VARCHAR(3);
ALTER TABLE postings            ALTER COLUMN currency         TYPE VARCHAR(3);
ALTER TABLE settlements         ALTER COLUMN currency         TYPE VARCHAR(3);
ALTER TABLE balance_projections ALTER COLUMN currency         TYPE VARCHAR(3);
ALTER TABLE fx_rates            ALTER COLUMN base_currency    TYPE VARCHAR(3);
ALTER TABLE fx_rates            ALTER COLUMN quote_currency   TYPE VARCHAR(3);
