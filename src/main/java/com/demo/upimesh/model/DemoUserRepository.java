package com.demo.upimesh.model;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface DemoUserRepository extends JpaRepository<DemoUser, Long> {
    Optional<DemoUser> findByContact(String contact);
}
