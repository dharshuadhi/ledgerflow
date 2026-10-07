package com.ledgerflow.common;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Append-only record of security-sensitive operations. */
@Entity
@Table(name = "audit_log", indexes = {
        @Index(name = "ix_audit_actor_created", columnList = "actor, createdAt"),
})
public class AuditLog {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(nullable = false, length = 128)
    private String actor;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(length = 64)
    private String resourceType;

    @Column(length = 64)
    private String resourceId;

    @Column(nullable = false, columnDefinition = "jsonb")
    private String details;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditLog() {
    }

    public AuditLog(String actor, String action, String resourceType,
                    String resourceId, String details) {
        this.id = UUID.randomUUID();
        this.actor = actor;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.details = details == null ? "{}" : details;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getActor() { return actor; }
    public String getAction() { return action; }
    public String getResourceType() { return resourceType; }
    public String getResourceId() { return resourceId; }
    public String getDetails() { return details; }
    public Instant getCreatedAt() { return createdAt; }
}
