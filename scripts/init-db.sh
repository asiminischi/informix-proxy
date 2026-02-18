#!/bin/bash
#
# Informix Database Initialization Script
# Creates the testdb database with tables and sample data.
#
# Works in two modes:
#   1) Inside informix-db container:  docker exec informix-db bash /opt/ibm/config/init-db.sh
#   2) From db-init sidecar:          automatically via docker compose
#

set -e

# ============================================================================
# INFORMIX ENVIRONMENT
# ============================================================================

export INFORMIXDIR="${INFORMIXDIR:-/opt/ibm/informix}"
export INFORMIXSERVER="${INFORMIXSERVER:-informix}"
export ONCONFIG="${ONCONFIG:-onconfig}"
export PATH="${INFORMIXDIR}/bin:${PATH}"

# ============================================================================
# CONFIGURATION
# ============================================================================

DB_NAME="${DB_NAME:-testdb}"
LOG_FILE="/tmp/init-db.log"

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" | tee -a "$LOG_FILE"; }
error() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] ERROR: $*" | tee -a "$LOG_FILE" >&2; }

# ============================================================================
# PRE-FLIGHT CHECKS
# ============================================================================

if ! command -v dbaccess >/dev/null 2>&1; then
    error "dbaccess not found in PATH. Ensure INFORMIXDIR is correct."
    exit 1
fi

log "========================================"
log "Informix Database Initialization"
log "========================================"
log "Database: $DB_NAME"
log "Server:   $INFORMIXSERVER"

# Wait for Informix to accept connections (works locally and over network)
log "Waiting for Informix server..."
MAX_WAIT=300
WAITED=0
while [ $WAITED -lt $MAX_WAIT ]; do
    if echo "SELECT COUNT(*) FROM systables WHERE tabid=1;" | dbaccess sysmaster 2>/dev/null | grep -q "1"; then
        log "Informix is online and accepting connections."
        break
    fi
    sleep 5
    WAITED=$((WAITED + 5))
done
if [ $WAITED -ge $MAX_WAIT ]; then
    error "Timeout waiting for Informix."
    exit 1
fi
sleep 3

# ============================================================================
# CHECK IF ALREADY INITIALIZED
# ============================================================================

if echo "SELECT name FROM sysdatabases WHERE name = '$DB_NAME';" | dbaccess sysmaster 2>/dev/null | grep -q "$DB_NAME"; then
    log "Database '$DB_NAME' already exists — skipping initialization."
    log "To reinitialize: echo 'DROP DATABASE $DB_NAME;' | dbaccess sysmaster"
    exit 0
fi

# ============================================================================
# CREATE DATABASE
# ============================================================================

log "Creating database '$DB_NAME'..."
dbaccess sysmaster - <<EOSQL 2>&1 | tee -a "$LOG_FILE"
CREATE DATABASE $DB_NAME WITH LOG;
EOSQL
log "Database created."

# ============================================================================
# CREATE TABLES
# ============================================================================

log "Creating tables..."
dbaccess $DB_NAME - <<'EOSQL' 2>&1 | tee -a "$LOG_FILE"

CREATE TABLE customer (
    customer_id SERIAL PRIMARY KEY,
    first_name  VARCHAR(50) NOT NULL,
    last_name   VARCHAR(50) NOT NULL,
    email       VARCHAR(100),
    company     VARCHAR(100),
    phone       VARCHAR(20),
    city        VARCHAR(50),
    state       VARCHAR(20),
    country     VARCHAR(50) DEFAULT 'USA',
    created_at  DATETIME YEAR TO SECOND DEFAULT CURRENT YEAR TO SECOND
);

CREATE TABLE products (
    product_id     SERIAL PRIMARY KEY,
    product_name   VARCHAR(100) NOT NULL,
    description    VARCHAR(255),
    price          DECIMAL(10,2) NOT NULL,
    cost           DECIMAL(10,2),
    stock_quantity INTEGER DEFAULT 0,
    category       VARCHAR(50),
    sku            VARCHAR(50),
    is_active      CHAR(1) DEFAULT 'Y',
    created_at     DATETIME YEAR TO SECOND DEFAULT CURRENT YEAR TO SECOND
);

