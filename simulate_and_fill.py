import openpyxl
from datetime import datetime, timedelta

# Define constants
# Powers in degrees per second
F_POWER = 30.0 / 3600.0  # 30 degrees/hour = 30/3600 degrees/second
T_POWER = 10.0 / 3600.0  # 10 degrees/hour = 10/3600 degrees/second

# Price periods
def get_price_rate(seconds):
    hours = seconds / 3600.0
    
    # Peak: 10:00~15:00, 18:00~21:00
    if (10.0 <= hours < 15.0) or (18.0 <= hours < 21.0):
        return 1.0, 0.8
    # Regular: 07:00~10:00, 15:00~18:00, 21:00~23:00
    elif (7.0 <= hours < 10.0) or (15.0 <= hours < 18.0) or (21.0 <= hours < 23.0):
        return 0.7, 0.8
    # Off-peak: 23:00~次日7:00
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

    def current_cost(self):
        return self.charge_cost + self.service_cost

class Pile:
    def __init__(self, id, type, power):
        self.id = id  # '快充1', etc.
        self.type = type  # 'F' or 'T'
        self.power = power  # deg per second
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

def run_simulation():
    # Initialize piles
    piles = {
        '快充1': Pile('快充1', 'F', F_POWER),
        '快充2': Pile('快充2', 'F', F_POWER),
        '快充3': Pile('快充3', 'F', F_POWER),
        '慢充1': Pile('慢充1', 'T', T_POWER),
        '慢充2': Pile('慢充2', 'T', T_POWER)
    }
    
    # Waiting areas
    waiting_area = {'F': [], 'T': []} # list of Vehicles
    
    # Trackers for queue numbering
    last_queue_num = {'F': 0, 'T': 0}
    
    # Vehicles dict
    vehicles = {}
    
    # Freeze call-out flags
    waiting_area_frozen = {'F': False, 'T': False}
    
    # Fault dispatch queues
    fault_dispatch_queue = {'F': [], 'T': []}
    
    # Event list from Excel
    events = [
        ("06:00:00", 'A', 'V1', 'T', 40.0),
        ("06:05:00", 'A', 'V2', 'T', 30.0),
        ("06:10:00", 'A', 'V3', 'F', 60.0),
        ("06:20:00", 'A', 'V2', 'O', 0.0), # cancels V2
        ("06:25:00", 'A', 'V4', 'T', 20.0),
        ("06:30:00", 'A', 'V5', 'T', 20.0),
        ("06:40:00", 'A', 'V6', 'T', 20.0),
        ("06:50:00", 'A', 'V7', 'T', 10.0),
        ("07:00:00", 'A', 'V8', 'F', 90.0),
        ("07:10:00", 'A', 'V9', 'F', 30.0),
        ("07:15:00", 'A', 'V10', 'T', 10.0),
        ("07:20:00", 'A', 'V11', 'F', 60.0),
        ("07:25:00", 'A', 'V12', 'T', 10.0),
        ("07:30:00", 'A', 'V13', 'T', 7.5),
        ("07:35:00", 'A', 'V14', 'F', 75.0),
        ("07:40:00", 'A', 'V15', 'F', 45.0),
        ("08:00:00", 'A', 'V16', 'T', 5.0),
        ("08:20:00", 'A', 'V17', 'T', 15.0),
        ("08:30:00", 'A', 'V18', 'T', 20.0),
        ("08:35:00", 'A', 'V19', 'T', 25.0),
        ("09:00:00", 'A', 'V20', 'F', 30.0),
        ("09:10:00", 'A', 'V7', 'O', 0.0), # cancels V7
        ("09:20:00", 'A', 'V11', 'O', 0.0), # cancels V11
        ("09:30:00", 'A', 'V18', 'O', 0.0), # cancels V18
        ("09:35:00", 'A', 'V20', 'O', 0.0), # cancels V20
        ("09:50:00", 'A', 'V21', 'F', 30.0),
        ("10:00:00", 'A', 'V22', 'T', 10.0),
        ("10:05:00", 'C', 'V19', 'F', 25.0), # V19 change to F, 25
        ("10:10:00", 'C', 'V21', 'F', 10.0), # V21 change to 10
        ("10:20:00", 'C', 'V22', 'F', 10.0), # V22 change to F, 10
        ("10:30:00", 'B', 'T1', 'O', 60.0), # T1 breakdown 60 mins
        ("10:50:00", 'B', 'F1', 'O', 120.0), # F1 breakdown 120 mins
    ]
    
    events.sort(key=lambda x: time_to_sec(x[0]))
    
    start_time = time_to_sec("06:00:00")
    end_time = time_to_sec("11:00:00")
    
    snapshots = {}
    
    def try_dispatch(mode):
        while fault_dispatch_queue[mode]:
            avail_piles = [p for p in piles.values() if p.type == mode and not p.is_fault and not p.is_full()]
            if not avail_piles:
                waiting_area_frozen[mode] = True
                break
            
            veh = fault_dispatch_queue[mode].pop(0)
            best_pile = min(avail_piles, key=lambda p: (p.get_s_value(), p.id))
            
            best_pile.queue.append(veh)
            veh.pile_id = best_pile.id
            veh.state = 'queuing_in_pile'
            print(f"[{sec_to_time(current_sec)}] Fault Dispatch: {veh.id} dispatched to {best_pile.id}")
            
            if len(best_pile.queue) == 1:
                veh.state = 'charging'
                veh.start_charge_time = current_sec
                print(f"[{sec_to_time(current_sec)}] Fault Dispatch: {veh.id} starts charging on {best_pile.id}")
        
        if not fault_dispatch_queue[mode]:
            waiting_area_frozen[mode] = False
            
            while waiting_area[mode]:
                avail_piles = [p for p in piles.values() if p.type == mode and not p.is_fault and not p.is_full()]
                if not avail_piles:
                    break
                
                veh = waiting_area[mode].pop(0)
                best_pile = min(avail_piles, key=lambda p: (p.get_s_value(), p.id))
                
                best_pile.queue.append(veh)
                veh.pile_id = best_pile.id
                veh.state = 'queuing_in_pile'
                print(f"[{sec_to_time(current_sec)}] Dispatch: {veh.id} (Queue No: {veh.queue_num}) dispatched to {best_pile.id}")
                
                if len(best_pile.queue) == 1:
                    veh.state = 'charging'
                    veh.start_charge_time = current_sec
                    print(f"[{sec_to_time(current_sec)}] Dispatch: {veh.id} starts charging on {best_pile.id}")
    
    current_sec = start_time
    event_idx = 0
    
    while current_sec <= end_time:
        # 1. Process breakdowns (recovery is ignored as they end after 11:00:00)
        
        # 2. Charging progress for 1 second
        for pile in piles.values():
            if not pile.is_fault and pile.queue:
                veh = pile.queue[0]
                if veh.state != 'charging':
                    veh.state = 'charging'
                    veh.start_charge_time = current_sec
                    
                delta_q = pile.power  # power is per second
                rem_q = veh.requested_q - veh.charged_q
                if delta_q > rem_q:
                    delta_q = rem_q
                
                if delta_q > 0:
                    elec_price, serv_price = get_price_rate(current_sec)
                    cost_elec = delta_q * elec_price
                    cost_serv = delta_q * serv_price
                    
                    veh.charged_q += delta_q
                    veh.charge_cost += cost_elec
                    veh.service_cost += cost_serv
                    veh.total_cost += cost_elec + cost_serv
                    
                    pile.total_charged_q += delta_q
                    pile.total_charge_cost += cost_elec
                    pile.total_service_cost += cost_serv
                    pile.total_cost += cost_elec + cost_serv
                
                # Check if charging is complete
                if veh.charged_q >= veh.requested_q - 1e-9:
                    veh.state = 'finished'
                    veh.finish_time = current_sec
                    print(f"[{sec_to_time(current_sec)}] Finished: {veh.id} finished charging on {pile.id}. Charged: {veh.charged_q:.2f} deg, Cost: {veh.total_cost:.2f}元")
                    
                    pile.charge_count += 1
                    if veh.start_charge_time is not None:
                        pile.total_charge_time_seconds += (current_sec - veh.start_charge_time)
                    
                    pile.queue.pop(0)
                    
                    if pile.queue:
                        next_veh = pile.queue[0]
                        next_veh.state = 'charging'
                        next_veh.start_charge_time = current_sec
                        print(f"[{sec_to_time(current_sec)}] Next Car: {next_veh.id} starts charging on {pile.id}")
                    
                    try_dispatch(pile.type)

        # 3. Process events at this second
        while event_idx < len(events) and time_to_sec(events[event_idx][0]) == current_sec:
            evt_time_str, evt_type, id_val, mode_val, val = events[event_idx]
            print(f"\n>>> [{evt_time_str}] Event: ({evt_type}, {id_val}, {mode_val}, {val})")
            
            if evt_type == 'A':
                if val == 0.0:
                    veh_id = id_val
                    if veh_id in vehicles:
                        veh = vehicles[veh_id]
                        if veh.state == 'waiting_in_area':
                            waiting_area[veh.mode].remove(veh)
                            veh.state = 'cancelled'
                            veh.cancelled_time = current_sec
                            print(f"[{evt_time_str}] Cancel: {veh.id} cancelled in waiting area")
                        elif veh.state in ['queuing_in_pile', 'charging']:
                            pile_obj = piles[veh.pile_id]
                            is_charging = (pile_obj.queue[0] == veh)
                            pile_obj.queue.remove(veh)
                            veh.state = 'cancelled'
                            veh.cancelled_time = current_sec
                            print(f"[{evt_time_str}] Cancel: {veh.id} cancelled in {pile_obj.id}")
                            
                            if is_charging:
                                pile_obj.charge_count += 1
                                if veh.start_charge_time is not None:
                                    pile_obj.total_charge_time_seconds += (current_sec - veh.start_charge_time)
                                if pile_obj.queue:
                                    next_veh = pile_obj.queue[0]
                                    next_veh.state = 'charging'
                                    next_veh.start_charge_time = current_sec
                                    print(f"[{evt_time_str}] Next Car: {next_veh.id} starts charging on {pile_obj.id}")
                            
                            try_dispatch(pile_obj.type)
                else:
                    veh_id = id_val
                    mode = mode_val
                    req_q = val
                    
                    last_queue_num[mode] += 1
                    q_num = f"{mode}{last_queue_num[mode]}"
                    
                    veh = Vehicle(veh_id, mode, req_q, evt_time_str)
                    veh.queue_num = q_num
                    veh.queue_num_history.append(q_num)
                    vehicles[veh_id] = veh
                    
                    waiting_area[mode].append(veh)
                    print(f"[{evt_time_str}] Request: {veh.id} entered waiting area. Queue No: {q_num}, Req: {req_q}deg")
                    
                    try_dispatch(mode)
                    
            elif evt_type == 'C':
                veh_id = id_val
                new_mode = mode_val
                new_val = val
                
                if veh_id in vehicles:
                    veh = vehicles[veh_id]
                    if veh.state == 'waiting_in_area':
                        old_mode = veh.mode
                        if new_mode != 'O' and new_mode != old_mode:
                            waiting_area[old_mode].remove(veh)
                            veh.mode = new_mode
                            last_queue_num[new_mode] += 1
                            new_q_num = f"{new_mode}{last_queue_num[new_mode]}"
                            veh.queue_num = new_q_num
                            veh.queue_num_history.append(new_q_num)
                            
                            if new_val != -1:
                                veh.requested_q = new_val
                            
                            waiting_area[new_mode].append(veh)
                            print(f"[{evt_time_str}] Change (Waiting): {veh.id} changed mode to {new_mode}, new Queue No: {new_q_num}, Req: {veh.requested_q}deg")
                            
                            try_dispatch(old_mode)
                            try_dispatch(new_mode)
                        else:
                            if new_val != -1:
                                veh.requested_q = new_val
                            print(f"[{evt_time_str}] Change (Waiting): {veh.id} changed request to {veh.requested_q}deg")
                            try_dispatch(veh.mode)
                    elif veh.state in ['queuing_in_pile', 'charging']:
                        print(f"[{evt_time_str}] Change (In-Pile): {veh.id} is in {veh.state} on {veh.pile_id}. Update requested_q to {new_val}.")
                        if new_val != -1:
                            veh.requested_q = new_val
                        if veh.state == 'charging' and veh.charged_q >= veh.requested_q - 1e-9:
                            veh.state = 'finished'
                            veh.finish_time = current_sec
                            print(f"[{sec_to_time(current_sec)}] Finished (via Change): {veh.id} finished charging on {veh.pile_id}. Charged: {veh.charged_q:.2f} deg, Cost: {veh.total_cost:.2f}元")
                            
                            pile_obj = piles[veh.pile_id]
                            pile_obj.charge_count += 1
                            if veh.start_charge_time is not None:
                                pile_obj.total_charge_time_seconds += (current_sec - veh.start_charge_time)
                            
                            pile_obj.queue.pop(0)
                            if pile_obj.queue:
                                next_veh = pile_obj.queue[0]
                                next_veh.state = 'charging'
                                next_veh.start_charge_time = current_sec
                                print(f"[{sec_to_time(current_sec)}] Next Car: {next_veh.id} starts charging on {pile_obj.id}")
                            
                            try_dispatch(pile_obj.type)
                            
            elif evt_type == 'B':
                pile_id = id_val
                mapped_pile_id = None
                if pile_id.startswith('T'):
                    mapped_pile_id = f"慢充{pile_id[1:]}"
                elif pile_id.startswith('F'):
                    mapped_pile_id = f"快充{pile_id[1:]}"
                
                duration = val
                fault_end_sec = current_sec + int(duration * 60)
                
                pile_obj = piles[mapped_pile_id]
                pile_obj.is_fault = True
                pile_obj.fault_end_time = fault_end_sec
                print(f"[{evt_time_str}] Breakdown: Pile {mapped_pile_id} is broken for {duration} mins (until {sec_to_time(fault_end_sec)})")
                
                mode = pile_obj.type
                evicted_cars = list(pile_obj.queue)
                pile_obj.queue = []
                
                if evicted_cars:
                    active_car = evicted_cars[0]
                    print(f"[{evt_time_str}] Breakdown Eviction: Active car {active_car.id} stopped on {pile_obj.id}. Already charged: {active_car.charged_q:.2f} deg")
                    pile_obj.charge_count += 1
                    if active_car.start_charge_time is not None:
                        pile_obj.total_charge_time_seconds += (current_sec - active_car.start_charge_time)
                    
                    for veh in evicted_cars:
                        veh.state = 'queuing_in_pile'
                        fault_dispatch_queue[mode].append(veh)
                    
                    waiting_area_frozen[mode] = True
                    print(f"[{evt_time_str}] Waiting area call-out for {mode} is frozen. Evicted cars to dispatch: {[v.id for v in evicted_cars]}")
                    
                    try_dispatch(mode)
            
            event_idx += 1
        
        # Snapshot taking
        event_times = [
            "06:00:00", "06:05:00", "06:10:00", "06:20:00", "06:25:00", "06:30:00", "06:40:00", "06:50:00",
            "07:00:00", "07:10:00", "07:15:00", "07:20:00", "07:25:00", "07:30:00", "07:35:00", "07:40:00",
            "08:00:00", "08:20:00", "08:30:00", "08:35:00", "09:00:00", "09:10:00", "09:20:00", "09:30:00",
            "09:35:00", "09:50:00", "10:00:00", "10:05:00", "10:10:00", "10:20:00", "10:30:00", "10:50:00"
        ]
        
        current_time_str = sec_to_time(current_sec)
        if current_time_str in event_times:
            snap = {}
            for p_id, p_obj in piles.items():
                p_list = []
                for v in p_obj.queue:
                    p_list.append((v.id, v.charged_q, v.total_cost))
                snap[p_id] = p_list
            
            # Fault-dispatch vehicles are physically waiting for re-dispatch and
            # must be shown before normal waiting-area vehicles under strategy A.
            fault_wait_list = []
            for f_veh in fault_dispatch_queue['F']:
                fault_wait_list.append((f_veh.id, 'F', f_veh.requested_q, f_veh.request_time_str))
            for t_veh in fault_dispatch_queue['T']:
                fault_wait_list.append((t_veh.id, 'T', t_veh.requested_q, t_veh.request_time_str))

            normal_wait_list = []
            for f_veh in waiting_area['F']:
                normal_wait_list.append((f_veh.id, 'F', f_veh.requested_q, f_veh.request_time_str))
            for t_veh in waiting_area['T']:
                normal_wait_list.append((t_veh.id, 'T', t_veh.requested_q, t_veh.request_time_str))

            fault_wait_list.sort(key=lambda x: time_to_sec(x[3]))
            normal_wait_list.sort(key=lambda x: time_to_sec(x[3]))
            wait_list = fault_wait_list + normal_wait_list
            snap['waiting_area'] = wait_list
            snapshots[current_time_str] = snap
            
        current_sec += 1

    # End of simulation
    print("\n================ VEHICLES FINAL STATUS ================")
    for v_id, v in sorted(vehicles.items(), key=lambda x: int(x[0][1:])):
        print(f"Vehicle {v.id}: Mode={v.mode}, Req={v.requested_q:.1f}, State={v.state}, Charged={v.charged_q:.2f}, Cost={v.total_cost:.2f} (Elec={v.charge_cost:.2f}, Serv={v.service_cost:.2f})")
        if v.pile_id:
            print(f"   Pile={v.pile_id}")
    
    print("\n================ PILES FINAL STATUS ================")
    for p_id, p in piles.items():
        print(f"Pile {p.id}: Normal={not p.is_fault}, ChargeCount={p.charge_count}, TotalTime={p.total_charge_time_seconds/60.0:.2f} mins, TotalQ={p.total_charged_q:.2f}, TotalCost={p.total_cost:.2f}")

    return snapshots, vehicles, piles

if __name__ == '__main__':
    run_simulation()
