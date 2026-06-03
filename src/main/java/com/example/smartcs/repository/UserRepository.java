package com.example.smartcs.repository;

import com.example.smartcs.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    List<User> findByRole(String role);
    List<User> findByStatus(Integer status);
    List<User> findByUsernameContaining(String keyword);
}
