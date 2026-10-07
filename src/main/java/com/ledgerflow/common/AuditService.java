package com.ledgerflow.common;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Writes audit records in their own transaction so they survive rollbacks. */
@Service
public class AuditService {

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String actor, String action, String resourceType,
                       String resourceId, String details) {
        repository.save(new AuditLog(actor, action, resourceType, resourceId, details));
    }
}
