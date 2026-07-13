-- =====================================================================
-- 01_schema.sql : 학습용 예제 데이터베이스 `shop` 스키마
-- ---------------------------------------------------------------------
-- 가상의 온라인 쇼핑몰입니다. 모든 Step 이 이 스키마를 공유합니다.
--
--   categories ──┐
--                └─< products ──< order_items >── orders >── customers
--                       │                            │           │
--                       └──< reviews >───────────────┘           │
--                                     └───────────────────────────┘
--   employees (자기참조: manager_id → employee_id)
--   tally     (1~10000 숫자 테이블. 데이터 생성/보조용)
--
-- 실행:  mysql -h127.0.0.1 -P3307 -ulearner -plearn1234 shop < 01_schema.sql
-- =====================================================================

CREATE DATABASE IF NOT EXISTS shop
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE shop;

-- 재실행 가능하도록 자식 테이블부터 삭제 (FK 역순)
DROP TABLE IF EXISTS reviews;
DROP TABLE IF EXISTS payments;
DROP TABLE IF EXISTS order_items;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS products;
DROP TABLE IF EXISTS categories;
DROP TABLE IF EXISTS customers;
DROP TABLE IF EXISTS employees;
DROP TABLE IF EXISTS tally;

-- ---------------------------------------------------------------------
-- tally : 1 ~ 10000 숫자 테이블
--   "숫자 테이블"은 SQL 로 데이터를 만들어낼 때 매우 유용한 도구입니다.
--   (예: 주문 1건당 상품 N개 생성, 날짜 채우기, 빈 구간 메우기)
-- ---------------------------------------------------------------------
CREATE TABLE tally (
  n INT UNSIGNED NOT NULL PRIMARY KEY
) ENGINE=InnoDB COMMENT='보조용 숫자 테이블 1..10000';

-- ---------------------------------------------------------------------
-- categories : 상품 카테고리 (parent_id 로 자기참조 → 계층 구조)
-- ---------------------------------------------------------------------
CREATE TABLE categories (
  category_id  INT UNSIGNED NOT NULL AUTO_INCREMENT,
  parent_id    INT UNSIGNED NULL COMMENT '상위 카테고리. NULL 이면 최상위',
  name         VARCHAR(50)  NOT NULL,
  sort_order   SMALLINT     NOT NULL DEFAULT 0,
  PRIMARY KEY (category_id),
  CONSTRAINT fk_categories_parent
    FOREIGN KEY (parent_id) REFERENCES categories(category_id)
    ON DELETE RESTRICT
) ENGINE=InnoDB COMMENT='상품 카테고리(계층형)';

-- ---------------------------------------------------------------------
-- customers : 고객
-- ---------------------------------------------------------------------
CREATE TABLE customers (
  customer_id  INT UNSIGNED NOT NULL AUTO_INCREMENT,
  email        VARCHAR(120) NOT NULL,
  name         VARCHAR(50)  NOT NULL,
  phone        VARCHAR(20)  NULL,
  grade        ENUM('BRONZE','SILVER','GOLD','VIP') NOT NULL DEFAULT 'BRONZE',
  birth_date   DATE         NULL,
  city         VARCHAR(30)  NOT NULL,
  points       INT          NOT NULL DEFAULT 0 COMMENT '적립 포인트',
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (customer_id),
  UNIQUE KEY uk_customers_email (email)
) ENGINE=InnoDB COMMENT='고객';

-- ---------------------------------------------------------------------
-- products : 상품
--   attrs 는 JSON 컬럼입니다 (Step 18 에서 집중적으로 다룹니다).
-- ---------------------------------------------------------------------
CREATE TABLE products (
  product_id   INT UNSIGNED NOT NULL AUTO_INCREMENT,
  category_id  INT UNSIGNED NOT NULL,
  name         VARCHAR(100) NOT NULL,
  price        DECIMAL(10,2) NOT NULL COMMENT '판매가',
  cost         DECIMAL(10,2) NOT NULL COMMENT '원가',
  stock        INT          NOT NULL DEFAULT 0,
  status       ENUM('ON_SALE','SOLD_OUT','HIDDEN') NOT NULL DEFAULT 'ON_SALE',
  attrs        JSON         NULL COMMENT '상품별 가변 속성',
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (product_id),
  KEY idx_products_category (category_id),
  CONSTRAINT fk_products_category
    FOREIGN KEY (category_id) REFERENCES categories(category_id),
  CONSTRAINT chk_products_price CHECK (price >= 0),
  CONSTRAINT chk_products_stock CHECK (stock >= 0)
) ENGINE=InnoDB COMMENT='상품';

