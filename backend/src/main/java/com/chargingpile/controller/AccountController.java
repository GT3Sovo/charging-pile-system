package com.chargingpile.controller;

import com.chargingpile.entity.ChargingMode;
import com.chargingpile.entity.Role;
import com.chargingpile.entity.User;
import com.chargingpile.entity.Vehicle;
import com.chargingpile.entity.VehicleState;
import com.chargingpile.repository.BillRepository;
import com.chargingpile.repository.DetailListRepository;
import com.chargingpile.repository.QueueRecordRepository;
import com.chargingpile.repository.RechargeRecordRepository;
import com.chargingpile.repository.UserRepository;
import com.chargingpile.repository.VehicleRepository;
import com.chargingpile.service.SimulationService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AccountController {

    private static final String SESSION_USER_ID = "userId";

    private final UserRepository userRepository;
    private final VehicleRepository vehicleRepository;
    private final QueueRecordRepository queueRecordRepository;
    private final RechargeRecordRepository rechargeRecordRepository;
    private final DetailListRepository detailListRepository;
    private final BillRepository billRepository;
    private final PasswordEncoder passwordEncoder;
    private final SimulationService simulationService;

    public AccountController(UserRepository userRepository,
                             VehicleRepository vehicleRepository,
                             QueueRecordRepository queueRecordRepository,
                             RechargeRecordRepository rechargeRecordRepository,
                             DetailListRepository detailListRepository,
                             BillRepository billRepository,
                             PasswordEncoder passwordEncoder,
                             SimulationService simulationService) {
        this.userRepository = userRepository;
        this.vehicleRepository = vehicleRepository;
        this.queueRecordRepository = queueRecordRepository;
        this.rechargeRecordRepository = rechargeRecordRepository;
        this.detailListRepository = detailListRepository;
        this.billRepository = billRepository;
        this.passwordEncoder = passwordEncoder;
        this.simulationService = simulationService;
    }

    public static class AuthDto {
        public String username;
        public String password;
    }

    public static class VehicleProfileDto {
        public String vehicleId;
        public String vehicleType;
        public Double batteryCapacity;
        public Double currentCapacity;
    }

    public static class RoleDto {
        public Role role;
    }

    @PostMapping("/auth/register")
    @Transactional
    public ResponseEntity<Map<String, Object>> register(@RequestBody AuthDto dto, HttpSession session) {
        simulationService.ensureInitialized();
        String username = normalizeUsername(dto.username);
        String password = dto.password == null ? "" : dto.password;
        if (username.length() < 3 || username.length() > 50) {
            return error(HttpStatus.BAD_REQUEST, "用户名长度需为 3-50 个字符");
        }
        if (password.length() < 6 || password.length() > 72) {
            return error(HttpStatus.BAD_REQUEST, "密码长度需为 6-72 个字符");
        }
        if (userRepository.existsByUsername(username)) {
            return error(HttpStatus.CONFLICT, "用户名已存在");
        }

        User user = userRepository.save(User.builder()
                .username(username)
                .password(passwordEncoder.encode(password))
                .role(Role.CUSTOMER)
                .createdAt(LocalDateTime.now())
                .build());
        session.setAttribute(SESSION_USER_ID, user.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(successUser(user));
    }

    @PostMapping("/auth/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody AuthDto dto, HttpSession session) {
        simulationService.ensureInitialized();
        String username = normalizeUsername(dto.username);
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null || dto.password == null || !passwordEncoder.matches(dto.password, user.getPassword())) {
            return error(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        session.setAttribute(SESSION_USER_ID, user.getId());
        return ResponseEntity.ok(successUser(user));
    }

    @PostMapping("/auth/logout")
    public Map<String, Object> logout(HttpSession session) {
        session.invalidate();
        return Map.of("status", "SUCCESS");
    }

    @GetMapping("/auth/me")
    public Map<String, Object> me(HttpSession session) {
        User user = currentUser(session);
        if (user == null) {
            return Map.of("authenticated", false);
        }
        Map<String, Object> response = successUser(user);
        response.put("authenticated", true);
        return response;
    }

    @GetMapping("/account/vehicles")
    public ResponseEntity<?> myVehicles(HttpSession session) {
        User user = currentUser(session);
        if (user == null) {
            return error(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return ResponseEntity.ok(vehicleRepository.findByUserIdOrderByIdAsc(user.getId()).stream()
                .map(this::safeVehicle)
                .toList());
    }

    @PostMapping("/account/vehicles")
    @Transactional
    public ResponseEntity<?> addVehicle(@RequestBody VehicleProfileDto dto, HttpSession session) {
        User user = currentUser(session);
        if (user == null) {
            return error(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        String vehicleId = normalizeVehicleId(dto.vehicleId);
        String validation = validateVehicleProfile(vehicleId, dto);
        if (validation != null) {
            return error(HttpStatus.BAD_REQUEST, validation);
        }
        if (vehicleRepository.existsById(vehicleId)) {
            return error(HttpStatus.CONFLICT, "该车牌已存在");
        }

        simulationService.ensureInitialized();
        Vehicle vehicle = vehicleRepository.save(Vehicle.builder()
                .id(vehicleId)
                .user(user)
                .vehicleType(dto.vehicleType.trim())
                .batteryCapacity(dto.batteryCapacity)
                .currentCapacity(dto.currentCapacity)
                .chargeMode(ChargingMode.TRICKLE)
                .requestedAmount(0.0)
                .requestTime(simulationService.getCurrentDateTime())
                .state(VehicleState.CANCELLED)
                .build());
        return ResponseEntity.status(HttpStatus.CREATED).body(safeVehicle(vehicle));
    }

    @PutMapping("/account/vehicles/{vehicleId}")
    @Transactional
    public ResponseEntity<?> updateVehicle(@PathVariable String vehicleId,
                                           @RequestBody VehicleProfileDto dto,
                                           HttpSession session) {
        User user = currentUser(session);
        if (user == null) {
            return error(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        Vehicle vehicle = vehicleRepository.findById(vehicleId).orElse(null);
        if (vehicle == null) {
            return error(HttpStatus.NOT_FOUND, "车辆不存在");
        }
        if (!vehicle.getUser().getId().equals(user.getId()) && user.getRole() != Role.ADMIN) {
            return error(HttpStatus.FORBIDDEN, "无权修改该车辆");
        }
        if (isActive(vehicle)) {
            return error(HttpStatus.CONFLICT, "充电流程进行中，不能修改车辆档案");
        }
        String validation = validateVehicleProfile(vehicleId, dto);
        if (validation != null) {
            return error(HttpStatus.BAD_REQUEST, validation);
        }
        vehicle.setVehicleType(dto.vehicleType.trim());
        vehicle.setBatteryCapacity(dto.batteryCapacity);
        vehicle.setCurrentCapacity(dto.currentCapacity);
        return ResponseEntity.ok(safeVehicle(vehicleRepository.save(vehicle)));
    }

    @DeleteMapping("/account/vehicles/{vehicleId}")
    @Transactional
    public ResponseEntity<?> deleteVehicle(@PathVariable String vehicleId, HttpSession session) {
        User user = currentUser(session);
        if (user == null) {
            return error(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        Vehicle vehicle = vehicleRepository.findById(vehicleId).orElse(null);
        if (vehicle == null) {
            return error(HttpStatus.NOT_FOUND, "车辆不存在");
        }
        if (!vehicle.getUser().getId().equals(user.getId()) && user.getRole() != Role.ADMIN) {
            return error(HttpStatus.FORBIDDEN, "无权删除该车辆");
        }
        if (isActive(vehicle)
                || !rechargeRecordRepository.findByVehicleId(vehicleId).isEmpty()
                || !detailListRepository.findByVehicleId(vehicleId).isEmpty()
                || !billRepository.findByVehicleId(vehicleId).isEmpty()) {
            return error(HttpStatus.CONFLICT, "该车辆已有排队或交易记录，不能删除");
        }
        queueRecordRepository.deleteByVehicleId(vehicleId);
        vehicleRepository.delete(vehicle);
        return ResponseEntity.ok(Map.of("status", "SUCCESS"));
    }

    @GetMapping("/admin/accounts")
    public ResponseEntity<?> accounts(HttpSession session) {
        User user = currentUser(session);
        if (user == null || (user.getRole() != Role.ADMIN && user.getRole() != Role.MANAGER)) {
            return error(HttpStatus.FORBIDDEN, "仅管理员或经理可查看用户资料");
        }
        List<Map<String, Object>> users = userRepository.findAll().stream().map(this::safeUser).toList();
        List<Map<String, Object>> vehicles = vehicleRepository.findAll().stream().map(this::safeVehicle).toList();
        return ResponseEntity.ok(Map.of("users", users, "vehicles", vehicles));
    }

    @PutMapping("/admin/users/{userId}/role")
    @Transactional
    public ResponseEntity<?> updateRole(@PathVariable Long userId,
                                        @RequestBody RoleDto dto,
                                        HttpSession session) {
        User operator = currentUser(session);
        if (operator == null || operator.getRole() != Role.ADMIN) {
            return error(HttpStatus.FORBIDDEN, "仅管理员可修改角色");
        }
        if (dto.role == null) {
            return error(HttpStatus.BAD_REQUEST, "角色不能为空");
        }
        User target = userRepository.findById(userId).orElse(null);
        if (target == null) {
            return error(HttpStatus.NOT_FOUND, "用户不存在");
        }
        target.setRole(dto.role);
        return ResponseEntity.ok(safeUser(userRepository.save(target)));
    }

    public Long currentUserId(HttpSession session) {
        User user = currentUser(session);
        return user == null ? null : user.getId();
    }

    private User currentUser(HttpSession session) {
        simulationService.ensureInitialized();
        Object value = session.getAttribute(SESSION_USER_ID);
        if (!(value instanceof Long userId)) {
            return null;
        }
        return userRepository.findById(userId).orElse(null);
    }

    private String normalizeUsername(String username) {
        return username == null ? "" : username.trim();
    }

    private String normalizeVehicleId(String vehicleId) {
        return vehicleId == null ? "" : vehicleId.trim().toUpperCase();
    }

    private String validateVehicleProfile(String vehicleId, VehicleProfileDto dto) {
        if (vehicleId.isBlank() || vehicleId.length() > 20) {
            return "车牌号不能为空且不能超过 20 个字符";
        }
        if (dto.vehicleType == null || dto.vehicleType.isBlank() || dto.vehicleType.trim().length() > 50) {
            return "车辆型号不能为空且不能超过 50 个字符";
        }
        if (dto.batteryCapacity == null || dto.batteryCapacity <= 0) {
            return "电池容量必须大于 0";
        }
        if (dto.currentCapacity == null || dto.currentCapacity < 0 || dto.currentCapacity > dto.batteryCapacity) {
            return "当前电量必须位于 0 与电池容量之间";
        }
        return null;
    }

    private boolean isActive(Vehicle vehicle) {
        return vehicle.getState() == VehicleState.WAITING_IN_AREA
                || vehicle.getState() == VehicleState.QUEUING_IN_PILE
                || vehicle.getState() == VehicleState.CHARGING;
    }

    private Map<String, Object> successUser(User user) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "SUCCESS");
        response.put("user", safeUser(user));
        return response;
    }

    private Map<String, Object> safeUser(User user) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", user.getId());
        result.put("username", user.getUsername());
        result.put("role", user.getRole());
        result.put("createdAt", user.getCreatedAt());
        return result;
    }

    private Map<String, Object> safeVehicle(Vehicle vehicle) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", vehicle.getId());
        result.put("userId", vehicle.getUser().getId());
        result.put("username", vehicle.getUser().getUsername());
        result.put("vehicleType", vehicle.getVehicleType());
        result.put("batteryCapacity", vehicle.getBatteryCapacity());
        result.put("currentCapacity", vehicle.getCurrentCapacity());
        result.put("chargeMode", vehicle.getChargeMode());
        result.put("requestedAmount", vehicle.getRequestedAmount());
        result.put("queueNum", vehicle.getQueueNum());
        result.put("state", vehicle.getState());
        return result;
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("status", "ERROR", "message", message));
    }
}
