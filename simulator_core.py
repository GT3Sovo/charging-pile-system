import openpyxl
from datetime import datetime, timedelta

# Define constants
F_POWER = 30.0 / 60.0  # 0.5 degrees per minute
T_POWER = 10.0 / 60.0  # 1/6 degrees per minute

# Price periods
# Peak: 10:00~15:00, 18:00~21:00 (1.0 + 0.8 = 1.8)
# Regular: 07:00~10:00, 15:00~18:00, 21:00~23:00 (0.7 + 0.8 = 1.5)
# Off-peak: 23:00~07:00 (0.4 + 0.8 = 1.2)
def get_price_rate(seconds):
    # seconds since midnight
    minutes = seconds / 60.0
    hours = minutes / 60.0
    
    # Let's write rules based on hours
    if (10.0 <= hours < 15.0) or (18.0 <= hours < 21.0):
        return 1.0, 0.8
    elif (7.0 <= hours < 10.0) or (15.0 <= hours < 18.0) or (21.0 <= hours < 23.0):
        return 0.7, 0.8
    else:
        return 0.4, 0.8

class Vehicle:
    def __init__(self, id, mode, requested_q, request_time_str):
        self.id = id
        self.mode = mode  # 'F' or 'T'
        self.requested_q = requested_q
        self.request_time_str = request_time_str
        self.queue_num = None
        
        # State: 'waiting_in_area', 'queuing_in_pile', 'charging', 'finished', 'cancelled'
        self.state = 'waiting_in_area'
        self.pile_id = None
        self.charged_q = 0.0
        self.charge_cost = 0.0
        self.service_cost = 0.0
        self.total_cost = 0.0
        
        # Timing details
        self.start_charge_time = None
        self.finish_time = None
        self.cancelled_time = None
        self.queue_num_history = []
        
        # Record session charging (for bills / multiple sessions due to breakdowns)
        self.sessions = [] # list of (pile_id, start_time, end_time, charged_q, charge_cost, service_cost)

    def current_cost(self):
        return self.charge_cost + self.service_cost

class Pile:
    def __init__(self, id, type, power):
        self.id = id  # '快充1', etc.
        self.type = type  # 'F' or 'T'
        self.power = power
        self.queue = []  # max length 3. queue[0] is charging, queue[1:] are waiting
        self.is_fault = False
        self.fault_end_time = None
        
        # Statistics
        self.charge_count = 0
        self.total_charge_time_seconds = 0
        self.total_charged_q = 0.0
        self.total_charge_cost = 0.0
        self.total_service_cost = 0.0
        self.total_cost = 0.0

    def total_cars(self):
        return len(self.queue)

    def is_full(self):
        return len(self.queue) >= 3

    def get_remaining_charge_time_seconds(self):
        # Calculates sum of remaining charge time for all vehicles in this pile
        total_sec = 0.0
        for i, veh in enumerate(self.queue):
            rem_q = veh.requested_q - veh.charged_q
            if rem_q > 0:
                total_sec += (rem_q / self.power) * 60.0
        return total_sec

    def get_s_value(self):
        # S value: sum of remaining energy of the active vehicle + requested energy of waiting vehicles
        s = 0.0
        for i, veh in enumerate(self.queue):
            rem_q = veh.requested_q - veh.charged_q
            s += rem_q
        return s

# Time converter helper
def time_to_sec(t_str):
    h, m, s = map(int, t_str.split(':'))
    return h * 3600 + m * 60 + s

def sec_to_time(sec):
    h = int(sec // 3600)
    m = int((sec % 3600) // 60)
    s = int(sec % 60)
    return f"{h:02d}:{m:02d}:{s:02d}"

print("Simulator core module defined.")