-- ---------------------------------------------------------------------
-- orders : 주문 헤더
--   total_amount 는 order_items 합계로 계산되어 채워집니다.
-- ---------------------------------------------------------------------
CREATE TABLE orders (
  order_id      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  customer_id   INT UNSIGNED NOT NULL,
  order_date    DATETIME     NOT NULL,
  status        ENUM('PENDING','PAID','SHIPPED','DELIVERED','CANCELLED') NOT NULL DEFAULT 'PENDING',
  total_amount  DECIMAL(12,2) NOT NULL DEFAULT 0,
  shipping_city VARCHAR(30)  NOT NULL,
  PRIMARY KEY (order_id),
  KEY idx_orders_customer (customer_id),
  CONSTRAINT fk_orders_customer
    FOREIGN KEY (customer_id) REFERENCES customers(customer_id)
) ENGINE=InnoDB COMMENT='주문';

-- ---------------------------------------------------------------------
-- order_items : 주문 상세 (주문 1 : N 상품)
-- ---------------------------------------------------------------------
CREATE TABLE order_items (
  order_item_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  order_id      BIGINT UNSIGNED NOT NULL,
  product_id    INT UNSIGNED NOT NULL,
  quantity      INT          NOT NULL,
  unit_price    DECIMAL(10,2) NOT NULL COMMENT '주문 시점의 가격(스냅샷)',
  PRIMARY KEY (order_item_id),
  KEY idx_order_items_order (order_id),
  KEY idx_order_items_product (product_id),
  CONSTRAINT fk_order_items_order
    FOREIGN KEY (order_id) REFERENCES orders(order_id) ON DELETE CASCADE,
  CONSTRAINT fk_order_items_product
    FOREIGN KEY (product_id) REFERENCES products(product_id),
  CONSTRAINT chk_order_items_qty CHECK (quantity > 0)
) ENGINE=InnoDB COMMENT='주문 상세';

-- ---------------------------------------------------------------------
-- payments : 결제
-- ---------------------------------------------------------------------
CREATE TABLE payments (
  payment_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  order_id   BIGINT UNSIGNED NOT NULL,
  method     ENUM('CARD','BANK','POINT','MOBILE') NOT NULL,
  amount     DECIMAL(12,2) NOT NULL,
  status     ENUM('DONE','REFUNDED') NOT NULL DEFAULT 'DONE',
  paid_at    DATETIME NOT NULL,
  PRIMARY KEY (payment_id),
  KEY idx_payments_order (order_id),
  CONSTRAINT fk_payments_order
    FOREIGN KEY (order_id) REFERENCES orders(order_id) ON DELETE CASCADE
) ENGINE=InnoDB COMMENT='결제';

-- ---------------------------------------------------------------------
-- reviews : 상품 후기
-- ---------------------------------------------------------------------
CREATE TABLE reviews (
  review_id   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  product_id  INT UNSIGNED NOT NULL,
  customer_id INT UNSIGNED NOT NULL,
  rating      TINYINT UNSIGNED NOT NULL,
  title       VARCHAR(100) NULL,
  body        TEXT NULL,
  created_at  DATETIME NOT NULL,
  PRIMARY KEY (review_id),
  KEY idx_reviews_product (product_id),
  KEY idx_reviews_customer (customer_id),
  CONSTRAINT fk_reviews_product
    FOREIGN KEY (product_id) REFERENCES products(product_id) ON DELETE CASCADE,
  CONSTRAINT fk_reviews_customer
    FOREIGN KEY (customer_id) REFERENCES customers(customer_id) ON DELETE CASCADE,
  CONSTRAINT chk_reviews_rating CHECK (rating BETWEEN 1 AND 5)
) ENGINE=InnoDB COMMENT='상품 후기';

-- ---------------------------------------------------------------------
-- employees : 사원 (자기참조 계층 → 재귀 CTE / SELF JOIN 실습용)
-- ---------------------------------------------------------------------
CREATE TABLE employees (
  employee_id INT UNSIGNED NOT NULL AUTO_INCREMENT,
  name        VARCHAR(50) NOT NULL,
  manager_id  INT UNSIGNED NULL,
  dept        VARCHAR(30) NOT NULL,
  position    VARCHAR(30) NOT NULL,
  salary      DECIMAL(10,2) NOT NULL,
  hire_date   DATE NOT NULL,
  PRIMARY KEY (employee_id),
  KEY idx_employees_manager (manager_id),
  CONSTRAINT fk_employees_manager
    FOREIGN KEY (manager_id) REFERENCES employees(employee_id)
) ENGINE=InnoDB COMMENT='사원(계층형 조직)';
