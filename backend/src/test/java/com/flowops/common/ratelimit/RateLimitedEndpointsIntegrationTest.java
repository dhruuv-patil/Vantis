package com.flowops.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.ai.AiController;
import com.flowops.ai.AiServiceClient;
import com.flowops.ai.GenerateWorkflowRequest;
import com.flowops.auth.AuthController;
import com.flowops.auth.AuthService;
import com.flowops.auth.ForgotPasswordRequest;
import com.flowops.auth.LoginRequest;
import com.flowops.auth.PasswordResetMailer;
import com.flowops.auth.PasswordResetService;
import com.flowops.auth.RefreshCookies;
import com.flowops.auth.RegisterRequest;
import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;
import com.flowops.config.AuthProperties;
import com.flowops.domain.Role;
import com.flowops.execution.ExecutionService;
import com.flowops.organization.AcceptInvitationRequest;
import com.flowops.organization.CreateOrganizationRequest;
import com.flowops.organization.InvitationController;
import com.flowops.organization.OrganizationController;
import com.flowops.organization.OrganizationService;
import com.flowops.reliability.ReliabilityController;
import com.flowops.reliability.ReliabilityService;
import com.flowops.repository.AuthSessionRepository;
import com.flowops.repository.IdempotencyKeyRepository;
import com.flowops.repository.PasswordResetTokenRepository;
import com.flowops.repository.RefreshTokenRepository;
import com.flowops.repository.UserRepository;
import com.flowops.security.FlowOpsPrincipal;
import com.flowops.webhook.WebhookIngressController;
import com.flowops.webhook.WebhookService;
import com.flowops.workflow.WorkflowController;
import com.flowops.workflow.WorkflowService;
import com.flowops.workflow.nodes.NodeRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Verifies that all protected endpoints continue to enforce rate limiting via {@link RateLimiter}.
 */
class RateLimitedEndpointsIntegrationTest {

    private RateLimiter rateLimiter;
    private HttpServletRequest httpRequest;
    private HttpServletResponse httpResponse;

    private UUID userId;
    private UUID orgId;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        rateLimiter = mock(RateLimiter.class);
        httpRequest = mock(HttpServletRequest.class);
        httpResponse = mock(HttpServletResponse.class);

        when(httpRequest.getRemoteAddr()).thenReturn("198.51.100.42");

        userId = UUID.randomUUID();
        orgId = UUID.randomUUID();
        sessionId = UUID.randomUUID();

        FlowOpsPrincipal principal = new FlowOpsPrincipal(userId, sessionId, orgId, Role.OWNER, "test@example.com");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_OWNER")))
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("AuthController.register invokes rate limiter with client IP")
    void authControllerRegisterUsesLimiter() {
        AuthController controller = new AuthController(
                mock(AuthService.class),
                mock(PasswordResetService.class),
                mock(RefreshCookies.class),
                rateLimiter
        );

        doThrow(ApiException.rateLimited(60))
                .when(rateLimiter).check(argThat(k -> k.startsWith("register:ip:")), eq(5), eq(Duration.ofMinutes(60)));

        RegisterRequest request = new RegisterRequest("Test User", "test@example.com", "SecurePass123!", "Acme Org");

        assertThatThrownBy(() -> controller.register(request, httpRequest))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.RATE_LIMITED);

