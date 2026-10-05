package com.flowops.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;
import com.flowops.domain.DeadLetterRecord;
import com.flowops.domain.WorkflowExecution;
import com.flowops.repository.DeadLetterRecordRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for managing the durable failure path (Dead Letter Queue).
 * Unrecoverable work is captured with original input, execution context, and failure details.
 */
@Service
public class DeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);

    private final DeadLetterRecordRepository repository;

    public DeadLetterService(DeadLetterRecordRepository repository) {
        this.repository = repository;
    }

    /**
     * Captures an unrecoverable failure into the dead letter store.
     */
    @Transactional
    public DeadLetterRecord capture(
            WorkflowExecution execution,
            String failedNodeId,
            String failedNodeType,
            int attempts,
            String error,
            JsonNode executionContext) {

        // Avoid duplicate dead letter rows for the same execution
        Optional<DeadLetterRecord> existing = repository.findByExecutionId(execution.getId());
        if (existing.isPresent()) {
            return existing.get();
        }

        DeadLetterRecord record = DeadLetterRecord.create(
                execution.getOrganizationId(),
                execution.getWorkflowId(),
                execution.getWorkflowVersionId(),
                execution.getId(),
                failedNodeId,
                failedNodeType,
                attempts,
                error,
                execution.getTriggerPayload(),
                executionContext);

        DeadLetterRecord saved = repository.save(record);
        log.warn("Captured execution {} into Dead Letter Queue (workflow={}, node={}): {}",
                execution.getId(), execution.getWorkflowId(), failedNodeId, error);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<DeadLetterRecord> list(UUID organizationId, UUID workflowId, String status, int page, int size) {
        return repository.search(organizationId, workflowId, status, PageRequest.of(page, Math.min(size, 100)));
    }

    @Transactional(readOnly = true)
    public DeadLetterRecord get(UUID organizationId, UUID deadLetterId) {
        return repository.findByIdAndOrganizationId(deadLetterId, organizationId)
                .orElseThrow(() -> new ApiException(ErrorCode.DEAD_LETTER_NOT_FOUND));
    }

    @Transactional
    public void markInvestigating(UUID organizationId, UUID deadLetterId) {
        DeadLetterRecord record = get(organizationId, deadLetterId);
        record.markInvestigating();
        repository.save(record);
    }

    @Transactional
    public void markResolved(UUID organizationId, UUID deadLetterId) {
        DeadLetterRecord record = get(organizationId, deadLetterId);
        record.markResolved();
        repository.save(record);
    }

    @Transactional
    public void markReplayed(UUID organizationId, UUID deadLetterId, UUID newExecutionId) {
        DeadLetterRecord record = get(organizationId, deadLetterId);
        record.markReplayed(newExecutionId);
        repository.save(record);
    }
}