CREATE TABLE orders (
    order_id     SERIAL PRIMARY KEY,
    customer_id  INTEGER NOT NULL,
    order_date   DATE DEFAULT TODAY,
    ship_date    DATE,
    total_amount DECIMAL(12,2) DEFAULT 0,
    status       VARCHAR(20) DEFAULT 'pending',
    payment_method VARCHAR(20),
    created_at   DATETIME YEAR TO SECOND DEFAULT CURRENT YEAR TO SECOND,
    FOREIGN KEY (customer_id) REFERENCES customer(customer_id)
);

CREATE TABLE order_items (
    item_id      SERIAL PRIMARY KEY,
    order_id     INTEGER NOT NULL,
    product_id   INTEGER NOT NULL,
    quantity     INTEGER NOT NULL,
    unit_price   DECIMAL(10,2) NOT NULL,
    line_total   DECIMAL(12,2),
    FOREIGN KEY (order_id)   REFERENCES orders(order_id),
    FOREIGN KEY (product_id) REFERENCES products(product_id)
);

CREATE TABLE inventory_movements (
    movement_id    SERIAL PRIMARY KEY,
    product_id     INTEGER NOT NULL,
    movement_type  VARCHAR(20) NOT NULL,
    quantity       INTEGER NOT NULL,
    reference_id   INTEGER,
    notes          VARCHAR(200),
    movement_date  DATETIME YEAR TO SECOND DEFAULT CURRENT YEAR TO SECOND,
    FOREIGN KEY (product_id) REFERENCES products(product_id)
);

CREATE INDEX idx_customer_email     ON customer(email);
CREATE INDEX idx_customer_name      ON customer(last_name, first_name);
CREATE INDEX idx_orders_customer    ON orders(customer_id);
CREATE INDEX idx_orders_status      ON orders(status);
CREATE INDEX idx_order_items_order  ON order_items(order_id);
CREATE INDEX idx_products_category  ON products(category);
CREATE INDEX idx_products_sku       ON products(sku);
CREATE INDEX idx_inventory_product  ON inventory_movements(product_id);

EOSQL
log "Tables created."

# ============================================================================
# INSERT SAMPLE DATA
# ============================================================================

log "Inserting sample data..."
dbaccess $DB_NAME - <<'EOSQL' 2>&1 | tee -a "$LOG_FILE"

-- Customers (10)
INSERT INTO customer (first_name, last_name, email, company, phone, city, state) VALUES ('John',    'Doe',      'john.doe@example.com',     'Acme Corp',    '555-0001', 'New York',      'NY');
INSERT INTO customer (first_name, last_name, email, company, phone, city, state) VALUES ('Jane',    'Smith',    'jane.smith@techco.com',    'TechCo',       '555-0002', 'San Francisco', 'CA');
INSERT INTO customer (first_name, last_name, email, company, phone, city, state) VALUES ('Bob',     'Johnson',  'bob.j@datasys.com',        'DataSys',      '555-0003', 'Chicago',       'IL');
INSERT INTO customer (first_name, last_name, email, company, phone, city, state) VALUES ('Alice',   'Williams', 'alice.w@cloudnet.com',     'CloudNet',     '555-0004', 'Seattle',       'WA');
INSERT INTO customer (first_name, last_name, email, company, phone, city, state) VALUES ('Charlie', 'Brown',    'charlie.b@webdev.com',     'WebDev Inc',   '555-0005', 'Austin',        'TX');
INSERT INTO customer (first_name, last_name, email, company, phone, city, state) VALUES ('Diana',   'Davis',    'diana.d@infotech.com',     'InfoTech',     '555-0006', 'Boston',        'MA');
INSERT INTO customer (first_name, last_name, email, company, phone, city, state) VALUES ('Eve',     'Martinez', 'eve.m@softcorp.com',       'SoftCorp',     '555-0007', 'Denver',        'CO');
INSERT INTO customer (first_name, last_name, email, company, phone, city, state) VALUES ('Frank',   'Garcia',   'frank.g@netsol.com',       'NetSolutions', '555-0008', 'Portland',      'OR');
INSERT INTO customer (first_name, last_name, email, company, phone, city, state) VALUES ('Grace',   'Rodriguez','grace.r@digital.com',      'Digital LLC',  '555-0009', 'Miami',         'FL');
INSERT INTO customer (first_name, last_name, email, company, phone, city, state) VALUES ('Henry',   'Wilson',   'henry.w@codefactory.com',  'CodeFactory',  '555-0010', 'Atlanta',       'GA');

