package com.flowops.repository;

import com.flowops.domain.UserAccount;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<UserAccount, UUID> {

    Optional<UserAccount> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    @Query("SELECT u.emailVerified FROM UserAccount u WHERE u.id = :userId")
    Optional<Boolean> findEmailVerifiedById(@Param("userId") UUID userId);

    @Modifying
    @Query("UPDATE UserAccount u SET u.emailVerified = true, u.updatedAt = :now WHERE u.id = :userId")
    int markEmailVerified(@Param("userId") UUID userId, @Param("now") Instant now);
}