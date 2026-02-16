#!/bin/bash
#
# Informix Database Initialization Script
# Runs on container startup to create test database and sample data
#

set -e

echo "===================================="
echo "Informix Test Database Init Script"
echo "===================================="

# Wait for Informix to be fully ready
echo "Waiting for Informix server to be ready..."
sleep 30

# Create test database
echo "Creating test database: testdb..."
dbaccess sysmaster - <<EOF || echo "Database might already exist"
CREATE DATABASE testdb WITH LOG;
EOF

# Create sample tables
echo "Creating sample tables..."
dbaccess testdb - <<EOF
-- Customer table
CREATE TABLE IF NOT EXISTS customer (
    customer_id SERIAL PRIMARY KEY,
    first_name VARCHAR(50) NOT NULL,
    last_name VARCHAR(50) NOT NULL,
    email VARCHAR(100),
    company VARCHAR(100),
    phone VARCHAR(20),
    created_at DATETIME YEAR TO SECOND DEFAULT CURRENT YEAR TO SECOND,
    updated_at DATETIME YEAR TO SECOND
);

-- Orders table
CREATE TABLE IF NOT EXISTS orders (
    order_id SERIAL PRIMARY KEY,
    customer_id INTEGER NOT NULL,
    order_date DATE NOT NULL,
    total_amount DECIMAL(10,2),
    status VARCHAR(20) DEFAULT 'pending',
    FOREIGN KEY (customer_id) REFERENCES customer(customer_id)
);

-- Products table
CREATE TABLE IF NOT EXISTS products (
    product_id SERIAL PRIMARY KEY,
    product_name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    price DECIMAL(10,2) NOT NULL,
    stock_quantity INTEGER DEFAULT 0,
    category VARCHAR(50)
);

-- Order Items table
CREATE TABLE IF NOT EXISTS order_items (
    item_id SERIAL PRIMARY KEY,
    order_id INTEGER NOT NULL,
    product_id INTEGER NOT NULL,
    quantity INTEGER NOT NULL,
    unit_price DECIMAL(10,2) NOT NULL,
    FOREIGN KEY (order_id) REFERENCES orders(order_id),
    FOREIGN KEY (product_id) REFERENCES products(product_id)
);

-- Create indexes for better query performance
CREATE INDEX IF NOT EXISTS idx_customer_email ON customer(email);
CREATE INDEX IF NOT EXISTS idx_orders_customer ON orders(customer_id);
CREATE INDEX IF NOT EXISTS idx_orders_date ON orders(order_date);
CREATE INDEX IF NOT EXISTS idx_order_items_order ON order_items(order_id);
CREATE INDEX IF NOT EXISTS idx_order_items_product ON order_items(product_id);

EOF

# Insert sample data
echo "Inserting sample data..."
dbaccess testdb - <<EOF
-- Insert customers
INSERT INTO customer (first_name, last_name, email, company, phone) VALUES
    ('John', 'Doe', 'john.doe@example.com', 'Acme Corp', '555-0001'),
    ('Jane', 'Smith', 'jane.smith@example.com', 'TechCo', '555-0002'),
    ('Bob', 'Johnson', 'bob.j@example.com', 'DataSys', '555-0003'),
    ('Alice', 'Williams', 'alice.w@example.com', 'CloudNet', '555-0004'),
    ('Charlie', 'Brown', 'charlie.b@example.com', 'WebDev Inc', '555-0005'),
    ('Diana', 'Davis', 'diana.d@example.com', 'InfoTech', '555-0006'),
    ('Eve', 'Martinez', 'eve.m@example.com', 'SoftCorp', '555-0007'),
    ('Frank', 'Garcia', 'frank.g@example.com', 'NetSolutions', '555-0008'),
    ('Grace', 'Rodriguez', 'grace.r@example.com', 'Digital LLC', '555-0009'),
    ('Henry', 'Wilson', 'henry.w@example.com', 'CodeFactory', '555-0010');

