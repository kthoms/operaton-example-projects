-- Single shared database with per-service schemas and users.
-- Runs automatically via /docker-entrypoint-initdb.d/ in the platform database.

CREATE SCHEMA operaton;
CREATE SCHEMA keycloak;
CREATE SCHEMA flowset;

CREATE USER operaton WITH PASSWORD 'operaton';
CREATE USER keycloak WITH PASSWORD 'keycloak';
CREATE USER flowset WITH PASSWORD 'flowset';

GRANT ALL PRIVILEGES ON SCHEMA operaton TO operaton;
GRANT ALL PRIVILEGES ON SCHEMA keycloak TO keycloak;
GRANT ALL PRIVILEGES ON SCHEMA flowset TO flowset;

ALTER USER operaton SET search_path TO operaton;
ALTER USER keycloak SET search_path TO keycloak;
ALTER USER flowset SET search_path TO flowset;
