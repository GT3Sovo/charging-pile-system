package com.chargingpile.repository;

import com.chargingpile.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);

    @Modifying
    @Transactional
    @Query(value = "INSERT INTO users (id, username, password, role, created_at) VALUES (?1, ?2, ?3, ?4, ?5)", nativeQuery = true)
    void insertUserWithId(Long id, String username, String password, String role, LocalDateTime createdAt);
}
