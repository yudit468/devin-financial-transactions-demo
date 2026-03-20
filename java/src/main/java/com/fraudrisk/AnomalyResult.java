package com.fraudrisk;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a detected anomaly in a customer's transaction sequence.
 * Contains the customer identifier, anomaly type, severity, affected
 * transaction IDs, and a human-readable description of the anomaly.
 */
public class AnomalyResult {

    /**
     * Enumeration of anomaly severity levels.
     */
    public enum Severity {
        LOW, MEDIUM, HIGH
    }

    /**
     * Enumeration of anomaly types detected by the sequence analyzer.
     */
    public enum AnomalyType {
        REPEATED_HIGH_VALUE,
        TRANSFER_THEN_CASHOUT,
        SUDDEN_AMOUNT_INCREASE,
        TRANSFER_TRANSFER_CASHOUT,
        RAPID_HIGH_VALUE_SEQUENCE,
        SUDDEN_TYPE_CHANGE
    }

    private final String customerId;
    private final AnomalyType anomalyType;
    private final Severity severity;
    private final List<Integer> affectedTransactionIds;
    private final String description;

    public AnomalyResult(String customerId, AnomalyType anomalyType, Severity severity,
                         List<Integer> affectedTransactionIds, String description) {
        this.customerId = customerId;
        this.anomalyType = anomalyType;
        this.severity = severity;
        this.affectedTransactionIds = new ArrayList<Integer>(affectedTransactionIds);
        this.description = description;
    }

    public String getCustomerId() {
        return customerId;
    }

    public AnomalyType getAnomalyType() {
        return anomalyType;
    }

    public Severity getSeverity() {
        return severity;
    }

    public List<Integer> getAffectedTransactionIds() {
        return new ArrayList<Integer>(affectedTransactionIds);
    }

    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return String.format("AnomalyResult{customer='%s', type=%s, severity=%s, txns=%s, desc='%s'}",
                customerId, anomalyType, severity, affectedTransactionIds, description);
    }
}
