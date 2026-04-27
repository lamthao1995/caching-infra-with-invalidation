CREATE DATABASE IF NOT EXISTS catalog
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE catalog;

CREATE TABLE IF NOT EXISTS items (
  id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  name         VARCHAR(200) NOT NULL,
  description  VARCHAR(2000),
  price_cents  BIGINT       NOT NULL DEFAULT 0,
  currency     CHAR(3)      NOT NULL DEFAULT 'USD',
  stock        INT,
  created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_items_updated_at (updated_at)
) ENGINE=InnoDB;

-- Permissions required by the binlog client (Debezium / Kafka Connect).
GRANT SELECT, RELOAD, SHOW DATABASES,
      REPLICATION SLAVE, REPLICATION CLIENT, LOCK TABLES
  ON *.* TO 'app'@'%';
GRANT ALL PRIVILEGES ON catalog.* TO 'app'@'%';
FLUSH PRIVILEGES;
