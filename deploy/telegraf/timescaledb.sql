-- Optional: database, user and hypertable for BimmerData, if you do not let Telegraf create them.
-- Run as a PostgreSQL superuser:  psql -f timescaledb.sql
-- (Telegraf's postgresql output creates the table and adds missing columns by itself.)

CREATE DATABASE bimmerdata;
\c bimmerdata
CREATE EXTENSION IF NOT EXISTS timescaledb;

CREATE ROLE telegraf LOGIN PASSWORD 'change-me';
GRANT CREATE, USAGE ON SCHEMA public TO telegraf;

CREATE TABLE IF NOT EXISTS bimmerdata (
    time  timestamptz NOT NULL,
    car   text,
    ecu   text,
    id    text,
    label text,
    unit  text,
    value double precision,
    text  text
);
SELECT create_hypertable('bimmerdata', by_range('time', INTERVAL '7 days'), if_not_exists => true);
CREATE INDEX IF NOT EXISTS bimmerdata_car_id_time ON bimmerdata (car, ecu, id, time DESC);
ALTER TABLE bimmerdata OWNER TO telegraf;

-- Optional: compress chunks older than 30 days (TimescaleDB 2.x)
-- ALTER TABLE bimmerdata SET (timescaledb.compress, timescaledb.compress_segmentby = 'car, ecu, id');
-- SELECT add_compression_policy('bimmerdata', INTERVAL '30 days');
