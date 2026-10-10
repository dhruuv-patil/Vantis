package com.flowops.reliability;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Architectural invariant test verifying that critical reliability, detection,
 * anomaly recording, alerting, failure dispatch, and notification systems NEVER depend on billing.
 * Alerts, failure notifications, and anomaly baselines must never be blocked by billing limits.
 */
@AnalyzeClasses(
        packages = "com.flowops",
        importOptions = {ImportOption.DoNotIncludeTests.class})
public class ReliabilityBillingIsolationTest {

    @ArchTest
    static final ArchRule reliabilityAndFailureAlertsMustNotDependOnBilling =
            noClasses()
                    .that().resideInAnyPackage(
                            "com.flowops.reliability.detect..",
                            "com.flowops.reliability.baseline..",
                            "com.flowops.reliability.telemetry..",
                            "com.flowops.notification..")
                    .or().haveSimpleName("SlackAlertService")
                    .or().haveSimpleName("ReliabilityService")
                    .or().haveSimpleName("DeadLetterService")
                    .or().haveSimpleName("DeadLetterController")
                    .or().haveSimpleName("DeadLetterQueueExecutor")
                    .or().haveSimpleName("ExecutionRecoveryService")
                    .or().haveSimpleName("ExecutionEvents")
                    .or().haveSimpleName("ExecutionEventService")
                    .or().haveSimpleName("AlertDeliveryService")
                    .or().haveSimpleName("RecoveryVerificationService")
                    .should().dependOnClassesThat().resideInAPackage("com.flowops.billing..")
                    .allowEmptyShould(false);

    @ArchTest
    static void sanitySubjectSetIsNonEmpty(com.tngtech.archunit.core.domain.JavaClasses classes) {
        long matchCount = 0;
        for (var clazz : classes) {
            String pkg = clazz.getPackageName();
            String name = clazz.getSimpleName();
            if (pkg.startsWith("com.flowops.reliability.detect")
                    || pkg.startsWith("com.flowops.reliability.baseline")
                    || pkg.startsWith("com.flowops.reliability.telemetry")
                    || pkg.startsWith("com.flowops.notification")
                    || "SlackAlertService".equals(name)
                    || "ReliabilityService".equals(name)
                    || "DeadLetterService".equals(name)
                    || "DeadLetterController".equals(name)
                    || "DeadLetterQueueExecutor".equals(name)
                    || "ExecutionRecoveryService".equals(name)
                    || "ExecutionEvents".equals(name)
                    || "ExecutionEventService".equals(name)
                    || "AlertDeliveryService".equals(name)
                    || "RecoveryVerificationService".equals(name)) {
                matchCount++;
            }
        }
        org.assertj.core.api.Assertions.assertThat(matchCount)
                .as("Subject class set for billing isolation ArchRule must not be empty")
                .isGreaterThan(0);
        org.assertj.core.api.Assertions.assertThat(classes.contain(com.flowops.reliability.ReliabilityService.class))
                .isTrue();
        org.assertj.core.api.Assertions.assertThat(classes.contain(com.flowops.execution.DeadLetterService.class))
                .isTrue();
        org.assertj.core.api.Assertions.assertThat(classes.contain(com.flowops.execution.ExecutionRecoveryService.class))
                .isTrue();
    }
}