-- Products (15)
INSERT INTO products (product_name, description, price, cost, stock_quantity, category, sku) VALUES ('Laptop Pro 15',       'High-performance laptop 16GB RAM',   1299.99, 899.99, 50,  'Electronics',  'LAP-PRO-15');
INSERT INTO products (product_name, description, price, cost, stock_quantity, category, sku) VALUES ('Wireless Mouse',      'Ergonomic wireless mouse',             29.99,  12.99, 200, 'Accessories',  'MOU-WIR-01');
INSERT INTO products (product_name, description, price, cost, stock_quantity, category, sku) VALUES ('USB-C Hub',           '7-in-1 USB-C hub with HDMI',           49.99,  22.99, 150, 'Accessories',  'HUB-USB-C7');
INSERT INTO products (product_name, description, price, cost, stock_quantity, category, sku) VALUES ('Monitor 27 4K',       '4K UHD monitor with HDR',             399.99, 249.99,  75, 'Electronics',  'MON-27-4K');
INSERT INTO products (product_name, description, price, cost, stock_quantity, category, sku) VALUES ('Mechanical Keyboard', 'RGB mechanical keyboard Cherry MX',    89.99,  45.99, 100, 'Accessories',  'KEY-MECH-RGB');
INSERT INTO products (product_name, description, price, cost, stock_quantity, category, sku) VALUES ('Webcam HD',           '1080p webcam with auto-focus',         59.99,  29.99, 120, 'Electronics',  'CAM-HD-1080');
INSERT INTO products (product_name, description, price, cost, stock_quantity, category, sku) VALUES ('Desk Lamp LED',       'Adjustable LED desk lamp',             34.99,  15.99,  80, 'Office',       'LAMP-LED-USB');
INSERT INTO products (product_name, description, price, cost, stock_quantity, category, sku) VALUES ('Office Chair',        'Ergonomic chair with lumbar support', 249.99, 149.99,  30, 'Furniture',    'CHAIR-ERG-01');
INSERT INTO products (product_name, description, price, cost, stock_quantity, category, sku) VALUES ('Standing Desk',       'Electric adjustable desk 60x30',      499.99, 299.99,  20, 'Furniture',    'DESK-STAND-60');
INSERT INTO products (product_name, description, price, cost, stock_quantity, category, sku) VALUES ('Headphones NC',       'Noise-canceling over-ear headphones', 199.99,  99.99,  90, 'Electronics',  'HEAD-NC-PRO');
INSERT INTO products (product_name, description, price, cost, stock_quantity, category, sku) VALUES ('External SSD 1TB',    'Portable SSD USB 3.2',                119.99,  69.99, 110, 'Storage',      'SSD-EXT-1TB');
INSERT INTO products (product_name, description, price, cost, stock_quantity, category, sku) VALUES ('Bluetooth Speaker',   'Portable waterproof BT speaker',       79.99,  39.99, 105, 'Electronics',  'SPK-BT-PORT');
INSERT INTO products (product_name, description, price, cost, stock_quantity, category, sku) VALUES ('Laptop Bag 17',       'Professional laptop bag',              59.99,  29.99,  80, 'Accessories',  'BAG-LAP-17');
INSERT INTO products (product_name, description, price, cost, stock_quantity, category, sku) VALUES ('Power Strip 12',      '12-outlet surge protector',            39.99,  19.99, 100, 'Accessories',  'POWER-STRIP-12');
INSERT INTO products (product_name, description, price, cost, stock_quantity, category, sku) VALUES ('HDMI Cable 10ft',     'High-speed HDMI 2.1 cable',            16.99,   7.99, 250, 'Accessories',  'HDMI-10FT-21');

