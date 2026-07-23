-- Create users table
CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL,
    created_at DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Create charging_piles table
CREATE TABLE IF NOT EXISTS charging_piles (
    id VARCHAR(20) PRIMARY KEY,
    type VARCHAR(10) NOT NULL,
    power DOUBLE NOT NULL,
    state VARCHAR(10) NOT NULL,
    charge_count INT NOT NULL DEFAULT 0,
    total_charge_time BIGINT NOT NULL DEFAULT 0,
    total_charge_amount DOUBLE NOT NULL DEFAULT 0.0,
    total_charge_cost DOUBLE NOT NULL DEFAULT 0.0,
    total_service_cost DOUBLE NOT NULL DEFAULT 0.0,
    total_cost DOUBLE NOT NULL DEFAULT 0.0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Create vehicles table
CREATE TABLE IF NOT EXISTS vehicles (
    id VARCHAR(20) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    vehicle_type VARCHAR(50) NOT NULL,
    battery_capacity DOUBLE NOT NULL,
    current_capacity DOUBLE NOT NULL,
    charge_mode VARCHAR(10) NOT NULL,
    requested_amount DOUBLE NOT NULL,
    request_time DATETIME NOT NULL,
    queue_num VARCHAR(10),
    state VARCHAR(20) NOT NULL,
    pile_id VARCHAR(20),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (pile_id) REFERENCES charging_piles(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Create queue_records table
CREATE TABLE IF NOT EXISTS queue_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    vehicle_id VARCHAR(20) NOT NULL,
    pile_id VARCHAR(20),
    queue_type VARCHAR(20) NOT NULL,
    queue_num VARCHAR(10) NOT NULL,
    position INT NOT NULL,
    entry_time DATETIME NOT NULL,
    FOREIGN KEY (vehicle_id) REFERENCES vehicles(id) ON DELETE CASCADE,
    FOREIGN KEY (pile_id) REFERENCES charging_piles(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Create recharge_records table
CREATE TABLE IF NOT EXISTS recharge_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    vehicle_id VARCHAR(20) NOT NULL,
    pile_id VARCHAR(20) NOT NULL,
    start_time DATETIME NOT NULL,
    end_time DATETIME,
    charged_amount DOUBLE NOT NULL,
    charge_cost DOUBLE NOT NULL,
    service_cost DOUBLE NOT NULL,
    total_cost DOUBLE NOT NULL,
    FOREIGN KEY (vehicle_id) REFERENCES vehicles(id) ON DELETE CASCADE,
    FOREIGN KEY (pile_id) REFERENCES charging_piles(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Create bills table
CREATE TABLE IF NOT EXISTS bills (
    bill_no VARCHAR(50) PRIMARY KEY,
    vehicle_id VARCHAR(20) NOT NULL,
    station_name VARCHAR(100) NOT NULL,
    created_at DATETIME NOT NULL,
    charged_amount DOUBLE NOT NULL,
    charge_cost DOUBLE NOT NULL,
    service_cost DOUBLE NOT NULL,
    parking_fee DOUBLE NOT NULL,
    total_cost DOUBLE NOT NULL,
    is_paid BOOLEAN NOT NULL DEFAULT FALSE,
    FOREIGN KEY (vehicle_id) REFERENCES vehicles(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Create detail_lists table
CREATE TABLE IF NOT EXISTS detail_lists (
    detail_no VARCHAR(50) PRIMARY KEY,
    created_at DATETIME NOT NULL,
    pile_id VARCHAR(20) NOT NULL,
    vehicle_id VARCHAR(20) NOT NULL,
    bill_no VARCHAR(50),
    charged_amount DOUBLE NOT NULL,
    charge_duration BIGINT NOT NULL,
    start_time DATETIME NOT NULL,
    end_time DATETIME NOT NULL,
    charge_cost DOUBLE NOT NULL,
    service_cost DOUBLE NOT NULL,
    total_cost DOUBLE NOT NULL,
    FOREIGN KEY (pile_id) REFERENCES charging_piles(id) ON DELETE CASCADE,
    FOREIGN KEY (vehicle_id) REFERENCES vehicles(id) ON DELETE CASCADE,
    FOREIGN KEY (bill_no) REFERENCES bills(bill_no) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
