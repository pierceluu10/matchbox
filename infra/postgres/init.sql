-- One database per service on a single Postgres instance; services never share tables.
CREATE DATABASE payments;
CREATE DATABASE reconciliation;
CREATE DATABASE exceptions;