-- Orders (10)
INSERT INTO orders (customer_id, order_date, ship_date, total_amount, status, payment_method) VALUES (1,  TODAY,      TODAY + 1,  1329.98, 'shipped',     'credit_card');
INSERT INTO orders (customer_id, order_date, ship_date, total_amount, status, payment_method) VALUES (2,  TODAY - 1,  TODAY,       449.98, 'delivered',   'paypal');
INSERT INTO orders (customer_id, order_date, ship_date, total_amount, status, payment_method) VALUES (3,  TODAY - 2,  NULL,         89.99, 'processing',  'credit_card');
INSERT INTO orders (customer_id, order_date, ship_date, total_amount, status, payment_method) VALUES (4,  TODAY - 3,  TODAY - 2,   749.97, 'delivered',   'debit_card');
INSERT INTO orders (customer_id, order_date, ship_date, total_amount, status, payment_method) VALUES (5,  TODAY - 5,  TODAY - 4,   199.99, 'delivered',   'credit_card');
INSERT INTO orders (customer_id, order_date, ship_date, total_amount, status, payment_method) VALUES (6,  TODAY - 7,  NULL,       1799.97, 'processing',  'paypal');
INSERT INTO orders (customer_id, order_date, ship_date, total_amount, status, payment_method) VALUES (7,  TODAY - 10, TODAY - 9,   119.98, 'shipped',     'credit_card');
INSERT INTO orders (customer_id, order_date, ship_date, total_amount, status, payment_method) VALUES (8,  TODAY - 14, TODAY - 13,  549.98, 'delivered',   'debit_card');
INSERT INTO orders (customer_id, order_date, ship_date, total_amount, status, payment_method) VALUES (9,  TODAY - 20, NULL,        299.98, 'cancelled',   'credit_card');
INSERT INTO orders (customer_id, order_date, ship_date, total_amount, status, payment_method) VALUES (10, TODAY - 30, TODAY - 28, 1549.96, 'delivered',   'paypal');

-- Order items
INSERT INTO order_items (order_id, product_id, quantity, unit_price, line_total) VALUES (1,  1,  1, 1299.99, 1299.99);
INSERT INTO order_items (order_id, product_id, quantity, unit_price, line_total) VALUES (1,  2,  1,   29.99,   29.99);
INSERT INTO order_items (order_id, product_id, quantity, unit_price, line_total) VALUES (2,  4,  1,  399.99,  399.99);
INSERT INTO order_items (order_id, product_id, quantity, unit_price, line_total) VALUES (2,  3,  1,   49.99,   49.99);
INSERT INTO order_items (order_id, product_id, quantity, unit_price, line_total) VALUES (3,  5,  1,   89.99,   89.99);
INSERT INTO order_items (order_id, product_id, quantity, unit_price, line_total) VALUES (4,  8,  3,  249.99,  749.97);
INSERT INTO order_items (order_id, product_id, quantity, unit_price, line_total) VALUES (5, 10,  1,  199.99,  199.99);
INSERT INTO order_items (order_id, product_id, quantity, unit_price, line_total) VALUES (6,  1,  1, 1299.99, 1299.99);
INSERT INTO order_items (order_id, product_id, quantity, unit_price, line_total) VALUES (6,  9,  1,  499.99,  499.99);
INSERT INTO order_items (order_id, product_id, quantity, unit_price, line_total) VALUES (7,  2,  2,   29.99,   59.98);
INSERT INTO order_items (order_id, product_id, quantity, unit_price, line_total) VALUES (7,  7,  1,   34.99,   34.99);
INSERT INTO order_items (order_id, product_id, quantity, unit_price, line_total) VALUES (8,  4,  1,  399.99,  399.99);
INSERT INTO order_items (order_id, product_id, quantity, unit_price, line_total) VALUES (8,  6,  2,   59.99,  119.98);
INSERT INTO order_items (order_id, product_id, quantity, unit_price, line_total) VALUES (9,  5,  2,   89.99,  179.98);
INSERT INTO order_items (order_id, product_id, quantity, unit_price, line_total) VALUES (9, 11,  1,  119.99,  119.99);
INSERT INTO order_items (order_id, product_id, quantity, unit_price, line_total) VALUES (10, 1,  1, 1299.99, 1299.99);
INSERT INTO order_items (order_id, product_id, quantity, unit_price, line_total) VALUES (10, 8,  1,  249.99,  249.99);

-- Inventory movements (initial stock + some sales)
INSERT INTO inventory_movements (product_id, movement_type, quantity, notes) VALUES (1,  'purchase',  50, 'Initial stock');
INSERT INTO inventory_movements (product_id, movement_type, quantity, notes) VALUES (2,  'purchase', 200, 'Initial stock');
INSERT INTO inventory_movements (product_id, movement_type, quantity, notes) VALUES (4,  'purchase',  75, 'Initial stock');
INSERT INTO inventory_movements (product_id, movement_type, quantity, notes) VALUES (5,  'purchase', 100, 'Initial stock');
INSERT INTO inventory_movements (product_id, movement_type, quantity, notes) VALUES (8,  'purchase',  30, 'Initial stock');
INSERT INTO inventory_movements (product_id, movement_type, quantity, notes) VALUES (10, 'purchase',  90, 'Initial stock');
INSERT INTO inventory_movements (product_id, movement_type, quantity, reference_id, notes) VALUES (1,  'sale', -2, 1, 'Order 1 and 6');
INSERT INTO inventory_movements (product_id, movement_type, quantity, reference_id, notes) VALUES (2,  'sale', -3, 1, 'Order 1 and 7');
INSERT INTO inventory_movements (product_id, movement_type, quantity, reference_id, notes) VALUES (4,  'sale', -2, 2, 'Order 2 and 8');
INSERT INTO inventory_movements (product_id, movement_type, quantity, reference_id, notes) VALUES (10, 'sale', -1, 5, 'Order 5');
INSERT INTO inventory_movements (product_id, movement_type, quantity, reference_id, notes) VALUES (8,  'sale', -4, 4, 'Order 4 and 10');

