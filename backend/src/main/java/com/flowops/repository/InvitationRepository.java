package com.flowops.repository;

import com.flowops.domain.Invitation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Org-scoped by parameter for management reads (contract §2 rule 6). The single
 * lookup that is <em>not</em> org-scoped — {@link #findByTokenHash} — is the accept
 * path, which has only a token; the org is then read from the returned row and the
 * accepting user's email is matched against it, never trusted from input.
 */
public interface InvitationRepository extends JpaRepository<Invitation, UUID> {

    List<Invitation> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    Optional<Invitation> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<Invitation> findByOrganizationIdAndEmailIgnoreCaseAndStatus(
            UUID organizationId, String email, Invitation.Status status);

    /** Count active/unexpired pending invitations for entitlement team limit checks. */
    long countByOrganizationIdAndStatusAndExpiresAtAfter(
            UUID organizationId, Invitation.Status status, java.time.Instant expiresAt);

    /** Accept path only: no principal exists yet for the target org. */
    Optional<Invitation> findByTokenHash(String tokenHash);
}
