package com.flowops.organization;

import com.flowops.api.AuthResponse;
import com.flowops.api.Envelopes;
import com.flowops.api.InvitationResponse;
import com.flowops.api.InvitationSecretResponse;
import com.flowops.api.MembershipResponse;
import com.flowops.api.OrganizationMemberResponse;
import com.flowops.api.OrganizationResponse;
import com.flowops.api.SessionResponse;
import com.flowops.audit.AuditService;
import com.flowops.auth.AuthResult;
import com.flowops.auth.SessionAssembler;
import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;
import com.flowops.domain.AuditAction;
import com.flowops.domain.AuthSession;
import com.flowops.domain.Invitation;
import com.flowops.domain.Organization;
import com.flowops.domain.OrganizationMember;
import com.flowops.domain.Role;
import com.flowops.domain.UserAccount;
import com.flowops.repository.AuthSessionRepository;
import com.flowops.repository.InvitationRepository;
import com.flowops.repository.OrganizationMemberRepository;
import com.flowops.repository.OrganizationRepository;
import com.flowops.repository.UserRepository;
import com.flowops.security.FlowOpsPrincipal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The six organization endpoints (contract §5.6–§5.11).
 *
 * <p>Every read is scoped by the authenticated principal's {@code organizationId}
 * claim (contract §2). The single method that accepts an org id from the client —
 * {@link #switchOrganization} — validates it against the caller's memberships
 * before use, and returns {@code 404} (never {@code 403}) for a non-member so a
 * caller cannot enumerate other tenants (contract §2.1).
 */
@Service
public class OrganizationService {

    private final OrganizationRepository organizations;
    private final OrganizationMemberRepository members;
    private final AuthSessionRepository sessions;
    private final OrganizationCreator organizationCreator;
    private final SessionAssembler sessionAssembler;
    private final UserRepository users;
    private final InvitationRepository invitations;
    private final InvitationTokens invitationTokens;
    private final AuditService audit;
    private final com.flowops.billing.service.EntitlementService entitlementService;

    /** A generated invitation is valid for seven days; regenerating resets the clock. */
    private static final Duration INVITE_TTL = Duration.ofDays(7);

    @org.springframework.beans.factory.annotation.Autowired
    public OrganizationService(
            OrganizationRepository organizations,
            OrganizationMemberRepository members,
            AuthSessionRepository sessions,
            OrganizationCreator organizationCreator,
            SessionAssembler sessionAssembler,
            UserRepository users,
            InvitationRepository invitations,
            InvitationTokens invitationTokens,
            AuditService audit,
            @org.springframework.beans.factory.annotation.Autowired(required = false) com.flowops.billing.service.EntitlementService entitlementService) {
        this.organizations = organizations;
        this.members = members;
        this.sessions = sessions;
        this.organizationCreator = organizationCreator;
        this.sessionAssembler = sessionAssembler;
        this.users = users;
        this.invitations = invitations;
        this.invitationTokens = invitationTokens;
        this.audit = audit;
        this.entitlementService = entitlementService;
    }

    public OrganizationService(
            OrganizationRepository organizations,
            OrganizationMemberRepository members,
            AuthSessionRepository sessions,
            OrganizationCreator organizationCreator,
            SessionAssembler sessionAssembler,
            UserRepository users,
            InvitationRepository invitations,
            InvitationTokens invitationTokens,
            AuditService audit) {
        this(organizations, members, sessions, organizationCreator, sessionAssembler, users, invitations, invitationTokens, audit, null);
    }

    /** Every org the caller belongs to — the switcher payload (contract §5.6). */
    @Transactional(readOnly = true)
    public Envelopes.Memberships listMemberships(FlowOpsPrincipal principal) {
        List<MembershipResponse> memberships = members.findMembershipRows(principal.userId()).stream()
                .map(MembershipResponse::of)
                .toList();
        return new Envelopes.Memberships(memberships);
    }

    /** The current org with the caller's role and a live member count (contract §5.7). */
    @Transactional(readOnly = true)
    public Envelopes.OrganizationContext currentContext(FlowOpsPrincipal principal) {
        OrganizationMember membership = requireMembership(principal);
        Organization organization = requireOrganization(principal.organizationId());
        long memberCount = members.countByOrganizationId(organization.getId());
        return Envelopes.OrganizationContext.of(
                OrganizationResponse.of(organization), membership.getRole(), memberCount);
    }

    /**
     * Renames the current org (contract §5.8). Role is enforced in the controller
     * ({@code ADMIN} or {@code OWNER}); the slug is immutable and untouched.
     */
    @Transactional
    public Envelopes.OrganizationContext renameCurrent(
            FlowOpsPrincipal principal, RenameOrganizationRequest request) {
        OrganizationMember membership = requireMembership(principal);
        Organization organization = requireOrganization(principal.organizationId());
        organization.rename(request.name());
        audit.record(
                principal,
                AuditAction.ORGANIZATION_RENAMED,
                organization.getId().toString(),
                "Renamed the organization to \"" + organization.getName() + "\".");
        long memberCount = members.countByOrganizationId(organization.getId());
        return Envelopes.OrganizationContext.of(
                OrganizationResponse.of(organization), membership.getRole(), memberCount);
    }

    /**
     * Creates an additional org with the caller as {@code OWNER} (contract §5.9).
     *
     * <p>Does <em>not</em> switch the caller in: the access token and session are
     * unchanged, and no cookie is set. To enter the new org, call
     * {@link #switchOrganization}.
     */
    public Envelopes.OrganizationContext createOrganization(
            FlowOpsPrincipal principal, CreateOrganizationRequest request) {
        OrganizationCreator.Created created;
        try {
            created = organizationCreator.create(principal.userId(), request.organizationName());
        } catch (DataIntegrityViolationException race) {
            // The only unique constraint reachable here is the org slug, recomputed
            // on each attempt; a single retry resolves a concurrent slug collision.
            created = organizationCreator.create(principal.userId(), request.organizationName());
        }
        return Envelopes.OrganizationContext.created(
                OrganizationResponse.of(created.organization()), created.membership().getRole());
    }

    /** Members of the current org, readable by any role (contract §5.11). */
    @Transactional(readOnly = true)
    public Envelopes.Members listMembers(FlowOpsPrincipal principal) {
        // Confirms the claim still maps to a membership before exposing the list.
        requireMembership(principal);
        List<OrganizationMemberResponse> memberList =
                members.findMemberRows(principal.organizationId()).stream()
                        .map(OrganizationMemberResponse::of)
                        .toList();
        return new Envelopes.Members(memberList);
    }

    /**
     * Switches the caller's session into another of their orgs and mints a new
     * access token carrying the new {@code orgId} and role (contract §5.10).
     *
     * <p>The refresh cookie is deliberately not rotated: the session row now holds
     * the new org, so a later refresh returns the switched tenant.
     */
    @Transactional
    public AuthResult switchOrganization(FlowOpsPrincipal principal, UUID targetOrganizationId) {
        OrganizationMember membership = members
                .findByUserIdAndOrganizationId(principal.userId(), targetOrganizationId)
                // Non-member and nonexistent org are indistinguishable by design.
                .orElseThrow(() -> new ApiException(ErrorCode.ORGANIZATION_NOT_FOUND));

        AuthSession session = sessions.findById(principal.sessionId())
                .orElseThrow(() -> new ApiException(ErrorCode.TOKEN_INVALID));
        session.switchOrganization(targetOrganizationId);

        SessionResponse snapshot =
                sessionAssembler.assemble(principal.userId(), targetOrganizationId);
        AuthResponse response = sessionAssembler.withAccessToken(
                snapshot,
                principal.userId(),
                session.getId(),
                targetOrganizationId,
                membership.getRole());

        return AuthResult.tokenOnly(response);
    }

    /**
     * Changes another member's role (contract §5, team management). Requires
     * {@code ADMIN} (enforced in the controller). Guards, in order: you cannot change
     * your own role; only an {@code OWNER} may grant {@code OWNER} or modify an
     * existing owner; and the last owner can never be demoted.
     */
    @Transactional
    public Envelopes.Members changeMemberRole(
            FlowOpsPrincipal principal, UUID targetUserId, Role newRole) {
        OrganizationMember actor = requireMembership(principal);
        UUID orgId = principal.organizationId();

        if (targetUserId.equals(principal.userId())) {
            throw new ApiException(ErrorCode.CANNOT_MODIFY_SELF);
        }
        OrganizationMember target = members
                .findByUserIdAndOrganizationId(targetUserId, orgId)
                .orElseThrow(() -> new ApiException(ErrorCode.MEMBER_NOT_FOUND));

        // Only an owner may grant OWNER or touch an existing owner. An admin manages
        // members up to admin but must never mint or modify an owner.
        boolean touchesOwner = target.getRole() == Role.OWNER || newRole == Role.OWNER;
        if (touchesOwner && actor.getRole() != Role.OWNER) {
            throw new ApiException(ErrorCode.FORBIDDEN_ROLE);
        }
        // Never demote the last owner — an org must always have one.
        if (target.getRole() == Role.OWNER
                && newRole != Role.OWNER
                && members.countByOrganizationIdAndRole(orgId, Role.OWNER) <= 1) {
            throw new ApiException(ErrorCode.LAST_OWNER);
        }

        Role previousRole = target.getRole();
        target.changeRole(newRole);
        audit.record(
                principal,
                AuditAction.MEMBER_ROLE_CHANGED,
                targetUserId.toString(),
                "Changed a member's role from " + previousRole + " to " + newRole + ".");
        return membersOf(orgId);
    }

    /**
     * Removes another member from the org (contract §5, team management). Requires
     * {@code ADMIN}. You cannot remove yourself; only an owner may remove an owner;
     * and the last owner can never be removed. The removed user's sessions are not
     * force-closed here — the refresh path already re-resolves membership and falls
     * back to another org (or fails cleanly) on their next token rotation (§2.2).
     */
    @Transactional
    public Envelopes.Members removeMember(FlowOpsPrincipal principal, UUID targetUserId) {
        OrganizationMember actor = requireMembership(principal);
        UUID orgId = principal.organizationId();

        if (targetUserId.equals(principal.userId())) {
            throw new ApiException(ErrorCode.CANNOT_MODIFY_SELF);
        }
        OrganizationMember target = members
                .findByUserIdAndOrganizationId(targetUserId, orgId)
                .orElseThrow(() -> new ApiException(ErrorCode.MEMBER_NOT_FOUND));

        if (target.getRole() == Role.OWNER) {
            if (actor.getRole() != Role.OWNER) {
                throw new ApiException(ErrorCode.FORBIDDEN_ROLE);
            }
            if (members.countByOrganizationIdAndRole(orgId, Role.OWNER) <= 1) {
                throw new ApiException(ErrorCode.LAST_OWNER);
            }
        }

        members.delete(target);
        audit.record(
                principal,
                AuditAction.MEMBER_REMOVED,
                targetUserId.toString(),
                "Removed a " + target.getRole() + " from the organization.");
        return membersOf(orgId);
    }

    /** Pending/accepted/revoked invitations for the current org (admin view). */
    @Transactional(readOnly = true)
    public Envelopes.Invitations listInvitations(FlowOpsPrincipal principal) {
        requireMembership(principal);
        List<InvitationResponse> list =
                invitations.findByOrganizationIdOrderByCreatedAtDesc(principal.organizationId())
                        .stream()
                        .map(InvitationResponse::of)
                        .toList();
        return new Envelopes.Invitations(list);
    }

    /**
     * Creates (or re-mints) a tokened invitation for an email at a role. Requires
     * {@code ADMIN}. The role may not be {@code OWNER}. If the address already belongs
     * to a member, there is nothing to invite. Returns the raw token exactly once.
     */
    @Transactional
    public InvitationSecretResponse invite(FlowOpsPrincipal principal, InviteMemberRequest request) {
        requireMembership(principal);
        UUID orgId = principal.organizationId();

        Role role = request.role();
        if (role == Role.OWNER) {
            throw new ApiException(ErrorCode.INVITATION_INVALID);
        }

        // Already a member of this org? Nothing to invite.
        users.findByEmailIgnoreCase(request.email())
                .flatMap(user -> members.findByUserIdAndOrganizationId(user.getId(), orgId))
                .ifPresent(existing -> {
                    throw new ApiException(ErrorCode.INVITATION_INVALID);
                });

        if (entitlementService != null) {
            entitlementService.assertCanAddMember(orgId);
        }

        InvitationTokens.Generated minted = invitationTokens.generate();
        Instant expiresAt = Instant.now().plus(INVITE_TTL);

        Invitation invitation = invitations
                .findByOrganizationIdAndEmailIgnoreCaseAndStatus(
                        orgId, request.email(), Invitation.Status.PENDING)
                .map(pending -> {
                    pending.reissue(role, minted.hash(), minted.hint(), expiresAt);
                    return pending;
                })
                .orElseGet(() -> invitations.save(Invitation.create(
                        orgId, request.email(), role, minted.hash(), minted.hint(),
                        principal.userId(), expiresAt)));

        // The minted token is deliberately absent from the audit trail — only the
        // invited address and the role it grants are recorded.
        audit.record(
                principal,
                AuditAction.MEMBER_INVITED,
                invitation.getEmail(),
                "Invited " + invitation.getEmail() + " as " + role + ".");

        return new InvitationSecretResponse(
                minted.token(), invitation.getEmail(), role, minted.hint(), expiresAt);
    }

    /** Revokes a pending invitation (admin). Idempotent-safe: a revoked invite stays revoked. */
    @Transactional
    public void revokeInvitation(FlowOpsPrincipal principal, UUID invitationId) {
        requireMembership(principal);
        Invitation invitation = invitations
                .findByIdAndOrganizationId(invitationId, principal.organizationId())
                .orElseThrow(() -> new ApiException(ErrorCode.INVITATION_NOT_FOUND));
        invitation.revoke();
        audit.record(
                principal,
                AuditAction.INVITATION_REVOKED,
                invitation.getEmail(),
                "Revoked the invitation for " + invitation.getEmail() + ".");
    }

    /**
     * Redeems an invitation token for the authenticated caller, adding them to the
     * invitation's org at its role. The org and role come only from the stored
     * invitation, never the client. Every failure — unknown/expired/revoked token, or
     * a token whose email does not match the caller's — is the same opaque
     * {@code INVITATION_NOT_FOUND}, so a leaked token cannot enroll another account
     * and cannot be probed. Does not switch the caller in; they switch afterwards.
     */
    @Transactional
    public Envelopes.OrganizationContext acceptInvitation(FlowOpsPrincipal principal, String token) {
        Invitation invitation = invitations
                .findByTokenHash(invitationTokens.hash(token))
                .filter(Invitation::isPending)
                .filter(candidate -> !candidate.isExpired(Instant.now()))
                .orElseThrow(() -> new ApiException(ErrorCode.INVITATION_NOT_FOUND));

        // Match against the authoritative DB email, not the token's convenience claim.
        UserAccount user = users.findById(principal.userId())
                .orElseThrow(() -> new ApiException(ErrorCode.AUTHENTICATION_REQUIRED));
        if (!invitation.getEmail().equalsIgnoreCase(user.getEmail())) {
            throw new ApiException(ErrorCode.INVITATION_NOT_FOUND);
        }

        UUID orgId = invitation.getOrganizationId();
        if (members.findByUserIdAndOrganizationId(user.getId(), orgId).isEmpty()) {
            members.save(OrganizationMember.create(user.getId(), orgId, invitation.getRole()));
        }
        invitation.accept();

        // Recorded against the org being JOINED — read from the stored invitation, not
        // the principal's current org, which is a different tenant at this moment.
        audit.record(
                orgId,
                user.getId(),
                user.getEmail(),
                AuditAction.INVITATION_ACCEPTED,
                user.getEmail(),
                user.getEmail() + " joined as " + invitation.getRole() + ".");

        Organization organization = requireOrganization(orgId);
        return Envelopes.OrganizationContext.created(
                OrganizationResponse.of(organization), invitation.getRole());
    }

    private Envelopes.Members membersOf(UUID organizationId) {
        List<OrganizationMemberResponse> memberList =
                members.findMemberRows(organizationId).stream()
                        .map(OrganizationMemberResponse::of)
                        .toList();
        return new Envelopes.Members(memberList);
    }

    private OrganizationMember requireMembership(FlowOpsPrincipal principal) {
        return members
                .findByUserIdAndOrganizationId(principal.userId(), principal.organizationId())
                // The token's orgId no longer maps to a membership: removed mid-token.
                .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN_ROLE));
    }

    private Organization requireOrganization(UUID organizationId) {
        return organizations.findById(organizationId)
                .orElseThrow(() -> new ApiException(ErrorCode.ORGANIZATION_NOT_FOUND));
    }
}