EOSQL
log "Sample data inserted."

# ============================================================================
# STORED PROCEDURES
# ============================================================================

log "Creating stored procedures..."
dbaccess $DB_NAME - <<'EOSQL' 2>&1 | tee -a "$LOG_FILE"

CREATE PROCEDURE get_customer_orders(p_cust_id INT)
RETURNING INT AS order_id, DATE AS order_date, DECIMAL AS total_amount,
          VARCHAR(20) AS status, INT AS item_count;

    DEFINE v_oid INT;
    DEFINE v_odate DATE;
    DEFINE v_total DECIMAL(12,2);
    DEFINE v_status VARCHAR(20);
    DEFINE v_cnt INT;

    FOREACH
        SELECT o.order_id, o.order_date, o.total_amount, o.status, COUNT(oi.item_id)
        INTO v_oid, v_odate, v_total, v_status, v_cnt
        FROM orders o LEFT JOIN order_items oi ON o.order_id = oi.order_id
        WHERE o.customer_id = p_cust_id
        GROUP BY o.order_id, o.order_date, o.total_amount, o.status
        ORDER BY o.order_date DESC
        RETURN v_oid, v_odate, v_total, v_status, v_cnt WITH RESUME;
    END FOREACH;
END PROCEDURE;

CREATE FUNCTION calculate_order_total(p_ord_id INT) RETURNING DECIMAL(12,2);
    DEFINE v_total DECIMAL(12,2);
    SELECT SUM(line_total) INTO v_total FROM order_items WHERE order_id = p_ord_id;
    RETURN NVL(v_total, 0);
END FUNCTION;

CREATE FUNCTION get_customer_ltv(p_cust_id INT) RETURNING DECIMAL(12,2);
    DEFINE v_ltv DECIMAL(12,2);
    SELECT SUM(total_amount) INTO v_ltv FROM orders
    WHERE customer_id = p_cust_id AND status IN ('shipped', 'delivered');
    RETURN NVL(v_ltv, 0);
END FUNCTION;

EOSQL
log "Stored procedures created."

# ============================================================================
# GRANT PERMISSIONS
# ============================================================================

log "Granting permissions..."
dbaccess $DB_NAME - <<EOSQL 2>&1 | tee -a "$LOG_FILE"
GRANT ALL ON customer             TO PUBLIC;
GRANT ALL ON products             TO PUBLIC;
GRANT ALL ON orders               TO PUBLIC;
GRANT ALL ON order_items          TO PUBLIC;
GRANT ALL ON inventory_movements  TO PUBLIC;
GRANT EXECUTE ON PROCEDURE get_customer_orders  TO PUBLIC;
GRANT EXECUTE ON FUNCTION  calculate_order_total TO PUBLIC;
GRANT EXECUTE ON FUNCTION  get_customer_ltv      TO PUBLIC;
EOSQL
log "Permissions granted."

# ============================================================================
# VERIFY
# ============================================================================

log ""
log "Verifying..."
for tbl in customer products orders order_items inventory_movements; do
    cnt=$(echo "SELECT COUNT(*) FROM $tbl;" | dbaccess $DB_NAME 2>/dev/null | grep -Eo '[0-9]+' | tail -1)
    log "  $tbl: $cnt rows"
done

log ""
log "========================================"
log "Database initialization complete!"
log "========================================"
log "  Tables: customer(10), products(15), orders(10),"
log "          order_items(17), inventory_movements(11)"
log "  Stored procs: get_customer_orders, calculate_order_total, get_customer_ltv"
log ""
log "  Connection: host=informix-db port=9088 db=$DB_NAME user=informix pw=in4mix"
log "========================================"

exit 0
