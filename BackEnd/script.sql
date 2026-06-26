-- SQL limpo do banco zentrix_web
-- Estrutura das tabelas preservada
-- Inserções mantidas

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE DATABASE IF NOT EXISTS `zentrix_web` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `zentrix_web`;

DROP TABLE IF EXISTS `activation_codes`;
CREATE TABLE `activation_codes` (
  `code` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `tenant_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `store_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `store_name` varchar(180) COLLATE utf8mb4_unicode_ci NOT NULL,
  `source_id` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ACTIVE',
  `expires_at` datetime NOT NULL,
  `used_at` datetime DEFAULT NULL,
  `used_device_id` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`code`),
  KEY `idx_activation_codes_scope` (`tenant_id`,`store_id`,`status`),
  KEY `idx_activation_codes_expires` (`status`,`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `activation_codes` VALUES ('340832','7d5b16df-f1f1-4003-a6d8-0c30ba29570e','71320070-b210-4848-860c-38df600f996e','LC Multimarcas','LC-MULTIMARCAS-CAIXA-01','USED','2026-06-22 15:17:47','2026-06-15 15:50:10','771c6df1-0c9a-4493-a6e7-10edd6e07bee','2026-06-15 15:17:46','2026-06-15 15:50:10');

DROP TABLE IF EXISTS `app_settings`;
CREATE TABLE `app_settings` (
  `tenant_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `store_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'all',
  `setting_key` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL,
  `setting_value` text COLLATE utf8mb4_unicode_ci,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`tenant_id`,`store_id`,`setting_key`),
  KEY `idx_app_settings_scope` (`tenant_id`,`store_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `audit_log`;
CREATE TABLE `audit_log` (
  `tenant_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'legacy',
  `store_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'WEB',
  `device_id` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_id` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'WEB',
  `id` int NOT NULL,
  `usuario` varchar(80) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `acao` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `entity_type` varchar(80) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `entity_id` varchar(80) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `details` text COLLATE utf8mb4_unicode_ci,
  `created_at` datetime DEFAULT NULL,
  `risk_level` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `previous_value` text COLLATE utf8mb4_unicode_ci,
  `new_value` text COLLATE utf8mb4_unicode_ci,
  `reason` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `origin` varchar(80) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `ip_address` varchar(80) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `user_role` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`tenant_id`,`store_id`,`id`),
  KEY `idx_audit_log_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `audit_log` VALUES ('7d5b16df-f1f1-4003-a6d8-0c30ba29570e','71320070-b210-4848-860c-38df600f996e','771c6df1-0c9a-4493-a6e7-10edd6e07bee','LC Multimarcas',1,'PDV','SYNC_SUCCESS','sync_runs','36','Sincronização recebida com 6 registro(s).','2026-06-18 00:19:16','INFO',NULL,'{\"users\":0,\"suppliers\":0,\"clients\":0,\"products\":2,\"stock_movements\":0,\"cash_sessions\":1,\"cash_movements\":0,\"sales\":1,\"sale_items\":2,\"sale_cancellations\":0,\"comandas\":0,\"comanda_itens\":0,\"audit_log\":0}',NULL,'PDV',NULL,NULL),('7d5b16df-f1f1-4003-a6d8-0c30ba29570e','71320070-b210-4848-860c-38df600f996e','legacy-device','LC Multimarcas',2,'admin','LOGIN_SUCCESS','users','admin','Login realizado com sucesso.','2026-06-18 00:36:40','INFO',NULL,NULL,NULL,'APPGESTAO',NULL,'ADMIN'),('7d5b16df-f1f1-4003-a6d8-0c30ba29570e','71320070-b210-4848-860c-38df600f996e','771c6df1-0c9a-4493-a6e7-10edd6e07bee','LC Multimarcas',3,'PDV','SYNC_SUCCESS','sync_runs','37','Sincronização recebida com 8 registro(s).','2026-06-18 00:39:30','INFO',NULL,'{\"users\":0,\"suppliers\":0,\"clients\":0,\"products\":3,\"stock_movements\":0,\"cash_sessions\":1,\"cash_movements\":0,\"sales\":1,\"sale_items\":3,\"sale_cancellations\":0,\"comandas\":0,\"comanda_itens\":0,\"audit_log\":0}',NULL,'PDV',NULL,NULL),('7d5b16df-f1f1-4003-a6d8-0c30ba29570e','71320070-b210-4848-860c-38df600f996e','771c6df1-0c9a-4493-a6e7-10edd6e07bee','LC Multimarcas',4,'PDV','SYNC_SUCCESS','sync_runs','38','Sincronização recebida com 2 registro(s).','2026-06-18 00:44:11','INFO',NULL,'{\"users\":0,\"suppliers\":0,\"clients\":0,\"products\":0,\"stock_movements\":0,\"cash_sessions\":1,\"cash_movements\":1,\"sales\":0,\"sale_items\":0,\"sale_cancellations\":0,\"comandas\":0,\"comanda_itens\":0,\"audit_log\":0}',NULL,'PDV',NULL,NULL),('7d5b16df-f1f1-4003-a6d8-0c30ba29570e','71320070-b210-4848-860c-38df600f996e','legacy-device','LC Multimarcas',5,'admin','LOGIN_SUCCESS','users','admin','Login realizado com sucesso.','2026-06-18 00:45:00','INFO',NULL,NULL,NULL,'APPGESTAO',NULL,'ADMIN'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',1,'admin','SYNC_TEST','API','10482','Lote de teste recebido','2026-06-10 14:17:00',NULL,NULL,NULL,NULL,NULL,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',2,'admin','LOGIN_SUCCESS','users','admin','Login realizado com sucesso.','2026-06-18 00:09:12','INFO',NULL,NULL,NULL,'APPGESTAO',NULL,'ADMIN'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',3,'admin','LOGIN_SUCCESS','users','admin','Login realizado com sucesso.','2026-06-18 00:18:42','INFO',NULL,NULL,NULL,'APPGESTAO',NULL,'ADMIN');

DROP TABLE IF EXISTS `backup_runs`;
CREATE TABLE `backup_runs` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `store_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'WEB',
  `device_id` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_id` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'WAITING',
  `total_rows` int NOT NULL DEFAULT '0',
  `file_name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `finished_at` datetime DEFAULT NULL,
  `message` text COLLATE utf8mb4_unicode_ci,
  PRIMARY KEY (`id`),
  KEY `idx_backup_runs_scope` (`tenant_id`,`store_id`,`created_at`),
  KEY `idx_backup_runs_status` (`tenant_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `cash_movements`;
CREATE TABLE `cash_movements` (
  `tenant_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'legacy',
  `store_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'WEB',
  `device_id` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_id` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'WEB',
  `id` int NOT NULL,
  `session_id` int NOT NULL,
  `type` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL,
  `value` decimal(15,2) NOT NULL,
  `observation` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `date_time` datetime DEFAULT NULL,
  PRIMARY KEY (`tenant_id`,`store_id`,`id`),
  KEY `idx_cash_movements_session` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `cash_movements` VALUES ('7d5b16df-f1f1-4003-a6d8-0c30ba29570e','71320070-b210-4848-860c-38df600f996e','771c6df1-0c9a-4493-a6e7-10edd6e07bee','LC Multimarcas',14,44,'SANGRIA',44.00,'','2026-06-18 00:43:54'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',1,4,'SANGRIA',10.00,'teste','2026-05-25 17:13:43'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',2,1,'SANGRIA',20.00,'','2026-05-25 19:56:47'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',3,1,'SANGRIA',10.00,'','2026-05-25 21:25:30'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',4,1,'SANGRIA',9.00,'','2026-05-25 21:33:52'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',5,3,'SANGRIA',10.00,'','2026-05-25 22:49:46'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',6,24,'SANGRIA',50.00,'','2026-05-26 07:45:18'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',7,24,'SUPRIMENTO',50.00,'','2026-05-26 07:49:54'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',8,29,'SANGRIA',10.00,'','2026-05-26 08:23:07'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',9,34,'SANGRIA',42.00,'Marmita','2026-06-07 15:34:05'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',10,35,'SANGRIA',10.00,'','2026-06-07 15:37:07'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',11,37,'SANGRIA',20.00,'','2026-06-07 15:54:35'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',12,36,'SANGRIA',90.00,'teste','2026-06-08 14:37:23'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',13,36,'SUPRIMENTO',10.00,'','2026-06-08 14:37:36');

DROP TABLE IF EXISTS `cash_sessions`;
CREATE TABLE `cash_sessions` (
  `tenant_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'legacy',
  `store_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'WEB',
  `device_id` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_id` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'WEB',
  `id` int NOT NULL,
  `cash_id` varchar(40) COLLATE utf8mb4_unicode_ci NOT NULL,
  `operator` varchar(140) COLLATE utf8mb4_unicode_ci NOT NULL,
  `opening_balance` decimal(15,2) NOT NULL DEFAULT '0.00',
  `closing_balance` decimal(15,2) DEFAULT NULL,
  `expected_balance` decimal(15,2) DEFAULT NULL,
  `difference` decimal(15,2) DEFAULT NULL,
  `observation` text COLLATE utf8mb4_unicode_ci,
  `opened_at` datetime DEFAULT NULL,
  `closed_at` datetime DEFAULT NULL,
  `closed_by` varchar(80) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `close_reason` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `is_open` tinyint(1) NOT NULL DEFAULT '1',
  `status` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'OPEN',
  PRIMARY KEY (`tenant_id`,`store_id`,`id`),
  KEY `idx_cash_sessions_cash_open` (`cash_id`,`is_open`),
  KEY `idx_cash_sessions_opened_at` (`opened_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `cash_sessions` VALUES ('7d5b16df-f1f1-4003-a6d8-0c30ba29570e','71320070-b210-4848-860c-38df600f996e','771c6df1-0c9a-4493-a6e7-10edd6e07bee','LC Multimarcas',40,'002','Miguel Henrique Sant\'Ana',200.00,NULL,NULL,NULL,'','2026-06-08 08:40:02','2026-06-17 23:39:47',NULL,NULL,0,'OPEN'),('7d5b16df-f1f1-4003-a6d8-0c30ba29570e','71320070-b210-4848-860c-38df600f996e','771c6df1-0c9a-4493-a6e7-10edd6e07bee','LC Multimarcas',42,'001','Administrador',500.00,NULL,NULL,NULL,'','2026-06-15 15:52:05','2026-06-17 10:24:19',NULL,NULL,0,'OPEN'),('7d5b16df-f1f1-4003-a6d8-0c30ba29570e','71320070-b210-4848-860c-38df600f996e','771c6df1-0c9a-4493-a6e7-10edd6e07bee','LC Multimarcas',43,'001','Administrador',200.00,NULL,NULL,NULL,'','2026-06-18 00:06:06','2026-06-18 00:06:35',NULL,NULL,0,'OPEN'),('7d5b16df-f1f1-4003-a6d8-0c30ba29570e','71320070-b210-4848-860c-38df600f996e','771c6df1-0c9a-4493-a6e7-10edd6e07bee','LC Multimarcas',44,'001','Administrador',250.00,NULL,NULL,NULL,'','2026-06-18 00:06:49','2026-06-18 00:44:09',NULL,NULL,0,'OPEN'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',41,'001','Administrador',200.00,NULL,NULL,NULL,'','2026-06-08 15:17:07',NULL,NULL,NULL,1,'OPEN');

DROP TABLE IF EXISTS `clients`;
CREATE TABLE `clients` (
  `tenant_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'legacy',
  `store_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'WEB',
  `device_id` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_id` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'WEB',
  `id` int NOT NULL,
  `name` varchar(180) COLLATE utf8mb4_unicode_ci NOT NULL,
  `cpf_cnpj` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `phone` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `email` varchar(180) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `address` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime DEFAULT NULL,
  `birth_date` date DEFAULT NULL,
  `active` tinyint(1) NOT NULL DEFAULT '1',
  `notes` text COLLATE utf8mb4_unicode_ci,
  `loyalty_points` int NOT NULL DEFAULT '0',
  `updated_at` datetime DEFAULT NULL,
  `deleted_at` datetime DEFAULT NULL,
  PRIMARY KEY (`tenant_id`,`store_id`,`id`),
  KEY `idx_clients_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `clients` VALUES ('legacy','LC Multimarcas','legacy-device','LC Multimarcas',1,'Leonardo','7646324324324','65566565656','dsdsdsdsdsdsd','dsdsdd','2026-05-25 05:51:58',NULL,1,NULL,0,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',2,'Manuely','089654389','17988353424','Manuelyvitoria029@gmail.com','santo marcon\n20','2026-06-03 23:31:55',NULL,1,NULL,0,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',3,'Miguel','24476834358','17991567854','miguelsantana@gmail.com','Rua assad kallil N:1199','2026-06-11 22:04:30',NULL,1,NULL,0,NULL,NULL);

DROP TABLE IF EXISTS `comanda_itens`;
CREATE TABLE `comanda_itens` (
  `tenant_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'legacy',
  `store_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'WEB',
  `device_id` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_id` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'WEB',
  `id` int NOT NULL,
  `comanda_id` int NOT NULL,
  `descricao` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `valor` decimal(15,2) NOT NULL,
  `is_produto` tinyint(1) NOT NULL DEFAULT '0',
  `product_code` varchar(80) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `quantidade` decimal(15,3) DEFAULT NULL,
  PRIMARY KEY (`tenant_id`,`store_id`,`id`),
  KEY `idx_comanda_itens_comanda` (`comanda_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `comanda_itens` VALUES ('legacy','LC Multimarcas','legacy-device','LC Multimarcas',1,1,'Arroz Empório 5KG',29.99,1,'11111',1.000),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',2,1,'Divida',250.00,0,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',3,2,'teste',100.00,0,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',4,3,'Arroz Empório 5KG',29.99,1,'11111',1.000),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',5,3,'teste',200.00,0,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',6,4,'Arroz Empório 5KG',29.99,1,'11111',1.000),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',7,4,'MES MAIO',200.00,0,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',8,5,'teste',2200.00,0,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',9,5,'Arroz Empório 5KG',29.99,1,'11111',1.000),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',10,6,'teste',10.00,0,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',11,6,'Arroz Empório 5KG',29.99,1,'11111',1.000),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',12,6,'Arroz Tipo 1 5kg',24.90,1,'789100004',1.000),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',13,7,'teste',100.00,0,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',14,7,'Arroz Tipo 1 5kg',24.90,1,'789100004',1.000),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',15,7,'divida',20.00,0,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',16,7,'divida',20.00,0,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',17,8,'teste',20.00,0,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',18,9,'divida',1000.00,0,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',19,10,'divida',2000.00,0,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',20,10,'Placa de Vídeo GTX',1599.90,1,'789500002',1.000),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',21,11,'maio',200.00,0,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',22,11,'teste',1200.00,0,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',23,11,'Água Sanitária 1L',4.80,1,'789200003',1.000),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',24,11,'Teclado USB',49.90,1,'789300004',1.000),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',25,11,'Webcam HD 720p',119.90,1,'789300005',1.000),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',26,12,'teste',200.00,0,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',27,12,'Água Sanitária 1L',4.80,1,'789200003',1.000),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',28,12,'Água Sanitária 1L',4.80,1,'789200003',1.000),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',29,12,'Fone de Ouvido Bluetooth',89.90,1,'789300001',1.000),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',30,14,'Arroz Empório 5KG',29.99,1,'11111',1.000),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',31,14,'Leite em pó ninho',22.00,1,'7891000340981',1.000),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',32,14,'divida',375.00,0,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',33,13,'Arroz Empório 5KG',299.90,1,'11111',10.000),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',34,13,'Água Mineral 1.5L',3.20,1,'789100010',1.000),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',35,14,'Arroz Tipo 1 5kg',24.90,1,'789100004',1.000),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',36,14,'Arroz Tipo 1 5kg',24.90,1,'789100004',1.000),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',37,14,'Água Sanitária 1L',4.80,1,'789200003',1.000),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',38,14,'Açúcar Refinado 1kg',6.50,1,'789100002',1.000),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',39,14,'Fralda Descartável M 10un',22.90,1,'789200010',1.000),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',40,14,'Assistência Técnica (hora)',80.00,1,'789400001',1.000),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',41,14,'Arroz Empório 5KG',29.99,1,'11111',1.000),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',42,15,'divida',9.17,0,NULL,NULL);

DROP TABLE IF EXISTS `comandas`;
CREATE TABLE `comandas` (
  `tenant_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'legacy',
  `store_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'WEB',
  `device_id` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_id` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'WEB',
  `id` int NOT NULL,
  `nome_cliente` varchar(180) COLLATE utf8mb4_unicode_ci NOT NULL,
  `client_id` int DEFAULT NULL,
  `aberta` tinyint(1) NOT NULL DEFAULT '1',
  `data_abertura` datetime DEFAULT NULL,
  `data_fechamento` datetime DEFAULT NULL,
  PRIMARY KEY (`tenant_id`,`store_id`,`id`),
  KEY `idx_comandas_aberta` (`aberta`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `comandas` VALUES ('legacy','LC Multimarcas','legacy-device','LC Multimarcas',1,'Leonardo',1,0,'2026-05-25 05:52:03','2026-05-25 22:56:30'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',2,'teste',NULL,0,'2026-05-25 17:31:52','2026-05-25 23:30:58'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',3,'Leonardo',1,0,'2026-05-25 23:20:08','2026-05-26 00:14:16'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',4,'Leonardo',1,0,'2026-05-26 00:15:20','2026-05-26 06:58:37'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',5,'Leonardo',1,0,'2026-05-26 07:59:49','2026-05-26 08:00:08'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',6,'Leonardo',1,0,'2026-06-02 22:06:57','2026-06-02 22:07:23'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',7,'Leonardo',1,0,'2026-06-02 22:07:56','2026-06-02 22:12:20'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',8,'joa',NULL,0,'2026-06-02 22:09:36','2026-06-07 00:29:12'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',9,'teste',NULL,0,'2026-06-02 22:09:41','2026-06-07 14:34:53'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',10,'miguel',NULL,0,'2026-06-02 22:09:48','2026-06-07 15:22:35'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',11,'Manuely',NULL,0,'2026-06-02 22:10:01','2026-06-07 15:32:07'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',12,'Leonardo',1,0,'2026-06-02 22:31:07','2026-06-07 16:42:56'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',13,'Manuely',2,1,'2026-06-07 16:42:26',NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',14,'Leonardo',1,1,'2026-06-07 16:43:14',NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',15,'teste',NULL,0,'2026-06-11 14:26:22','2026-06-11 14:27:00');

DROP TABLE IF EXISTS `licenses`;
CREATE TABLE `licenses` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `plan_name` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'LEGACY',
  `status` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ACTIVE',
  `starts_at` datetime DEFAULT NULL,
  `expires_at` datetime DEFAULT NULL,
  `max_stores` int NOT NULL DEFAULT '0',
  `max_devices` int NOT NULL DEFAULT '0',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_licenses_tenant` (`tenant_id`,`status`),
  KEY `idx_licenses_expiration` (`status`,`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `products`;
CREATE TABLE `products` (
  `tenant_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'legacy',
  `store_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'WEB',
  `device_id` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_id` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'WEB',
  `code` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `description` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `unit` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'UN',
  `price` decimal(15,2) NOT NULL DEFAULT '0.00',
  `cost_price` decimal(15,2) NOT NULL DEFAULT '0.00',
  `stock` decimal(15,3) NOT NULL DEFAULT '0.000',
  `supplier_id` int DEFAULT NULL,
  `category` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `barcode` varchar(80) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `min_stock` decimal(15,3) NOT NULL DEFAULT '0.000',
  `ideal_stock` decimal(15,3) NOT NULL DEFAULT '0.000',
  `active` tinyint(1) NOT NULL DEFAULT '1',
  `updated_at` datetime DEFAULT NULL,
  `deleted_at` datetime DEFAULT NULL,
  PRIMARY KEY (`tenant_id`,`store_id`,`code`),
  KEY `idx_products_description` (`description`),
  KEY `idx_products_supplier` (`supplier_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `products` VALUES ('7d5b16df-f1f1-4003-a6d8-0c30ba29570e','71320070-b210-4848-860c-38df600f996e','771c6df1-0c9a-4493-a6e7-10edd6e07bee','LC Multimarcas','789100010','Água Mineral 1.5L','UN',3.20,0.00,90.000,2,NULL,NULL,20.000,0.000,1,NULL,NULL),('7d5b16df-f1f1-4003-a6d8-0c30ba29570e','71320070-b210-4848-860c-38df600f996e','771c6df1-0c9a-4493-a6e7-10edd6e07bee','LC Multimarcas','789200003','Água Sanitária 1L','UN',4.80,0.00,12.000,1,NULL,NULL,7.000,0.000,1,NULL,NULL),('7d5b16df-f1f1-4003-a6d8-0c30ba29570e','71320070-b210-4848-860c-38df600f996e','771c6df1-0c9a-4493-a6e7-10edd6e07bee','LC Multimarcas','789300004','Teclado USB','UN',49.90,0.00,20.000,3,NULL,NULL,5.000,0.000,1,NULL,NULL),('7d5b16df-f1f1-4003-a6d8-0c30ba29570e','71320070-b210-4848-860c-38df600f996e','771c6df1-0c9a-4493-a6e7-10edd6e07bee','LC Multimarcas','789300005','Webcam HD 720p','UN',119.90,0.00,1.000,3,NULL,NULL,2.000,0.000,1,NULL,NULL),('7d5b16df-f1f1-4003-a6d8-0c30ba29570e','71320070-b210-4848-860c-38df600f996e','771c6df1-0c9a-4493-a6e7-10edd6e07bee','LC Multimarcas','789300006','Pendrive 32GB','UN',39.90,0.00,29.000,3,NULL,NULL,10.000,0.000,1,NULL,NULL),('7d5b16df-f1f1-4003-a6d8-0c30ba29570e','71320070-b210-4848-860c-38df600f996e','771c6df1-0c9a-4493-a6e7-10edd6e07bee','LC Multimarcas','7901029805085','Impressora témica Oasis','UN',160.50,0.00,1.000,3,NULL,NULL,7.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','11111','Arroz Empório 5KG','UN',29.99,0.00,51.000,NULL,NULL,NULL,0.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','123','teste','UN',0.99,0.00,100.000,1,NULL,NULL,12.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','2000605018484','Filé de frango','KG',16.00,0.00,101.500,1,NULL,NULL,20.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','2222222','ab','un',20.00,0.00,100.000,1,NULL,NULL,12.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','323332','a','Un',323.00,0.00,30.000,3,NULL,NULL,12.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','6955581352052','Leitor de código de barras','UN',89.90,0.00,994.000,1,NULL,NULL,15.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789100001','Café Torrado 500g','UN',15.90,0.00,47.000,1,NULL,NULL,10.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789100002','Açúcar Refinado 1kg','UN',6.50,0.00,70.000,1,NULL,NULL,15.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789100003','Leite Integral 1L','UN',5.20,0.00,35.000,1,NULL,NULL,10.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789100004','Arroz Tipo 1 5kg','UN',24.90,0.00,25.000,1,NULL,NULL,8.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789100005','Feijão Preto 1kg','UN',8.90,0.00,42.000,1,NULL,NULL,10.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789100006','Óleo de Soja 900ml','UN',7.80,0.00,35.000,1,NULL,NULL,8.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789100007','Macarrão Espaguete 500g','UN',4.50,0.00,60.000,1,NULL,NULL,12.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789100008','Molho de Tomate 340g','UN',3.90,0.00,55.000,1,NULL,NULL,10.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789100009','Refrigerante Cola 2L','UN',8.90,0.00,70.000,2,NULL,NULL,15.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789100010','Água Mineral 1.5L','UN',3.20,0.00,92.000,2,NULL,NULL,20.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789100011','Biscoito Recheado 140g','UN',3.50,0.00,90.000,2,NULL,NULL,15.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789100012','Chocolate ao Leite 100g','UN',5.90,0.00,63.000,2,NULL,NULL,10.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','7891000340981','Leite em pó ninho','UN',22.00,0.00,445.000,1,NULL,NULL,20.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','7891000412855','Chocolate em Pó Nescau','UN',15.00,0.00,296.000,1,NULL,NULL,20.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','7891010256760','Listerine','UN',35.90,0.00,548.000,1,NULL,NULL,20.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789200001','Sabão em Pó 800g','UN',12.90,0.00,39.000,1,NULL,NULL,8.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789200002','Detergente Líquido 500ml','UN',2.50,0.00,79.000,1,NULL,NULL,15.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789200003','Água Sanitária 1L','UN',4.80,0.00,23.000,1,NULL,NULL,7.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789200004','Esponja de Aço','UN',1.80,0.00,119.000,1,NULL,NULL,20.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789200005','Papel Toalha 2un','UN',6.90,0.00,45.000,2,NULL,NULL,10.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789200006','Sabonete Líquido 250ml','UN',7.90,0.00,46.000,2,NULL,NULL,8.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789200007','Shampoo 350ml','UN',15.90,0.00,33.000,3,NULL,NULL,7.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789200008','Condicionador 350ml','UN',15.90,0.00,30.000,3,NULL,NULL,7.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789200009','Creme Dental 90g','UN',4.50,0.00,69.000,3,NULL,NULL,12.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789200010','Fralda Descartável M 10un','UN',22.90,0.00,18.000,3,NULL,NULL,5.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789300001','Fone de Ouvido Bluetooth','UN',89.90,0.00,13.000,3,NULL,NULL,3.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789300002','Carregador Portátil 10000mAh','UN',59.90,0.00,19.000,3,NULL,NULL,5.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789300003','Mouse Óptico USB','UN',29.90,0.00,30.000,3,NULL,NULL,5.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789300004','Teclado USB','UN',49.90,0.00,21.000,3,NULL,NULL,5.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789300005','Webcam HD 720p','UN',119.90,0.00,7.000,3,NULL,NULL,2.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789300006','Pendrive 32GB','UN',39.90,0.00,40.000,3,NULL,NULL,10.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789300007','Cabo USB-C 1m','UN',15.90,0.00,9.000,3,NULL,NULL,10.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789400001','Assistência Técnica (hora)','H',80.00,0.00,999989.000,NULL,NULL,NULL,0.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789400002','Entrega Expressa','UN',15.00,0.00,999998.000,NULL,NULL,NULL,0.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789400003','Garantia Estendida 1 ano','UN',49.90,0.00,999999.000,NULL,NULL,NULL,0.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','7894900011517','Coca-Cola 2L','UN',15.00,0.00,98.000,1,NULL,NULL,10.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789500001','Bateria Externa 20000mAh','UN',129.90,0.00,99.000,3,NULL,NULL,5.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789500002','Placa de Vídeo GTX','UN',1599.90,0.00,99.000,3,NULL,NULL,2.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789500003','Processador i7','UN',1899.90,0.00,99.000,3,NULL,NULL,2.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789600001','Leite Condensado 395g','UN',5.90,0.00,40.000,1,NULL,NULL,10.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789600002','Cerveja Lager 350ml','UN',3.90,0.00,99.000,2,NULL,NULL,20.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789600003','Refrigerante Guaraná 2L','UN',7.90,0.00,60.000,2,NULL,NULL,12.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789700001','Queijo Mussarela','KG',45.90,0.00,20.000,1,NULL,NULL,5.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789700002','Presunto','KG',38.90,0.00,18.000,1,NULL,NULL,4.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789700003','Pão Francês','KG',12.90,0.00,29.000,1,NULL,NULL,8.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789700004','Carne Moída','KG',32.90,0.00,25.000,1,NULL,NULL,6.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','789700005','Frango Congelado','KG',18.90,0.00,34.000,1,NULL,NULL,8.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','7898215151784','Creme de leite piracanjuba','UN',6.49,0.00,196.000,1,NULL,NULL,20.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','7898723631884','slime metalizada','UN',15.00,0.00,669.000,1,NULL,NULL,20.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','7899718707591','USB LAN','UN',40.00,0.00,9.000,1,NULL,NULL,20.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','7901029805085','Impressora témica Oasis','UN',160.50,0.00,3.000,3,NULL,NULL,7.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','PDV-00042','Marmita executiva','UN',26.00,0.00,31.000,3,NULL,NULL,12.000,0.000,1,NULL,NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','SERVICO','SERVIÇO / VALOR DIRETO','UN',0.00,0.00,999986.000,NULL,NULL,NULL,0.000,0.000,1,NULL,NULL);

DROP TABLE IF EXISTS `sale_cancellations`;
CREATE TABLE `sale_cancellations` (
  `tenant_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'legacy',
  `store_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'WEB',
  `device_id` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_id` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'WEB',
  `id` int NOT NULL,
  `sale_id` int NOT NULL,
  `reason` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `cancelled_by` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `cancelled_at` datetime DEFAULT NULL,
  PRIMARY KEY (`tenant_id`,`store_id`,`id`),
  KEY `idx_sale_cancellations_sale` (`sale_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `sale_cancellations` VALUES ('legacy','LC Multimarcas','legacy-device','LC Multimarcas',1,30,'teste','admin','2026-05-26 21:34:46'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',2,28,'cliente cancelou','admin','2026-05-29 02:06:48'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',3,1,'Cancelamento pelo administrador','admin','2026-06-02 18:25:27'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',4,51,'Cancelamento pelo administrador','admin','2026-06-06 20:57:33'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',5,53,'test','admin','2026-06-06 21:31:22'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',6,62,'teste','admin','2026-06-07 14:34:25'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',7,48,'teste','admin','2026-06-08 20:16:47'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',8,35,'Cancelamento pelo administrador','admin','2026-06-08 20:17:10');

DROP TABLE IF EXISTS `sale_items`;
CREATE TABLE `sale_items` (
  `tenant_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'legacy',
  `store_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'WEB',
  `device_id` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_id` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'WEB',
  `id` int NOT NULL,
  `sale_id` int NOT NULL,
  `product_code` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `quantity` decimal(15,3) NOT NULL,
  `unit_price` decimal(15,2) NOT NULL,
  `discount` decimal(15,2) NOT NULL DEFAULT '0.00',
  PRIMARY KEY (`tenant_id`,`store_id`,`id`),
  KEY `idx_sale_items_sale` (`sale_id`),
  KEY `idx_sale_items_product` (`product_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `sale_items` VALUES ('7d5b16df-f1f1-4003-a6d8-0c30ba29570e','71320070-b210-4848-860c-38df600f996e','771c6df1-0c9a-4493-a6e7-10edd6e07bee','LC Multimarcas',189,82,'789100010',1.000,3.20,0.00),('7d5b16df-f1f1-4003-a6d8-0c30ba29570e','71320070-b210-4848-860c-38df600f996e','771c6df1-0c9a-4493-a6e7-10edd6e07bee','LC Multimarcas',190,82,'789200003',10.000,4.80,0.00),('7d5b16df-f1f1-4003-a6d8-0c30ba29570e','71320070-b210-4848-860c-38df600f996e','771c6df1-0c9a-4493-a6e7-10edd6e07bee','LC Multimarcas',191,83,'7901029805085',1.000,160.50,0.00),('7d5b16df-f1f1-4003-a6d8-0c30ba29570e','71320070-b210-4848-860c-38df600f996e','771c6df1-0c9a-4493-a6e7-10edd6e07bee','LC Multimarcas',192,83,'789300004',1.000,49.90,0.00),('7d5b16df-f1f1-4003-a6d8-0c30ba29570e','71320070-b210-4848-860c-38df600f996e','771c6df1-0c9a-4493-a6e7-10edd6e07bee','LC Multimarcas',193,84,'789100010',1.000,3.20,0.00),('7d5b16df-f1f1-4003-a6d8-0c30ba29570e','71320070-b210-4848-860c-38df600f996e','771c6df1-0c9a-4493-a6e7-10edd6e07bee','LC Multimarcas',194,84,'789300006',10.000,39.90,0.00),('7d5b16df-f1f1-4003-a6d8-0c30ba29570e','71320070-b210-4848-860c-38df600f996e','771c6df1-0c9a-4493-a6e7-10edd6e07bee','LC Multimarcas',195,84,'789300005',5.000,119.90,0.00),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',1,10482,'7894900011517',2.000,15.00,0.00),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',2,10482,'PDV-00042',6.184,25.00,0.00),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',150,69,'789100010',1.000,3.20,0.00),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',151,69,'7901029805085',1.000,160.50,0.00),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',152,69,'789100002',1.000,6.50,0.00),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',153,70,'789100010',1.000,3.20,0.00),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',154,70,'789200003',1.000,4.80,0.00),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',155,71,'789100010',1.000,3.20,0.00),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',156,71,'789200006',1.000,7.90,0.00),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',157,71,'323332',1.000,323.00,0.00),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',158,72,'SERVICO',1.000,9.17,0.00),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',159,73,'7891010256760',1.000,35.90,0.00),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',160,73,'789100010',2.000,3.20,0.00),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',161,73,'789100002',1.000,6.50,0.00),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',162,73,'11111',1.000,29.99,0.00),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',163,73,'789600001',5.000,5.90,0.00),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',164,74,'789200003',1.000,4.80,0.00),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',165,74,'789200007',1.000,15.90,0.00),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',166,74,'789300004',1.000,49.90,0.00),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',167,74,'789300005',1.000,119.90,0.00),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',168,74,'789500002',1.000,1599.90,0.00),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',169,75,'789100002',1.000,6.50,0.00),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',170,75,'7894900011517',1.000,15.00,0.00),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',171,76,'789200003',2.000,4.80,0.00),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',172,76,'7894900011517',1.000,15.00,0.00),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',173,76,'789100002',1.000,6.50,0.00),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',174,76,'323332',1.000,323.00,0.00),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',175,77,'789200008',1.000,15.90,0.00),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',176,78,'6955581352052',1.000,89.90,0.00),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',177,78,'7898723631884',1.000,15.00,0.00),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',178,78,'7901029805085',1.000,160.50,0.00),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',179,78,'7899718707591',1.000,40.00,0.00),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',180,79,'789200003',1.000,4.80,0.00),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',181,79,'789100002',1.000,6.50,0.00),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',182,79,'7901029805085',10.000,160.50,0.00),('legacy','WEB','legacy-device','LC Multimarcas',183,80,'789500003',1.000,1899.90,0.00);

