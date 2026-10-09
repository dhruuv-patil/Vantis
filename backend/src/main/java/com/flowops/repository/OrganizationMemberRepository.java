package com.flowops.repository;

import com.flowops.domain.OrganizationMember;
import com.flowops.domain.Role;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Every method here is org-scoped by parameter (contract §2 rule 6): the
 * organization id is always passed in, derived from the authenticated principal,
 * never fetched and then filtered in memory.
 */
public interface OrganizationMemberRepository
        extends JpaRepository<OrganizationMember, UUID> {

    /**
     * The membership backing an authorization decision. Both ids come from the
     * principal — or, for the switch endpoint, from the principal plus a selector
     * that this very lookup validates — never from a bare {@code findById}.
     */
    Optional<OrganizationMember> findByUserIdAndOrganizationId(UUID userId, UUID organizationId);

    /** Members of one organization, sorted {@code joinedAt ASC, userId ASC}. */
    List<OrganizationMember> findByOrganizationIdOrderByJoinedAtAscUserIdAsc(UUID organizationId);

    long countByOrganizationId(UUID organizationId);

    /**
     * How many members hold a given role in an org. Used to protect the last
     * {@code OWNER} from being demoted or removed (team management).
     */
    long countByOrganizationIdAndRole(UUID organizationId, Role role);

    /**
     * Finds the user id of the owner of an organization for notification and alert routing.
     */
    @Query("""
            SELECT m.userId FROM OrganizationMember m
            WHERE m.organizationId = :organizationId AND m.role = com.flowops.domain.Role.OWNER
            ORDER BY m.joinedAt ASC
            """)
    List<UUID> findOwnerUserIds(@Param("organizationId") UUID organizationId);

    default Optional<UUID> findOwnerUserId(UUID organizationId) {
        List<UUID> owners = findOwnerUserIds(organizationId);
        return owners.isEmpty() ? Optional.empty() : Optional.of(owners.get(0));
    }

    /**
     * The earliest-joined membership: the current org on login, and the fallback
     * when a session's org is no longer one the user belongs to (contract §2.2).
     * The {@code organizationId} tiebreaker makes it deterministic.
     */
    Optional<OrganizationMember> findFirstByUserIdOrderByJoinedAtAscOrganizationIdAsc(UUID userId);

    /**
     * Membership rows joined to {@code organizations} for the switcher payload,
     * sorted {@code joinedAt ASC, organizationId ASC}.
     */
    @Query("""
            SELECT new com.flowops.repository.MembershipRow(
                m.organizationId, o.name, o.slug, m.role, m.joinedAt)
            FROM OrganizationMember m
            JOIN Organization o ON o.id = m.organizationId
            WHERE m.userId = :userId
            ORDER BY m.joinedAt ASC, m.organizationId ASC
            """)
    List<MembershipRow> findMembershipRows(@Param("userId") UUID userId);

    /**
     * Members of one organization joined to {@code users} for the member-list
     * payload, sorted {@code joinedAt ASC, userId ASC} (contract §5.11).
     */
    @Query("""
            SELECT new com.flowops.repository.OrganizationMemberRow(
                u.id, u.email, u.fullName, u.avatarUrl, m.role, m.joinedAt)
            FROM OrganizationMember m
            JOIN UserAccount u ON u.id = m.userId
            WHERE m.organizationId = :organizationId
            ORDER BY m.joinedAt ASC, u.id ASC
            """)
    List<OrganizationMemberRow> findMemberRows(@Param("organizationId") UUID organizationId);
}
