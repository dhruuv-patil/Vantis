package com.flowops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.domain.OrganizationMember;
import com.flowops.domain.Role;
import com.flowops.repository.OrganizationMemberRepository;
import com.flowops.repository.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Tenancy isolation and role-enforcement security tests.
 *
 * <p>Two properties are pinned down here:
 *
 * <ul>
 *   <li><b>Tenant isolation (contract §2):</b> the organization id is never read
 *       from the client. A request for another org's resource must come back
 *       {@code 404 NOT_FOUND} — the same envelope as a nonexistent resource, so
 *       cross-tenant existence cannot be probed.</li>
 *   <li><b>RBAC:</b> {@code AuthenticatedUser.requireRole} enforces a minimum
 *       role by rank. VIEWER may read but not mutate; MEMBER may mutate ordinary
 *       workflows but not admin-only actions; a read is always {@code 401} with no
 *       token at all.</li>
 * </ul>
 *
 * <p>Second memberships are injected directly through
 * {@link OrganizationMemberRepository} and the user's own (created-on-register)
 * membership is deleted, so re-login resolves to the target org and role.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthzIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OrganizationMemberRepository members;

    @Autowired
    private UserRepository users;

    // =========================================================================
    // Tenant isolation
    // =========================================================================

    @Test
    void crossTenantResourceAccessIsA404NotA403() throws Exception {
        Session ownerA = register(
                "tenant-a+" + UUID.randomUUID() + "@example.com",
                "pass-word-1234", "Tenant A", "Org A");
        Session ownerB = register(
                "tenant-b+" + UUID.randomUUID() + "@example.com",
                "pass-word-1234", "Tenant B", "Org B");

        String workflowInB = createWorkflow(ownerB, "Workflow in B");

        // A must not be able to read, run, or inspect B's workflow — and the
        // response must look exactly like "does not exist".
        mvc.perform(get("/api/workflows/" + workflowInB)
                        .header("Authorization", "Bearer " + ownerA.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("WORKFLOW_NOT_FOUND"));

        mvc.perform(post("/api/workflows/" + workflowInB + "/run")
                        .header("Authorization", "Bearer " + ownerA.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("WORKFLOW_NOT_FOUND"));

        mvc.perform(get("/api/reliability/workflows/" + workflowInB + "/health")
                        .header("Authorization", "Bearer " + ownerA.accessToken()))
                .andExpect(status().isNotFound());

        // B, the owner, can still see it — the 404 above was isolation, not damage.
        mvc.perform(get("/api/workflows/" + workflowInB)
                        .header("Authorization", "Bearer " + ownerB.accessToken()))
                .andExpect(status().isOk());
    }

    @Test
    void protectedReadWithoutATokenIs401() throws Exception {
        mvc.perform(get("/api/workflows"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }

    // =========================================================================
    // RBAC matrix
    // =========================================================================

    @Test
    void viewerCanReadButCannotMutate() throws Exception {
        Session owner = register(
                "rbac-owner+" + UUID.randomUUID() + "@example.com",
                "pass-word-1234", "RBAC Owner", "RBAC Org");

        Session viewer = asRole(
                "rbac-viewer+" + UUID.randomUUID() + "@example.com",
                owner.organizationId(),
                Role.VIEWER);

        // Read is fine.
        mvc.perform(get("/api/workflows")
                        .header("Authorization", "Bearer " + viewer.accessToken()))
                .andExpect(status().isOk());

        // A mutation that needs at least MEMBER is denied by rank.
        mvc.perform(post("/api/workflows")
                        .header("Authorization", "Bearer " + viewer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"sneaky\",\"description\":\"\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_ROLE"));

        // So is an ADMIN-only action.
        mvc.perform(post("/api/integrations/providers")
                        .header("Authorization", "Bearer " + viewer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"slack\",\"name\":\"x\",\"config\":{}}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_ROLE"));
    }

    @Test
    void memberCanMutateButNotAdministrate() throws Exception {
        Session owner = register(
                "rbac-member-owner+" + UUID.randomUUID() + "@example.com",
                "pass-word-1234", "Member Owner", "Member Org");

        Session member = asRole(
                "rbac-member+" + UUID.randomUUID() + "@example.com",
                owner.organizationId(),
                Role.MEMBER);

        // MEMBER can create workflows inside the shared org.
        mvc.perform(post("/api/workflows")
                        .header("Authorization", "Bearer " + member.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"member workflow\",\"description\":\"\"}"))
                .andExpect(status().isCreated());

        // But an ADMIN-only action is still denied by rank.
        mvc.perform(post("/api/integrations/providers")
                        .header("Authorization", "Bearer " + member.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"slack\",\"name\":\"x\",\"config\":{}}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN_ROLE"));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private record Session(
            String accessToken,
            UUID userId,
            UUID organizationId) {
    }

    private Session register(String email, String password, String name, String org)
            throws Exception {

        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"
                                + "\"email\":\"" + email + "\","
                                + "\"password\":\"" + password + "\","
                                + "\"fullName\":\"" + name + "\","
                                + "\"organizationName\":\"" + org + "\"}"))
                .andExpect(status().isCreated());

        // Mark verified in-process (bypasses email delivery in tests).
        users.findByEmailIgnoreCase(email).ifPresent(u -> {
            u.markEmailVerified();
            users.save(u);
        });

        MvcResult loginResult = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = MAPPER.readTree(loginResult.getResponse().getContentAsString());
        return new Session(
                body.get("accessToken").asText(),
                UUID.fromString(body.get("user").get("id").asText()),
                UUID.fromString(body.get("currentOrganization").get("id").asText()));
    }

    /**
     * Registers {@code email}, drops the user's own-org membership and joins them
     * to {@code targetOrgId} under {@code role}, then signs in so the new context
     * (not the register-time context) is what the token carries.
     */
    private Session asRole(String email, UUID targetOrgId, Role role)
            throws Exception {

        Session registerTime = register(email, "pass-word-1234", "RBAC User", "RBAC Own Org");

        members.save(OrganizationMember.create(registerTime.userId(), targetOrgId, role));

        // Remove the register-time OWNER membership (by explicit org id, never by
        // joinedAt ordering) so the re-login below resolves to targetOrg.
        OrganizationMember own = members
                .findByUserIdAndOrganizationId(registerTime.userId(), registerTime.organizationId())
                .orElseThrow();
        members.delete(own);

        MvcResult login = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"pass-word-1234\"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = MAPPER.readTree(login.getResponse().getContentAsString());
        assertThat(body.get("currentRole").asText()).isEqualTo(role.name());
        assertThat(body.get("currentOrganization").get("id").asText())
                .isEqualTo(targetOrgId.toString());

        return new Session(
                body.get("accessToken").asText(),
                registerTime.userId(),
                targetOrgId);
    }

    private String createWorkflow(Session session, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/workflows")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"description\":\"\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        return MAPPER.readTree(result.getResponse().getContentAsString())
                .get("id").asText();
    }
}