DROP TABLE IF EXISTS `sales`;
CREATE TABLE `sales` (
  `tenant_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'legacy',
  `store_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'WEB',
  `device_id` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_id` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'WEB',
  `id` int NOT NULL,
  `session_id` int NOT NULL,
  `operator` varchar(140) COLLATE utf8mb4_unicode_ci NOT NULL,
  `discount` decimal(15,2) NOT NULL DEFAULT '0.00',
  `surcharge` decimal(15,2) NOT NULL DEFAULT '0.00',
  `payment_method` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `amount_paid` decimal(15,2) DEFAULT NULL,
  `status` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'OPEN',
  `date_time` datetime DEFAULT NULL,
  PRIMARY KEY (`tenant_id`,`store_id`,`id`),
  KEY `idx_sales_session` (`session_id`),
  KEY `idx_sales_date_time` (`date_time`),
  KEY `idx_sales_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `sales` VALUES ('7d5b16df-f1f1-4003-a6d8-0c30ba29570e','71320070-b210-4848-860c-38df600f996e','771c6df1-0c9a-4493-a6e7-10edd6e07bee','LC Multimarcas',82,44,'Administrador',0.00,0.00,'PIX',51.20,'PAID','2026-06-18 00:06:50'),('7d5b16df-f1f1-4003-a6d8-0c30ba29570e','71320070-b210-4848-860c-38df600f996e','771c6df1-0c9a-4493-a6e7-10edd6e07bee','LC Multimarcas',83,44,'Administrador',0.00,0.00,'PIX',210.40,'PAID','2026-06-18 00:07:05'),('7d5b16df-f1f1-4003-a6d8-0c30ba29570e','71320070-b210-4848-860c-38df600f996e','771c6df1-0c9a-4493-a6e7-10edd6e07bee','LC Multimarcas',84,44,'Administrador',0.00,0.00,'CARD',1001.70,'PAID','2026-06-18 00:19:15'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',1,4,'Administrador',0.00,0.00,'CASH',29.99,'PAID','2026-05-25 17:12:03'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',2,4,'Administrador',0.00,0.00,'CASH',29.99,'PAID','2026-05-25 17:12:23'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',3,4,'Administrador',0.00,0.00,'CASH',29.99,'PAID','2026-05-25 17:12:40'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',4,5,'João',0.00,0.00,'CASH',59.98,'PAID','2026-05-25 17:16:19'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',5,7,'Administrador',0.00,0.00,'CASH',29.99,'PAID','2026-05-25 17:24:26'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',6,8,'Administrador',0.00,0.00,'CASH',29.99,'PAID','2026-05-25 17:28:12'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',7,16,'Administrador',0.00,0.00,'CASH',29.99,'PAID','2026-05-25 18:15:06'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',8,18,'Administrador',0.00,0.00,'CASH',29.99,'PAID','2026-05-25 18:33:27'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',9,19,'Administrador',0.00,0.00,'CASH',29.99,'PAID','2026-05-25 18:41:21'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',10,21,'Administrador',0.00,0.00,'CASH',29.99,'PAID','2026-05-25 19:00:38'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',11,21,'Administrador',0.00,0.00,'CASH',29.99,'PAID','2026-05-25 19:00:38'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',12,21,'Administrador',0.00,0.00,'CASH',29.99,'PAID','2026-05-25 19:00:38'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',13,21,'Administrador',0.00,0.00,'CASH',29.99,'PAID','2026-05-25 19:00:38'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',14,22,'Administrador',0.00,0.00,'CASH',29.99,'PAID','2026-05-25 19:27:41'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',15,6,'Administrador',0.00,0.00,'CASH',29.99,'PAID','2026-05-25 23:09:57'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',16,6,'Administrador',0.00,0.00,'CASH',29.99,'PAID','2026-05-25 23:10:05'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',17,7,'admin',0.00,0.00,'CARD',100.00,'PAID','2026-05-25 23:30:56'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',18,23,'admin',0.00,0.00,'CARD',229.99,'PAID','2026-05-26 00:14:13'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',19,24,'admin',0.00,0.00,'PIX',229.99,'PAID','2026-05-26 06:58:34'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',20,24,'Administrador',0.00,0.00,'CASH',29.99,'PAID','2026-05-26 06:56:51'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',21,24,'Administrador',0.00,0.00,'CASH',29.99,'PAID','2026-05-26 07:02:43'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',22,24,'Administrador',0.00,0.00,'CASH',29.99,'PAID','2026-05-26 07:27:01'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',23,24,'Administrador',0.00,0.00,'CASH',29.99,'PAID','2026-05-26 07:33:54'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',24,24,'Administrador',0.00,0.00,'CASH',449.85,'PAID','2026-05-26 07:45:35'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',25,25,'Administrador',0.00,0.00,'CASH',29.99,'PAID','2026-05-26 07:59:27'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',26,25,'admin',0.00,0.00,'PIX',2229.99,'PAID','2026-05-26 08:00:07'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',27,27,'Administrador',0.00,0.00,'CASH',29.99,'PAID','2026-05-26 08:05:58'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',28,29,'Administrador',0.00,0.00,'CASH',29.99,'PAID','2026-05-26 08:22:29'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',29,30,'Administrador',0.00,0.00,'CASH',84.60,'PAID','2026-05-26 08:29:16'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',30,31,'Administrador',0.00,0.00,'CASH',84.60,'PAID','2026-05-27 00:31:58'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',31,31,'Administrador',0.00,0.00,'CASH',43.10,'PAID','2026-06-02 21:21:00'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',32,31,'Administrador',0.00,0.00,'CASH',15.90,'PAID','2026-06-02 21:22:07'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',33,31,'Administrador',0.00,0.00,'CASH',100.60,'PAID','2026-06-02 21:26:59'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',34,31,'Administrador',0.00,0.00,'CASH',6.50,'PAID','2026-06-02 21:29:57'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',35,31,'Administrador',0.00,0.00,'CASH',14.10,'CANCELLED','2026-06-02 21:39:37'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',36,31,'Administrador',0.00,0.00,'CASH',42.90,'PAID','2026-06-02 21:57:05'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',37,31,'Administrador',0.00,0.00,'CARD',86.19,'PAID','2026-06-02 21:59:29'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',38,31,'Administrador',0.00,0.00,'OTHER',6.50,'PAID','2026-06-02 22:00:18'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',39,31,'Administrador',0.00,0.00,'CASH',29.99,'PAID','2026-06-02 22:04:23'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',40,31,'admin',0.00,0.00,'CASH',64.89,'PAID','2026-06-02 22:07:21'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',41,31,'admin',0.00,0.00,'PIX',164.90,'PAID','2026-06-02 22:12:18'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',42,31,'Administrador',0.00,0.00,'CASH',5.20,'PAID','2026-06-02 22:04:37'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',43,31,'Administrador',0.00,0.00,'CASH',5.20,'PAID','2026-06-02 22:12:45'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',44,31,'Administrador',0.00,0.00,'CASH',250.40,'PAID','2026-06-03 20:05:41'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',45,31,'Administrador',0.00,0.00,'CASH',160.50,'PAID','2026-06-03 20:06:03'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',46,31,'Administrador',0.00,0.00,'PIX',293.89,'PAID','2026-06-03 20:06:37'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',47,31,'Administrador',0.00,0.00,'CASH',79.39,'PAID','2026-06-03 23:18:51'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',48,31,'Administrador',0.00,0.00,'CASH',100.00,'CANCELLED','2026-06-03 23:23:14'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',49,31,'Administrador',0.00,0.00,'CARD',43.49,'PAID','2026-06-03 23:27:53'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',50,31,'Administrador',0.00,0.00,'CARD',43.49,'PAID','2026-06-03 23:36:13'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',51,31,'Administrador',0.00,0.00,'PIX',108.39,'CANCELLED','2026-06-03 23:38:11'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',52,31,'admin',0.00,0.00,'PIX',20.00,'PAID','2026-06-07 00:29:12'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',53,31,'Administrador',0.00,0.00,'OTHER',145.68,'CANCELLED','2026-06-07 00:28:31'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',54,31,'Administrador',0.00,0.00,'CASH',89.90,'PAID','2026-06-06 23:48:50'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',55,32,'admin',0.00,0.00,'CASH',1000.00,'PAID','2026-06-07 14:34:53'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',56,33,'admin',0.00,0.00,'PIX',3599.90,'PAID','2026-06-07 15:22:35'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',57,33,'Administrador',0.00,0.00,'CASH',1168.09,'PAID','2026-06-07 15:21:59'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',58,34,'admin',0.00,0.00,'CASH',1574.60,'PAID','2026-06-07 15:32:06'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',59,34,'Administrador',0.00,0.00,'CASH',150.00,'PAID','2026-06-07 15:30:32'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',60,36,'admin',0.00,0.00,'CASH',299.50,'PAID','2026-06-07 16:42:55'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',61,36,'Administrador',0.00,0.00,'CASH',3.20,'PAID','2026-06-07 16:40:51'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',62,36,'Administrador',0.00,0.00,'CASH',3.20,'CANCELLED','2026-06-07 17:20:00'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',63,36,'Administrador',0.00,0.00,'CASH',266.40,'PAID','2026-06-07 18:22:02'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',64,36,'Administrador',0.00,0.00,'CASH',266.40,'PAID','2026-06-07 20:01:01'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',65,36,'Administrador',0.00,0.00,'CASH',29.99,'PAID','2026-06-08 21:11:22'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',66,41,'Administrador',0.00,0.00,'OTHER',57.69,'PAID','2026-06-08 21:17:07'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',67,41,'Administrador',0.00,0.00,'CARD',34.79,'PAID','2026-06-08 23:10:56'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',68,41,'Administrador',0.00,0.00,'CASH',221.29,'PAID','2026-06-10 14:33:42'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',69,41,'Administrador',0.00,0.00,'CASH',170.20,'PAID','2026-06-10 15:25:08'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',70,41,'Administrador',0.00,0.00,'CASH',8.00,'PAID','2026-06-10 15:33:47'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',71,41,'Administrador',0.00,0.00,'CASH',334.10,'PAID','2026-06-11 14:24:05'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',72,41,'admin',0.00,0.00,'CASH',9.17,'PAID','2026-06-11 14:26:59'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',73,41,'Administrador',0.00,0.00,'CASH',108.29,'PAID','2026-06-11 14:52:57'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',74,41,'Administrador',0.00,0.00,'CARD',1790.40,'PAID','2026-06-11 15:01:04'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',75,41,'Administrador',0.00,0.00,'CARD',21.50,'PAID','2026-06-12 10:12:07'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',76,41,'Administrador',0.00,0.00,'OTHER',354.10,'PAID','2026-06-12 10:14:15'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',77,41,'Administrador',0.00,0.00,'PIX',15.90,'PAID','2026-06-12 10:14:46'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',78,41,'Administrador',0.00,0.00,'OTHER',305.40,'PAID','2026-06-12 16:20:26'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',79,41,'Administrador',0.00,0.00,'OTHER',1616.30,'PAID','2026-06-15 10:36:38'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',10482,1,'Mariana Alves',0.00,0.00,'PIX',184.60,'PAID','2026-06-10 08:37:00'),('legacy','WEB','legacy-device','LC Multimarcas',80,41,'Administrador',0.00,0.00,'CASH',1899.90,'PAID','2026-06-15 14:38:51');

DROP TABLE IF EXISTS `stock_movements`;
CREATE TABLE `stock_movements` (
  `tenant_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'legacy',
  `store_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'WEB',
  `device_id` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_id` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'WEB',
  `id` int NOT NULL,
  `product_code` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `type` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL,
  `quantity` decimal(15,3) NOT NULL,
  `previous_stock` decimal(15,3) DEFAULT NULL,
  `new_stock` decimal(15,3) DEFAULT NULL,
  `origin` varchar(80) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `reference_type` varchar(80) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `reference_id` varchar(80) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `reason` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `user` varchar(80) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime DEFAULT NULL,
  PRIMARY KEY (`tenant_id`,`store_id`,`id`),
  KEY `idx_stock_movements_product` (`product_code`),
  KEY `idx_stock_movements_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `suppliers`;
CREATE TABLE `suppliers` (
  `tenant_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'legacy',
  `store_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'WEB',
  `device_id` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_id` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'WEB',
  `id` int NOT NULL,
  `name` varchar(180) COLLATE utf8mb4_unicode_ci NOT NULL,
  `cnpj` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `phone` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `email` varchar(180) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `address` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime DEFAULT NULL,
  PRIMARY KEY (`tenant_id`,`store_id`,`id`),
  KEY `idx_suppliers_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `suppliers` VALUES ('legacy','LC Multimarcas','legacy-device','LC Multimarcas',1,'Leonardo Vendas','678.332.89/0001-89','124354545','Email@email.com','Sao paulo','2026-05-25 22:20:02'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',2,'Fornecedor LTDA','12.345.678/0001-90','(11) 98765-4321','contato@fornecedor.com','Rua das Industrias, 100 - São Paulo/SP','2026-05-26 05:27:43'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',3,'Distribuidora Nacional','98.765.432/0001-21','(21) 99876-5432','vendas@distribuidora.com','Av. Central, 500 - Rio de Janeiro/RJ','2026-05-26 05:27:43'),('legacy','LC Multimarcas','legacy-device','LC Multimarcas',4,'Tech Importados','55.555.555/0001-55','(47) 99999-8888','importacao@tech.com','Rua do Comércio, 200 - Curitiba/PR','2026-05-26 05:27:43');

DROP TABLE IF EXISTS `sync_runs`;
CREATE TABLE `sync_runs` (
  `tenant_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'legacy',
  `store_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'WEB',
  `device_id` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `source_id` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL,
  `mode` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL,
  `generated_at` datetime DEFAULT NULL,
  `received_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `finished_at` datetime DEFAULT NULL,
  `total_rows` int NOT NULL DEFAULT '0',
  `table_counts_json` longtext COLLATE utf8mb4_unicode_ci,
  `message` text COLLATE utf8mb4_unicode_ci,
  PRIMARY KEY (`id`),
  KEY `idx_sync_runs_received_at` (`received_at`),
  KEY `idx_sync_runs_source` (`source_id`)
) ENGINE=InnoDB AUTO_INCREMENT=39 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `sync_runs` VALUES ('legacy','ZENTRIX-PDV-LOJA-CENTRO','legacy-device',1,'ZENTRIX-PDV-LOJA-CENTRO','FULL','SUCCESS','2026-06-10 14:17:00','2026-06-10 14:17:23','2026-06-10 14:17:23',8,'{\"users\":1,\"products\":2,\"cash_sessions\":1,\"sales\":1,\"sale_items\":2,\"audit_log\":1}','Recebido via API'),('legacy','ZENTRIX-PDV-LOJA-CENTRO','legacy-device',2,'ZENTRIX-PDV-LOJA-CENTRO','PARTIAL','SUCCESS','2026-06-10 14:33:00','2026-06-10 14:32:51','2026-06-10 14:32:51',1,'{\"users\":1}','Recebido via API'),('legacy','ZENTRIX-PDV-LOJA-CENTRO','legacy-device',3,'ZENTRIX-PDV-LOJA-CENTRO','PARTIAL','SUCCESS','2026-06-11 14:12:57','2026-06-11 14:12:57','2026-06-11 14:12:57',0,'{\"users\":0,\"suppliers\":0,\"clients\":0,\"products\":0,\"stock_movements\":0,\"cash_sessions\":0,\"cash_movements\":0,\"sales\":0,\"sale_items\":0,\"sale_cancellations\":0,\"comandas\":0,\"comanda_itens\":0,\"audit_log\":0}','Recebido via API'),('legacy','LC Multimarcas','legacy-device',4,'LC Multimarcas','PARTIAL','SUCCESS','2026-06-10 15:27:10','2026-06-11 14:18:23','2026-06-11 14:18:23',1,'{\"users\":0,\"suppliers\":0,\"clients\":0,\"products\":1,\"stock_movements\":0,\"cash_sessions\":0,\"cash_movements\":0,\"sales\":0,\"sale_items\":0,\"sale_cancellations\":0,\"comandas\":0,\"comanda_itens\":0,\"audit_log\":0}','Recebido via API'),('legacy','LC Multimarcas','legacy-device',5,'LC Multimarcas','PARTIAL','SUCCESS','2026-06-10 15:28:40','2026-06-11 14:18:23','2026-06-11 14:18:23',1,'{\"users\":0,\"suppliers\":0,\"clients\":0,\"products\":1,\"stock_movements\":0,\"cash_sessions\":0,\"cash_movements\":0,\"sales\":0,\"sale_items\":0,\"sale_cancellations\":0,\"comandas\":0,\"comanda_itens\":0,\"audit_log\":0}','Recebido via API'),('legacy','LC Multimarcas','legacy-device',6,'LC Multimarcas','PARTIAL','SUCCESS','2026-06-10 15:33:41','2026-06-11 14:18:23','2026-06-11 14:18:23',8,'{\"users\":0,\"suppliers\":0,\"clients\":0,\"products\":3,\"stock_movements\":0,\"cash_sessions\":1,\"cash_movements\":0,\"sales\":1,\"sale_items\":3,\"sale_cancellations\":0,\"comandas\":0,\"comanda_itens\":0,\"audit_log\":0}','Recebido via API'),('legacy','LC Multimarcas','legacy-device',7,'LC Multimarcas','PARTIAL','SUCCESS','2026-06-10 15:58:55','2026-06-11 14:18:23','2026-06-11 14:18:23',6,'{\"users\":0,\"suppliers\":0,\"clients\":0,\"products\":2,\"stock_movements\":0,\"cash_sessions\":1,\"cash_movements\":0,\"sales\":1,\"sale_items\":2,\"sale_cancellations\":0,\"comandas\":0,\"comanda_itens\":0,\"audit_log\":0}','Recebido via API'),('legacy','LC Multimarcas','legacy-device',8,'LC Multimarcas','PARTIAL','SUCCESS','2026-06-11 14:25:49','2026-06-11 14:25:50','2026-06-11 14:25:50',8,'{\"users\":0,\"suppliers\":0,\"clients\":0,\"products\":3,\"stock_movements\":0,\"cash_sessions\":1,\"cash_movements\":0,\"sales\":1,\"sale_items\":3,\"sale_cancellations\":0,\"comandas\":0,\"comanda_itens\":0,\"audit_log\":0}','Recebido via API'),('legacy','LC Multimarcas','legacy-device',9,'LC Multimarcas','PARTIAL','SUCCESS','2026-06-11 14:26:23','2026-06-11 14:26:52','2026-06-11 14:26:52',1,'{\"users\":0,\"suppliers\":0,\"clients\":0,\"products\":0,\"stock_movements\":0,\"cash_sessions\":0,\"cash_movements\":0,\"sales\":0,\"sale_items\":0,\"sale_cancellations\":0,\"comandas\":1,\"comanda_itens\":0,\"audit_log\":0}','Recebido via API'),('legacy','LC Multimarcas','legacy-device',10,'LC Multimarcas','PARTIAL','SUCCESS','2026-06-11 14:26:53','2026-06-11 14:27:00','2026-06-11 14:27:00',2,'{\"users\":0,\"suppliers\":0,\"clients\":0,\"products\":0,\"stock_movements\":0,\"cash_sessions\":0,\"cash_movements\":0,\"sales\":0,\"sale_items\":0,\"sale_cancellations\":0,\"comandas\":1,\"comanda_itens\":1,\"audit_log\":0}','Recebido via API'),('legacy','LC Multimarcas','legacy-device',11,'LC Multimarcas','PARTIAL','SUCCESS','2026-06-11 14:27:00','2026-06-11 14:27:00','2026-06-11 14:27:00',4,'{\"users\":0,\"suppliers\":0,\"clients\":0,\"products\":1,\"stock_movements\":0,\"cash_sessions\":1,\"cash_movements\":0,\"sales\":1,\"sale_items\":1,\"sale_cancellations\":0,\"comandas\":0,\"comanda_itens\":0,\"audit_log\":0}','Recebido via API'),('legacy','LC Multimarcas','legacy-device',12,'LC Multimarcas','PARTIAL','SUCCESS','2026-06-11 14:27:00','2026-06-11 14:27:00','2026-06-11 14:27:00',2,'{\"users\":0,\"suppliers\":0,\"clients\":0,\"products\":0,\"stock_movements\":0,\"cash_sessions\":0,\"cash_movements\":0,\"sales\":0,\"sale_items\":0,\"sale_cancellations\":0,\"comandas\":1,\"comanda_itens\":1,\"audit_log\":0}','Recebido via API'),('legacy','LC Multimarcas','legacy-device',13,'LC Multimarcas','PARTIAL','SUCCESS','2026-06-11 15:01:03','2026-06-11 15:01:03','2026-06-11 15:01:03',12,'{\"users\":0,\"suppliers\":0,\"clients\":0,\"products\":5,\"stock_movements\":0,\"cash_sessions\":1,\"cash_movements\":0,\"sales\":1,\"sale_items\":5,\"sale_cancellations\":0,\"comandas\":0,\"comanda_itens\":0,\"audit_log\":0}','Recebido via API'),('legacy','LC Multimarcas','legacy-device',14,'LC Multimarcas','PARTIAL','SUCCESS','2026-06-11 15:21:18','2026-06-11 15:21:41','2026-06-11 15:21:41',12,'{\"users\":0,\"suppliers\":0,\"clients\":0,\"products\":5,\"stock_movements\":0,\"cash_sessions\":1,\"cash_movements\":0,\"sales\":1,\"sale_items\":5,\"sale_cancellations\":0,\"comandas\":0,\"comanda_itens\":0,\"audit_log\":0}','Recebido via API'),('legacy','LC Multimarcas','legacy-device',15,'LC Multimarcas','PARTIAL','SUCCESS','2026-06-11 16:11:09','2026-06-11 16:11:09','2026-06-11 16:11:09',1,'{\"users\":0,\"suppliers\":0,\"clients\":0,\"products\":1,\"stock_movements\":0,\"cash_sessions\":0,\"cash_movements\":0,\"sales\":0,\"sale_items\":0,\"sale_cancellations\":0,\"comandas\":0,\"comanda_itens\":0,\"audit_log\":0}','Recebido via API'),('legacy','LC Multimarcas','legacy-device',16,'LC Multimarcas','PARTIAL','SUCCESS','2026-06-11 22:04:30','2026-06-11 22:04:31','2026-06-11 22:04:31',1,'{\"users\":0,\"suppliers\":0,\"clients\":1,\"products\":0,\"stock_movements\":0,\"cash_sessions\":0,\"cash_movements\":0,\"sales\":0,\"sale_items\":0,\"sale_cancellations\":0,\"comandas\":0,\"comanda_itens\":0,\"audit_log\":0}','Recebido via API'),('legacy','LC Multimarcas','legacy-device',17,'LC Multimarcas','PARTIAL','SUCCESS','2026-06-12 10:12:49','2026-06-12 10:12:49','2026-06-12 10:12:49',1,'{\"users\":0,\"suppliers\":0,\"clients\":0,\"products\":1,\"stock_movements\":0,\"cash_sessions\":0,\"cash_movements\":0,\"sales\":0,\"sale_items\":0,\"sale_cancellations\":0,\"comandas\":0,\"comanda_itens\":0,\"audit_log\":0}','Recebido via API'),('legacy','LC Multimarcas','legacy-device',18,'LC Multimarcas','PARTIAL','SUCCESS','2026-06-12 10:14:14','2026-06-12 10:14:14','2026-06-12 10:14:14',6,'{\"users\":0,\"suppliers\":0,\"clients\":0,\"products\":2,\"stock_movements\":0,\"cash_sessions\":1,\"cash_movements\":0,\"sales\":1,\"sale_items\":2,\"sale_cancellations\":0,\"comandas\":0,\"comanda_itens\":0,\"audit_log\":0}','Recebido via API'),('legacy','LC Multimarcas','legacy-device',19,'LC Multimarcas','PARTIAL','SUCCESS','2026-06-12 10:14:46','2026-06-12 10:14:46','2026-06-12 10:14:46',10,'{\"users\":0,\"suppliers\":0,\"clients\":0,\"products\":4,\"stock_movements\":0,\"cash_sessions\":1,\"cash_movements\":0,\"sales\":1,\"sale_items\":4,\"sale_cancellations\":0,\"comandas\":0,\"comanda_itens\":0,\"audit_log\":0}','Recebido via API'),('legacy','LC Multimarcas','legacy-device',20,'LC Multimarcas','PARTIAL','SUCCESS','2026-06-12 10:26:56','2026-06-12 10:27:00','2026-06-12 10:27:00',1,'{\"users\":0,\"suppliers\":0,\"clients\":0,\"products\":1,\"stock_movements\":0,\"cash_sessions\":0,\"cash_movements\":0,\"sales\":0,\"sale_items\":0,\"sale_cancellations\":0,\"comandas\":0,\"comanda_itens\":0,\"audit_log\":0}','Recebido via API'),('legacy','LC Multimarcas','legacy-device',21,'LC Multimarcas','PARTIAL','SUCCESS','2026-06-12 10:27:37','2026-06-12 10:28:00','2026-06-12 10:28:00',1,'{\"users\":0,\"suppliers\":0,\"clients\":0,\"products\":1,\"stock_movements\":0,\"cash_sessions\":0,\"cash_movements\":0,\"sales\":0,\"sale_items\":0,\"sale_cancellations\":0,\"comandas\":0,\"comanda_itens\":0,\"audit_log\":0}','Recebido via API'),('legacy','LC Multimarcas','legacy-device',22,'LC Multimarcas','PARTIAL','SUCCESS','2026-06-12 10:30:28','2026-06-12 10:30:30','2026-06-12 10:30:30',1,'{\"users\":0,\"suppliers\":0,\"clients\":0,\"products\":1,\"stock_movements\":0,\"cash_sessions\":0,\"cash_movements\":0,\"sales\":0,\"sale_items\":0,\"sale_cancellations\":0,\"comandas\":0,\"comanda_itens\":0,\"audit_log\":0}','Recebido via API'),('legacy','LC Multimarcas','legacy-device',23,'LC Multimarcas','PARTIAL','SUCCESS','2026-06-12 16:20:26','2026-06-12 16:20:32','2026-06-12 16:20:32',4,'{\"users\":0,\"suppliers\":0,\"clients\":0,\"products\":1,\"stock_movements\":0,\"cash_sessions\":1,\"cash_movements\":0,\"sales\":1,\"sale_items\":1,\"sale_cancellations\":0,\"comandas\":0,\"comanda_itens\":0,\"audit_log\":0}','Recebido via API'),('legacy','LC Multimarcas','legacy-device',24,'LC Multimarcas','PARTIAL','SUCCESS','2026-06-12 16:24:49','2026-06-12 16:24:49','2026-06-12 16:24:49',1,'{\"users\":0,\"suppliers\":0,\"clients\":0,\"products\":1,\"stock_movements\":0,\"cash_sessions\":0,\"cash_movements\":0,\"sales\":0,\"sale_items\":0,\"sale_cancellations\":0,\"comandas\":0,\"comanda_itens\":0,\"audit_log\":0}','Recebido via API'),('legacy','LC Multimarcas','legacy-device',25,'LC Multimarcas','PARTIAL','SUCCESS','2026-06-12 16:27:54','2026-06-12 16:27:54','2026-06-12 16:27:54',1,'{\"users\":0,\"suppliers\":0,\"clients\":0,\"products\":1,\"stock_movements\":0,\"cash_sessions\":0,\"cash_movements\":0,\"sales\":0,\"sale_items\":0,\"sale_cancellations\":0,\"comandas\":0,\"comanda_itens\":0,\"audit_log\":0}','Recebido via API'),('legacy','LC Multimarcas','legacy-device',26,'LC Multimarcas','PARTIAL','SUCCESS','2026-06-12 16:30:44','2026-06-12 16:31:03','2026-06-12 16:31:03',10,'{\"users\":0,\"suppliers\":0,\"clients\":0,\"products\":4,\"stock_movements\":0,\"cash_sessions\":1,\"cash_movements\":0,\"sales\":1,\"sale_items\":4,\"sale_cancellations\":0,\"comandas\":0,\"comanda_itens\":0,\"audit_log\":0}','Recebido via API'),('legacy','LC Multimarcas','legacy-device',27,'LC Multimarcas','PARTIAL','SUCCESS','2026-06-15 10:37:06','2026-06-15 10:37:28','2026-06-15 10:37:28',1,'{\"users\":1,\"suppliers\":0,\"clients\":0,\"products\":0,\"stock_movements\":0,\"cash_sessions\":0,\"cash_movements\":0,\"sales\":0,\"sale_items\":0,\"sale_cancellations\":0,\"comandas\":0,\"comanda_itens\":0,\"audit_log\":0}','Recebido via API'),('legacy','LC Multimarcas','legacy-device',28,'LC Multimarcas','PARTIAL','SUCCESS','2026-06-15 14:38:50','2026-06-15 14:38:59','2026-06-15 14:38:59',8,'{\"users\":0,\"suppliers\":0,\"clients\":0,\"products\":3,\"stock_movements\":0,\"cash_sessions\":1,\"cash_movements\":0,\"sales\":1,\"sale_items\":3,\"sale_cancellations\":0,\"comandas\":0,\"comanda_itens\":0,\"audit_log\":0}','Recebido via API'),('legacy','WEB','legacy-device',29,'LC Multimarcas','PARTIAL','SUCCESS','2026-06-15 14:46:59','2026-06-15 14:47:01','2026-06-15 14:47:01',4,'{\"users\":0,\"suppliers\":0,\"clients\":0,\"products\":1,\"stock_movements\":0,\"cash_sessions\":1,\"cash_movements\":0,\"sales\":1,\"sale_items\":1,\"sale_cancellations\":0,\"comandas\":0,\"comanda_itens\":0,\"audit_log\":0}','Recebido via API'),('7d5b16df-f1f1-4003-a6d8-0c30ba29570e','71320070-b210-4848-860c-38df600f996e','771c6df1-0c9a-4493-a6e7-10edd6e07bee',30,'LC Multimarcas','PARTIAL','SUCCESS','2026-06-17 10:24:20','2026-06-17 10:24:47','2026-06-17 10:24:47',1,'{\"users\":0,\"suppliers\":0,\"clients\":0,\"products\":0,\"stock_movements\":0,\"cash_sessions\":1,\"cash_movements\":0,\"sales\":0,\"sale_items\":0,\"sale_cancellations\":0,\"comandas\":0,\"comanda_itens\":0,\"audit_log\":0}','Recebido via API'),('7d5b16df-f1f1-4003-a6d8-0c30ba29570e','71320070-b210-4848-860c-38df600f996e','771c6df1-0c9a-4493-a6e7-10edd6e07bee',31,'LC Multimarcas','PARTIAL','SUCCESS','2026-06-17 23:39:48','2026-06-17 23:40:18','2026-06-17 23:40:18',1,'{\"users\":0,\"suppliers\":0,\"clients\":0,\"products\":0,\"stock_movements\":0,\"cash_sessions\":1,\"cash_movements\":0,\"sales\":0,\"sale_items\":0,\"sale_cancellations\":0,\"comandas\":0,\"comanda_itens\":0,\"audit_log\":0}','Recebido via API'),('7d5b16df-f1f1-4003-a6d8-0c30ba29570e','71320070-b210-4848-860c-38df600f996e','771c6df1-0c9a-4493-a6e7-10edd6e07bee',32,'LC Multimarcas','PARTIAL','SUCCESS','2026-06-18 00:06:07','2026-06-18 00:06:19','2026-06-18 00:06:19',1,'{\"users\":0,\"suppliers\":0,\"clients\":0,\"products\":0,\"stock_movements\":0,\"cash_sessions\":1,\"cash_movements\":0,\"sales\":0,\"sale_items\":0,\"sale_cancellations\":0,\"comandas\":0,\"comanda_itens\":0,\"audit_log\":0}','Recebido via API'),('7d5b16df-f1f1-4003-a6d8-0c30ba29570e','71320070-b210-4848-860c-38df600f996e','771c6df1-0c9a-4493-a6e7-10edd6e07bee',33,'LC Multimarcas','PARTIAL','SUCCESS','2026-06-18 00:06:35','2026-06-18 00:06:36','2026-06-18 00:06:36',1,'{\"users\":0,\"suppliers\":0,\"clients\":0,\"products\":0,\"stock_movements\":0,\"cash_sessions\":1,\"cash_movements\":0,\"sales\":0,\"sale_items\":0,\"sale_cancellations\":0,\"comandas\":0,\"comanda_itens\":0,\"audit_log\":0}','Recebido via API'),('7d5b16df-f1f1-4003-a6d8-0c30ba29570e','71320070-b210-4848-860c-38df600f996e','771c6df1-0c9a-4493-a6e7-10edd6e07bee',34,'LC Multimarcas','PARTIAL','SUCCESS','2026-06-18 00:06:50','2026-06-18 00:07:06','2026-06-18 00:07:06',1,'{\"users\":0,\"suppliers\":0,\"clients\":0,\"products\":0,\"stock_movements\":0,\"cash_sessions\":1,\"cash_movements\":0,\"sales\":0,\"sale_items\":0,\"sale_cancellations\":0,\"comandas\":0,\"comanda_itens\":0,\"audit_log\":0}','Recebido via API'),('7d5b16df-f1f1-4003-a6d8-0c30ba29570e','71320070-b210-4848-860c-38df600f996e','771c6df1-0c9a-4493-a6e7-10edd6e07bee',35,'LC Multimarcas','PARTIAL','SUCCESS','2026-06-18 00:07:05','2026-06-18 00:07:20','2026-06-18 00:07:20',6,'{\"users\":0,\"suppliers\":0,\"clients\":0,\"products\":2,\"stock_movements\":0,\"cash_sessions\":1,\"cash_movements\":0,\"sales\":1,\"sale_items\":2,\"sale_cancellations\":0,\"comandas\":0,\"comanda_itens\":0,\"audit_log\":0}','Recebido via API'),('7d5b16df-f1f1-4003-a6d8-0c30ba29570e','71320070-b210-4848-860c-38df600f996e','771c6df1-0c9a-4493-a6e7-10edd6e07bee',36,'LC Multimarcas','PARTIAL','SUCCESS','2026-06-18 00:19:14','2026-06-18 00:19:15','2026-06-18 00:19:15',6,'{\"users\":0,\"suppliers\":0,\"clients\":0,\"products\":2,\"stock_movements\":0,\"cash_sessions\":1,\"cash_movements\":0,\"sales\":1,\"sale_items\":2,\"sale_cancellations\":0,\"comandas\":0,\"comanda_itens\":0,\"audit_log\":0}','Recebido via API'),('7d5b16df-f1f1-4003-a6d8-0c30ba29570e','71320070-b210-4848-860c-38df600f996e','771c6df1-0c9a-4493-a6e7-10edd6e07bee',37,'LC Multimarcas','PARTIAL','SUCCESS','2026-06-18 00:39:28','2026-06-18 00:39:29','2026-06-18 00:39:29',8,'{\"users\":0,\"suppliers\":0,\"clients\":0,\"products\":3,\"stock_movements\":0,\"cash_sessions\":1,\"cash_movements\":0,\"sales\":1,\"sale_items\":3,\"sale_cancellations\":0,\"comandas\":0,\"comanda_itens\":0,\"audit_log\":0}','Recebido via API'),('7d5b16df-f1f1-4003-a6d8-0c30ba29570e','71320070-b210-4848-860c-38df600f996e','771c6df1-0c9a-4493-a6e7-10edd6e07bee',38,'LC Multimarcas','PARTIAL','SUCCESS','2026-06-18 00:44:09','2026-06-18 00:44:09','2026-06-18 00:44:10',2,'{\"users\":0,\"suppliers\":0,\"clients\":0,\"products\":0,\"stock_movements\":0,\"cash_sessions\":1,\"cash_movements\":1,\"sales\":0,\"sale_items\":0,\"sale_cancellations\":0,\"comandas\":0,\"comanda_itens\":0,\"audit_log\":0}','Recebido via API');

DROP TABLE IF EXISTS `tenant_devices`;
CREATE TABLE `tenant_devices` (
  `tenant_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `store_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `id` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL,
  `name` varchar(180) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_id` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ACTIVE',
  `last_seen_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`tenant_id`,`store_id`,`id`),
  KEY `idx_tenant_devices_source` (`tenant_id`,`store_id`,`source_id`),
  KEY `idx_tenant_devices_seen` (`tenant_id`,`store_id`,`last_seen_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `tenant_devices` VALUES ('7d5b16df-f1f1-4003-a6d8-0c30ba29570e','71320070-b210-4848-860c-38df600f996e','771c6df1-0c9a-4493-a6e7-10edd6e07bee','771c6df1-0c9a-4493-a6e7-10edd6e07bee','LC Multimarcas','ACTIVE','2026-06-18 00:44:09','2026-06-15 15:50:10','2026-06-18 00:46:11'),('legacy','LC Multimarcas','legacy-device','legacy-device','LC Multimarcas','ACTIVE','2026-06-15 14:38:59','2026-06-15 14:44:16','2026-06-18 00:46:11'),('legacy','WEB','legacy-device','legacy-device','LC Multimarcas','ACTIVE','2026-06-15 14:47:01','2026-06-15 15:14:35','2026-06-18 00:46:11'),('legacy','ZENTRIX-PDV-LOJA-CENTRO','legacy-device','legacy-device','ZENTRIX-PDV-LOJA-CENTRO','ACTIVE','2026-06-11 14:12:57','2026-06-15 14:44:16','2026-06-18 00:46:11');

DROP TABLE IF EXISTS `tenant_stores`;
CREATE TABLE `tenant_stores` (
  `tenant_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `name` varchar(180) COLLATE utf8mb4_unicode_ci NOT NULL,
  `source_id` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ACTIVE',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`tenant_id`,`id`),
  KEY `idx_tenant_stores_source` (`tenant_id`,`source_id`),
  KEY `idx_tenant_stores_status` (`tenant_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `tenant_stores` VALUES ('7d5b16df-f1f1-4003-a6d8-0c30ba29570e','71320070-b210-4848-860c-38df600f996e','Lc Multimarcas','LC Multimarcas','ACTIVE','2026-06-15 15:17:46','2026-06-18 00:46:11'),('legacy','LC Multimarcas','LC Multimarcas','LC Multimarcas','ACTIVE','2026-06-15 14:44:16','2026-06-18 00:46:11'),('legacy','WEB','WEB','LC Multimarcas','ACTIVE','2026-06-15 15:14:35','2026-06-18 00:46:11'),('legacy','ZENTRIX-PDV-LOJA-CENTRO','ZENTRIX-PDV-LOJA-CENTRO','ZENTRIX-PDV-LOJA-CENTRO','ACTIVE','2026-06-15 14:44:16','2026-06-18 00:46:11');

DROP TABLE IF EXISTS `tenants`;
CREATE TABLE `tenants` (
  `id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `name` varchar(180) COLLATE utf8mb4_unicode_ci NOT NULL,
  `document` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ACTIVE',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_tenants_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `tenants` VALUES ('7d5b16df-f1f1-4003-a6d8-0c30ba29570e','7d5b16df-f1f1-4003-a6d8-0c30ba29570e',NULL,'ACTIVE','2026-06-15 15:17:46','2026-06-18 00:46:11'),('legacy','Cliente legado',NULL,'ACTIVE','2026-06-15 14:44:16','2026-06-18 00:46:11');

DROP TABLE IF EXISTS `users`;
CREATE TABLE `users` (
  `tenant_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'legacy',
  `store_id` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'WEB',
  `device_id` varchar(120) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_id` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'WEB',
  `username` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `password` varchar(120) COLLATE utf8mb4_unicode_ci NOT NULL,
  `display_name` varchar(140) COLLATE utf8mb4_unicode_ci NOT NULL,
  `role` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'OPERATOR',
  `active` tinyint(1) NOT NULL DEFAULT '1',
  `created_at` datetime DEFAULT NULL,
  `updated_at` datetime DEFAULT NULL,
  `last_login_at` datetime DEFAULT NULL,
  `permissions_json` text COLLATE utf8mb4_unicode_ci,
  PRIMARY KEY (`tenant_id`,`store_id`,`username`),
  KEY `idx_users_active` (`active`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `users` VALUES ('legacy','LC Multimarcas','legacy-device','LC Multimarcas','admin','$2a$12$jrbcosMXc43lW5goxlyE2exZVgylKOuA4hN17XTME7ZEr6eM4nOkW','Administrador Zentrix','ADMIN',1,NULL,NULL,'2026-06-18 00:44:58',NULL),('legacy','LC Multimarcas','legacy-device','LC Multimarcas','teste','$2a$12$7tc/kA66thsih5OyI10RN.eBPDMivgAp/XrOcS1/1kY3FJ92IOi16','João','OPERATOR',1,NULL,NULL,NULL,NULL);

SET FOREIGN_KEY_CHECKS = 1;
