package com.flowops.repository;

import com.flowops.domain.Subscription;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    Optional<Subscription> findByWorkspaceId(UUID workspaceId);

    boolean existsByWorkspaceId(UUID workspaceId);
}