-- Insert products
INSERT INTO products (product_name, description, price, stock_quantity, category) VALUES
    ('Laptop Pro', 'High-performance laptop', 1299.99, 50, 'Electronics'),
    ('Wireless Mouse', 'Ergonomic wireless mouse', 29.99, 200, 'Accessories'),
    ('USB-C Hub', '7-in-1 USB-C hub', 49.99, 150, 'Accessories'),
    ('Monitor 27"', '4K UHD monitor', 399.99, 75, 'Electronics'),
    ('Mechanical Keyboard', 'RGB mechanical keyboard', 89.99, 100, 'Accessories'),
    ('Webcam HD', '1080p webcam', 59.99, 120, 'Electronics'),
    ('Desk Lamp', 'LED desk lamp', 34.99, 80, 'Office'),
    ('Office Chair', 'Ergonomic office chair', 249.99, 30, 'Furniture'),
    ('Standing Desk', 'Adjustable standing desk', 499.99, 20, 'Furniture'),
    ('Headphones', 'Noise-canceling headphones', 199.99, 90, 'Electronics');

-- Insert orders
INSERT INTO orders (customer_id, order_date, total_amount, status) VALUES
    (1, TODAY, 1329.98, 'shipped'),
    (2, TODAY - 1, 449.98, 'delivered'),
    (3, TODAY - 2, 89.99, 'pending'),
    (4, TODAY - 3, 749.97, 'shipped'),
    (5, TODAY - 4, 199.99, 'delivered'),
    (6, TODAY - 5, 1799.97, 'processing'),
    (7, TODAY - 6, 119.98, 'shipped'),
    (8, TODAY - 7, 549.98, 'delivered'),
    (9, TODAY - 8, 299.98, 'cancelled'),
    (10, TODAY - 9, 1549.96, 'shipped');

-- Insert order items
INSERT INTO order_items (order_id, product_id, quantity, unit_price) VALUES
    -- Order 1
    (1, 1, 1, 1299.99),
    (1, 2, 1, 29.99),
    -- Order 2
    (2, 4, 1, 399.99),
    (2, 3, 1, 49.99),
    -- Order 3
    (3, 5, 1, 89.99),
    -- Order 4
    (4, 8, 3, 249.99),
    -- Order 5
    (5, 10, 1, 199.99),
    -- Order 6
    (6, 1, 1, 1299.99),
    (6, 9, 1, 499.99),
    -- Order 7
    (7, 2, 2, 29.99),
    (7, 3, 1, 49.99),
    (7, 7, 1, 34.99),
    -- Order 8
    (8, 4, 1, 399.99),
    (8, 6, 2, 59.99),
    (8, 2, 1, 29.99),
    -- Order 9
    (9, 5, 2, 89.99),
    (9, 6, 2, 59.99),
    -- Order 10
    (10, 1, 1, 1299.99),
    (10, 8, 1, 249.99);

EOF

# Grant permissions
echo "Granting permissions..."
dbaccess testdb - <<EOF
GRANT ALL ON customer TO PUBLIC;
GRANT ALL ON orders TO PUBLIC;
GRANT ALL ON products TO PUBLIC;
GRANT ALL ON order_items TO PUBLIC;
EOF

# Create stored procedures for testing
echo "Creating stored procedures..."
dbaccess testdb - <<EOF
-- Procedure to get customer orders
CREATE PROCEDURE get_customer_orders(cust_id INT)
RETURNING INT AS order_id, DATE AS order_date, DECIMAL AS total_amount, VARCHAR(20) AS status;
    FOREACH SELECT order_id, order_date, total_amount, status
            INTO order_id, order_date, total_amount, status
            FROM orders
            WHERE customer_id = cust_id
            ORDER BY order_date DESC
        RETURN order_id, order_date, total_amount, status WITH RESUME;
    END FOREACH;
END PROCEDURE;

-- Function to calculate order total
CREATE FUNCTION calculate_order_total(ord_id INT)
RETURNING DECIMAL(10,2);
    DEFINE total DECIMAL(10,2);
    SELECT SUM(quantity * unit_price)
    INTO total
    FROM order_items
    WHERE order_id = ord_id;
    RETURN total;
END FUNCTION;

EOF

echo "===================================="
echo "Test database setup complete!"
echo "===================================="
echo ""
echo "Database: testdb"
echo "Tables: customer, orders, products, order_items"
echo "Sample data: 10 customers, 10 products, 10 orders"
echo ""
echo "Test query:"
echo "  dbaccess testdb - <<< 'SELECT COUNT(*) FROM customer;'"
echo ""