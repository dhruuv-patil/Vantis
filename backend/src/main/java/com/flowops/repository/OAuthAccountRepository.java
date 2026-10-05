package com.flowops.repository;

import com.flowops.domain.OAuthAccount;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OAuthAccountRepository extends JpaRepository<OAuthAccount, UUID> {

    Optional<OAuthAccount> findByProviderAndProviderUserId(String provider, String providerUserId);

    Optional<OAuthAccount> findByUserIdAndProvider(UUID userId, String provider);

    List<OAuthAccount> findAllByUserId(UUID userId);

    boolean existsByProviderAndProviderUserId(String provider, String providerUserId);
}
