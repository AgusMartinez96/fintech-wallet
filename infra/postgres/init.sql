-- Se ejecuta solo con el volumen vacío (docker compose down -v para reaplicar).
-- Las contraseñas se leen de variables de entorno del contenedor (psql 15+): sin secretos en el repo.
\getenv core_pw CORE_DB_PASSWORD
\getenv tx_pw TX_DB_PASSWORD
\getenv fraud_pw FRAUD_DB_PASSWORD

CREATE ROLE core_user LOGIN PASSWORD :'core_pw';
CREATE ROLE tx_user LOGIN PASSWORD :'tx_pw';
CREATE ROLE fraud_user LOGIN PASSWORD :'fraud_pw';

CREATE DATABASE core_db OWNER core_user;
CREATE DATABASE tx_db OWNER tx_user;
CREATE DATABASE fraud_db OWNER fraud_user;

REVOKE CONNECT ON DATABASE core_db FROM PUBLIC;
REVOKE CONNECT ON DATABASE tx_db FROM PUBLIC;
REVOKE CONNECT ON DATABASE fraud_db FROM PUBLIC;

GRANT CONNECT ON DATABASE core_db TO core_user;
GRANT CONNECT ON DATABASE tx_db TO tx_user;
GRANT CONNECT ON DATABASE fraud_db TO fraud_user;
