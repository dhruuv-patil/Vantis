package com.flowops.auth;

import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;
import com.flowops.common.text.Slugs;
import com.flowops.domain.OAuthAccount;
import com.flowops.domain.Organization;
import com.flowops.domain.OrganizationMember;
import com.flowops.domain.Role;
import com.flowops.domain.UserAccount;
import com.flowops.repository.OAuthAccountRepository;
import com.flowops.repository.OrganizationMemberRepository;
import com.flowops.repository.OrganizationRepository;
import com.flowops.repository.UserRepository;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles OAuth2 user resolution, safe account-linking, and registration.
 *
 * <p>Invariant: An account is linked to an existing Vantis user ONLY if the
 * OAuth provider has cryptographically verified that the user controls the email.
 * Unverified emails are strictly rejected.
 */
@Service
public class OAuthAccountService {

    private static final Logger log = LoggerFactory.getLogger(OAuthAccountService.class);

    private final UserRepository users;
    private final OAuthAccountRepository oauthAccounts;
    private final OrganizationRepository organizations;
    private final OrganizationMemberRepository members;

    public OAuthAccountService(
            UserRepository users,
            OAuthAccountRepository oauthAccounts,
            OrganizationRepository organizations,
            OrganizationMemberRepository members) {
        this.users = users;
        this.oauthAccounts = oauthAccounts;
        this.organizations = organizations;
        this.members = members;
    }

    public record ResolvedUserContext(
            UserAccount user,
            Organization organization,
            Role role,
            boolean isNewUser) {
    }

    @Transactional
    public ResolvedUserContext processOAuthLogin(
            String provider,
            String providerUserId,
            String email,
            boolean emailVerified,
            String fullName,
            String avatarUrl) {

        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(providerUserId, "providerUserId must not be null");

        String normalizedProvider = provider.toLowerCase(Locale.ROOT).trim();
        String normalizedProviderUserId = providerUserId.trim();

        if (!emailVerified || email == null || email.isBlank()) {
            log.warn("OAuth login rejected for provider {} user {}: email is not verified",
                    normalizedProvider, normalizedProviderUserId);
            throw new ApiException(ErrorCode.AUTHENTICATION_REQUIRED,
                    "Your " + provider + " account must have a verified email address to continue.");
        }

        String normalizedEmail = email.toLowerCase(Locale.ROOT).trim();

        // 1. Look up existing OAuth link
        Optional<OAuthAccount> existingOAuth =
                oauthAccounts.findByProviderAndProviderUserId(normalizedProvider, normalizedProviderUserId);

        if (existingOAuth.isPresent()) {
            OAuthAccount link = existingOAuth.get();
            UserAccount user = users.findById(link.getUserId())
                    .orElseThrow(() -> new ApiException(ErrorCode.AUTHENTICATION_REQUIRED, "User not found"));

            if (avatarUrl != null && user.getAvatarUrl() == null) {
                user.setAvatarUrl(avatarUrl);
            }
            if (!normalizedEmail.equalsIgnoreCase(link.getEmail())) {
                link.updateEmail(normalizedEmail);
            }

            OrganizationMember membership = members.findFirstByUserIdOrderByJoinedAtAscOrganizationIdAsc(user.getId())
                    .orElseThrow(() -> new ApiException(ErrorCode.NO_ORGANIZATION_CONTEXT));
            Organization organization = organizations.findById(membership.getOrganizationId())
                    .orElseThrow(() -> new ApiException(ErrorCode.NO_ORGANIZATION_CONTEXT));

            return new ResolvedUserContext(user, organization, membership.getRole(), false);
        }

        // 2. Safe account-linking: check if a user with this verified email already exists
        Optional<UserAccount> existingUser = users.findByEmailIgnoreCase(normalizedEmail);
        if (existingUser.isPresent()) {
            UserAccount user = existingUser.get();
            log.info("Linking verified {} account (id: {}) to existing user {}",
                    normalizedProvider, normalizedProviderUserId, user.getId());

            OAuthAccount newLink = OAuthAccount.create(
                    user.getId(), normalizedProvider, normalizedProviderUserId, normalizedEmail);
            oauthAccounts.save(newLink);

            if (avatarUrl != null && user.getAvatarUrl() == null) {
                user.setAvatarUrl(avatarUrl);
            }

            OrganizationMember membership = members.findFirstByUserIdOrderByJoinedAtAscOrganizationIdAsc(user.getId())
                    .orElseThrow(() -> new ApiException(ErrorCode.NO_ORGANIZATION_CONTEXT));
            Organization organization = organizations.findById(membership.getOrganizationId())
                    .orElseThrow(() -> new ApiException(ErrorCode.NO_ORGANIZATION_CONTEXT));

            return new ResolvedUserContext(user, organization, membership.getRole(), false);
        }

        // 3. Brand new user registration via OAuth
        log.info("Creating new user via {} OAuth (email: {})", normalizedProvider, normalizedEmail);
        UserAccount newUser = users.save(UserAccount.createOAuthUser(normalizedEmail, fullName, avatarUrl));

        String displayName = (fullName != null && !fullName.isBlank()) ? fullName : normalizedEmail.split("@")[0];
        String orgName = displayName + "'s Workspace";
        String slug = Slugs.uniqueSlug(orgName, organizations::existsBySlug);
        Organization organization = organizations.save(Organization.create(orgName, slug));

        OrganizationMember membership = members.save(
                OrganizationMember.create(newUser.getId(), organization.getId(), Role.OWNER));

        OAuthAccount newLink = OAuthAccount.create(
                newUser.getId(), normalizedProvider, normalizedProviderUserId, normalizedEmail);
        oauthAccounts.save(newLink);

        return new ResolvedUserContext(newUser, organization, Role.OWNER, true);
    }
}
