package com.engine.idemgate.idempotency;

import com.engine.idemgate.model.IdempotencyRecord;

/**
 * Outcome of attempting to acquire in-flight execution rights for an idempotency key.
 */
public class LockAcquisitionResult {

    public enum Status {
        ACQUIRED,
        CONFLICT_IN_FLIGHT,
        ALREADY_RESOLVED
    }

    private final Status status;
    private final IdempotencyRecord existingRecord;

    public LockAcquisitionResult(Status status, IdempotencyRecord existingRecord) {
        this.status = status;
        this.existingRecord = existingRecord;
    }

    public static LockAcquisitionResult acquired() {
        return new LockAcquisitionResult(Status.ACQUIRED, null);
    }

    public static LockAcquisitionResult conflictInFlight(IdempotencyRecord record) {
        return new LockAcquisitionResult(Status.CONFLICT_IN_FLIGHT, record);
    }

    public static LockAcquisitionResult alreadyResolved(IdempotencyRecord record) {
        return new LockAcquisitionResult(Status.ALREADY_RESOLVED, record);
    }

    public Status getStatus() {
        return status;
    }

    public IdempotencyRecord getExistingRecord() {
        return existingRecord;
    }
}