        verify(rateLimiter).check("register:ip:198.51.100.42", 5, Duration.ofMinutes(60));
    }

    @Test
    @DisplayName("AuthController.login invokes rate limiter with client IP and email")
    void authControllerLoginUsesLimiter() {
        AuthController controller = new AuthController(
                mock(AuthService.class),
                mock(PasswordResetService.class),
                mock(RefreshCookies.class),
                rateLimiter
        );

        doThrow(ApiException.rateLimited(60))
                .when(rateLimiter).check(argThat(k -> k.startsWith("login:")), eq(10), eq(Duration.ofMinutes(15)));

        LoginRequest request = new LoginRequest("user@example.com", "Password123!");

        assertThatThrownBy(() -> controller.login(request, httpRequest))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.RATE_LIMITED);

        verify(rateLimiter).check("login:198.51.100.42:user@example.com", 10, Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("AuthController.refresh invokes rate limiter with client IP")
    void authControllerRefreshUsesLimiter() {
        AuthController controller = new AuthController(
                mock(AuthService.class),
                mock(PasswordResetService.class),
                mock(RefreshCookies.class),
                rateLimiter
        );

        doThrow(ApiException.rateLimited(60))
                .when(rateLimiter).check(argThat(k -> k.startsWith("refresh:ip:")), eq(60), eq(Duration.ofMinutes(15)));

        assertThatThrownBy(() -> controller.refresh(null, httpRequest, httpResponse))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.RATE_LIMITED);

        verify(rateLimiter).check("refresh:ip:198.51.100.42", 60, Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("AuthController.forgotPassword invokes rate limiter with client IP")
    void authControllerForgotPasswordUsesLimiter() {
        AuthController controller = new AuthController(
                mock(AuthService.class),
                mock(PasswordResetService.class),
                mock(RefreshCookies.class),
                rateLimiter
        );

        doThrow(ApiException.rateLimited(60))
                .when(rateLimiter).check(argThat(k -> k.startsWith("forgot-password:ip:")), eq(5), eq(Duration.ofMinutes(60)));

        ForgotPasswordRequest request = new ForgotPasswordRequest("reset@example.com");

        assertThatThrownBy(() -> controller.forgotPassword(request, httpRequest))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.RATE_LIMITED);

        verify(rateLimiter).check("forgot-password:ip:198.51.100.42", 5, Duration.ofMinutes(60));
    }

    @Test
    @DisplayName("PasswordResetService.forgotPassword invokes rate limiter with IP and email")
    void passwordResetServiceForgotPasswordUsesLimiter() {
        AuthProperties props = mock(AuthProperties.class);
        when(props.resetTokenTtl()).thenReturn(Duration.ofHours(1));

        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(any())).thenReturn("hashed");

        PasswordResetService service = new PasswordResetService(
                mock(UserRepository.class),
                mock(PasswordResetTokenRepository.class),
                mock(RefreshTokenRepository.class),
                mock(AuthSessionRepository.class),
                encoder,
                props,
                rateLimiter,
                mock(PasswordResetMailer.class)
        );

        doThrow(ApiException.rateLimited(60))
                .when(rateLimiter).check(argThat(k -> k.startsWith("forgot-password:")), eq(5), eq(Duration.ofMinutes(60)));

        ForgotPasswordRequest request = new ForgotPasswordRequest("target@example.com");

        assertThatThrownBy(() -> service.forgotPassword(request, "198.51.100.42"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.RATE_LIMITED);

        verify(rateLimiter).check("forgot-password:198.51.100.42:target@example.com", 5, Duration.ofMinutes(60));
    }

    @Test
    @DisplayName("OrganizationController.create invokes rate limiter with authenticated user ID")
    void organizationControllerCreateUsesLimiter() {
        OrganizationController controller = new OrganizationController(
                mock(OrganizationService.class),
                rateLimiter
        );

        doThrow(ApiException.rateLimited(60))
                .when(rateLimiter).check(argThat(k -> k.startsWith("create-org:user:")), eq(20), eq(Duration.ofMinutes(60)));

        CreateOrganizationRequest request = new CreateOrganizationRequest("New Org");

        assertThatThrownBy(() -> controller.create(request, httpRequest))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.RATE_LIMITED);

        verify(rateLimiter).check("create-org:user:" + userId, 20, Duration.ofMinutes(60));
    }

    @Test
    @DisplayName("InvitationController.accept invokes rate limiter with authenticated user ID")
    void invitationControllerAcceptUsesLimiter() {
        InvitationController controller = new InvitationController(
                mock(OrganizationService.class),
                rateLimiter
        );

        doThrow(ApiException.rateLimited(60))
                .when(rateLimiter).check(argThat(k -> k.startsWith("invite-accept:user:")), eq(20), eq(Duration.ofMinutes(15)));

        AcceptInvitationRequest request = new AcceptInvitationRequest("token-123");

        assertThatThrownBy(() -> controller.accept(request, httpRequest))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.RATE_LIMITED);

        verify(rateLimiter).check("invite-accept:user:" + userId, 20, Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("WebhookIngressController.receive invokes rate limiter with workflow ID")
    void webhookIngressControllerUsesLimiter() {
        WebhookIngressController controller = new WebhookIngressController(
                rateLimiter,
                mock(WebhookService.class),
                mock(ExecutionService.class),
                new ObjectMapper(),
                mock(IdempotencyKeyRepository.class)
        );

        UUID workflowId = UUID.randomUUID();
        doThrow(ApiException.rateLimited(60))
                .when(rateLimiter).check(eq("webhook:" + workflowId), eq(60), eq(Duration.ofMinutes(1)));

        assertThatThrownBy(() -> controller.receive(workflowId.toString(), "token-abc", null, httpRequest))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.RATE_LIMITED);

        verify(rateLimiter).check("webhook:" + workflowId, 60, Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("AiController.generate invokes rate limiter with user ID")
    void aiControllerGenerateUsesLimiter() {
        AiController controller = new AiController(
                mock(AiServiceClient.class),
                mock(NodeRegistry.class),
                new ObjectMapper(),
                rateLimiter
        );

        doThrow(ApiException.rateLimited(60))
                .when(rateLimiter).check(eq("ai:generate:" + userId), eq(20), eq(Duration.ofMinutes(1)));

        GenerateWorkflowRequest request = new GenerateWorkflowRequest("Build pipeline");

        assertThatThrownBy(() -> controller.generate(request))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.RATE_LIMITED);

        verify(rateLimiter).check("ai:generate:" + userId, 20, Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("WorkflowController.run invokes rate limiter with user ID")
    void workflowControllerRunUsesLimiter() {
        WorkflowController controller = new WorkflowController(
                mock(WorkflowService.class),
                mock(ExecutionService.class),
                rateLimiter
        );

        UUID workflowId = UUID.randomUUID();
        doThrow(ApiException.rateLimited(60))
                .when(rateLimiter).check(eq("workflow:run:" + userId), eq(60), eq(Duration.ofMinutes(1)));

        assertThatThrownBy(() -> controller.run(workflowId, null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.RATE_LIMITED);

        verify(rateLimiter).check("workflow:run:" + userId, 60, Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("ReliabilityController.investigateAnomaly invokes rate limiter with user ID")
    void reliabilityControllerInvestigateUsesLimiter() {
        ReliabilityController controller = new ReliabilityController(
                mock(ReliabilityService.class),
                rateLimiter
        );

        UUID anomalyId = UUID.randomUUID();
        doThrow(ApiException.rateLimited(60))
                .when(rateLimiter).check(eq("ai:investigate:" + userId), eq(20), eq(Duration.ofMinutes(1)));

        assertThatThrownBy(() -> controller.investigateAnomaly(
                null,
                anomalyId,
                null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.RATE_LIMITED);

        verify(rateLimiter).check("ai:investigate:" + userId, 20, Duration.ofMinutes(1));
    }
}
