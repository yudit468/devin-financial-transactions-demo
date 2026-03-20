package com.fraudrisk;

import com.opencsv.CSVWriter;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Analyzes transaction sequences per customer to detect anomalous patterns.
 *
 * Detected anomaly patterns:
 * 1. Repeated high-value transactions in sequence
 * 2. TRANSFER followed by immediate CASH_OUT (high risk)
 * 3. Sudden increase in transaction amount (spike detection)
 * 4. TRANSFER -> TRANSFER -> CASH_OUT sequence
 * 5. Multiple high-value transactions within a short time window
 * 6. Sudden change in transaction type
 */
public class TransactionSequenceAnalyzer {

    /** Threshold above which a transaction is considered high-value */
    private static final double HIGH_VALUE_THRESHOLD = 10000.0;

    /** Factor by which a transaction amount must exceed the running average to be a spike */
    private static final double SPIKE_FACTOR = 3.0;

    /** Maximum step difference to consider transactions as occurring in a short time window */
    private static final int SHORT_TIME_WINDOW = 2;

    /** Minimum number of high-value transactions in a short window to flag as rapid sequence */
    private static final int RAPID_HIGH_VALUE_MIN_COUNT = 2;

    /** Maximum number of anomaly rows in the report (below 50) */
    private static final int MAX_REPORT_ROWS = 49;

    /**
     * Group transactions by originating customer (nameOrig) and sort each
     * group by step (time order).
     */
    public Map<String, List<Transaction>> groupByCustomer(List<Transaction> transactions) {
        Map<String, List<Transaction>> grouped = new HashMap<String, List<Transaction>>();
        for (Transaction txn : transactions) {
            String customer = txn.getNameOrig();
            if (!grouped.containsKey(customer)) {
                grouped.put(customer, new ArrayList<Transaction>());
            }
            grouped.get(customer).add(txn);
        }
        // Sort each customer's transactions by step
        for (List<Transaction> list : grouped.values()) {
            Collections.sort(list, new Comparator<Transaction>() {
                @Override
                public int compare(Transaction a, Transaction b) {
                    int cmp = Integer.compare(a.getStep(), b.getStep());
                    if (cmp != 0) return cmp;
                    return Integer.compare(a.getTransactionId(), b.getTransactionId());
                }
            });
        }
        return grouped;
    }

    /**
     * Detect repeated high-value transactions for a single customer.
     * Two or more consecutive transactions above HIGH_VALUE_THRESHOLD are flagged.
     */
    public List<AnomalyResult> detectRepeatedHighValue(String customerId, List<Transaction> customerTxns) {
        List<AnomalyResult> anomalies = new ArrayList<AnomalyResult>();
        List<Integer> currentRun = new ArrayList<Integer>();

        for (Transaction txn : customerTxns) {
            if (txn.getAmount() > HIGH_VALUE_THRESHOLD) {
                currentRun.add(txn.getTransactionId());
            } else {
                if (currentRun.size() >= 2) {
                    anomalies.add(new AnomalyResult(
                            customerId,
                            AnomalyResult.AnomalyType.REPEATED_HIGH_VALUE,
                            AnomalyResult.Severity.HIGH,
                            new ArrayList<Integer>(currentRun),
                            String.format("Customer %s has %d consecutive high-value transactions (>%.0f)",
                                    customerId, currentRun.size(), HIGH_VALUE_THRESHOLD)
                    ));
                }
                currentRun.clear();
            }
        }
        // Check trailing run
        if (currentRun.size() >= 2) {
            anomalies.add(new AnomalyResult(
                    customerId,
                    AnomalyResult.AnomalyType.REPEATED_HIGH_VALUE,
                    AnomalyResult.Severity.HIGH,
                    new ArrayList<Integer>(currentRun),
                    String.format("Customer %s has %d consecutive high-value transactions (>%.0f)",
                            customerId, currentRun.size(), HIGH_VALUE_THRESHOLD)
            ));
        }
        return anomalies;
    }

    /**
     * Detect TRANSFER followed immediately by CASH_OUT in a customer's sequence.
     * This is a high-risk pattern often associated with money laundering.
     */
    public List<AnomalyResult> detectTransferThenCashout(String customerId, List<Transaction> customerTxns) {
        List<AnomalyResult> anomalies = new ArrayList<AnomalyResult>();
        for (int i = 0; i < customerTxns.size() - 1; i++) {
            Transaction current = customerTxns.get(i);
            Transaction next = customerTxns.get(i + 1);
            if ("TRANSFER".equals(current.getType()) && "CASH_OUT".equals(next.getType())) {
                List<Integer> ids = new ArrayList<Integer>();
                ids.add(current.getTransactionId());
                ids.add(next.getTransactionId());
                anomalies.add(new AnomalyResult(
                        customerId,
                        AnomalyResult.AnomalyType.TRANSFER_THEN_CASHOUT,
                        AnomalyResult.Severity.HIGH,
                        ids,
                        String.format("Customer %s: TRANSFER (txn %d, amount=%.2f) followed by CASH_OUT (txn %d, amount=%.2f)",
                                customerId, current.getTransactionId(), current.getAmount(),
                                next.getTransactionId(), next.getAmount())
                ));
            }
        }
        return anomalies;
    }

    /**
     * Detect sudden increase in transaction amount.
     * A transaction is flagged if its amount exceeds SPIKE_FACTOR times the
     * running average of the customer's prior transactions.
     */
    public List<AnomalyResult> detectSuddenAmountIncrease(String customerId, List<Transaction> customerTxns) {
        List<AnomalyResult> anomalies = new ArrayList<AnomalyResult>();
        if (customerTxns.size() < 2) {
            return anomalies;
        }

        double runningSum = 0.0;
        for (int i = 0; i < customerTxns.size(); i++) {
            Transaction txn = customerTxns.get(i);
            if (i > 0) {
                double runningAvg = runningSum / i;
                if (runningAvg > 0 && txn.getAmount() > runningAvg * SPIKE_FACTOR) {
                    List<Integer> ids = new ArrayList<Integer>();
                    ids.add(txn.getTransactionId());
                    anomalies.add(new AnomalyResult(
                            customerId,
                            AnomalyResult.AnomalyType.SUDDEN_AMOUNT_INCREASE,
                            AnomalyResult.Severity.MEDIUM,
                            ids,
                            String.format("Customer %s: transaction %d amount (%.2f) is %.1fx the running average (%.2f)",
                                    customerId, txn.getTransactionId(), txn.getAmount(),
                                    txn.getAmount() / runningAvg, runningAvg)
                    ));
                }
            }
            runningSum += txn.getAmount();
        }
        return anomalies;
    }

    /**
     * Detect the specific TRANSFER -> TRANSFER -> CASH_OUT sequence.
     * This triple-step pattern is a common indicator of layered fraud.
     */
    public List<AnomalyResult> detectTransferTransferCashout(String customerId, List<Transaction> customerTxns) {
        List<AnomalyResult> anomalies = new ArrayList<AnomalyResult>();
        for (int i = 0; i < customerTxns.size() - 2; i++) {
            Transaction t1 = customerTxns.get(i);
            Transaction t2 = customerTxns.get(i + 1);
            Transaction t3 = customerTxns.get(i + 2);
            if ("TRANSFER".equals(t1.getType())
                    && "TRANSFER".equals(t2.getType())
                    && "CASH_OUT".equals(t3.getType())) {
                List<Integer> ids = new ArrayList<Integer>();
                ids.add(t1.getTransactionId());
                ids.add(t2.getTransactionId());
                ids.add(t3.getTransactionId());
                anomalies.add(new AnomalyResult(
                        customerId,
                        AnomalyResult.AnomalyType.TRANSFER_TRANSFER_CASHOUT,
                        AnomalyResult.Severity.HIGH,
                        ids,
                        String.format("Customer %s: suspicious TRANSFER->TRANSFER->CASH_OUT sequence (txns %d, %d, %d)",
                                customerId, t1.getTransactionId(), t2.getTransactionId(), t3.getTransactionId())
                ));
            }
        }
        return anomalies;
    }

    /**
     * Detect multiple high-value transactions occurring within a short time window.
     * Transactions within SHORT_TIME_WINDOW steps of each other are grouped,
     * and if RAPID_HIGH_VALUE_MIN_COUNT or more are high-value, an anomaly is flagged.
     */
    public List<AnomalyResult> detectRapidHighValueSequence(String customerId, List<Transaction> customerTxns) {
        List<AnomalyResult> anomalies = new ArrayList<AnomalyResult>();
        if (customerTxns.size() < RAPID_HIGH_VALUE_MIN_COUNT) {
            return anomalies;
        }

        // Sliding window approach: find clusters of high-value transactions within the time window
        List<Transaction> highValueTxns = new ArrayList<Transaction>();
        for (Transaction txn : customerTxns) {
            if (txn.getAmount() > HIGH_VALUE_THRESHOLD) {
                highValueTxns.add(txn);
            }
        }

        if (highValueTxns.size() < RAPID_HIGH_VALUE_MIN_COUNT) {
            return anomalies;
        }

        // Check consecutive high-value transactions for time proximity
        int windowStart = 0;
        for (int windowEnd = 1; windowEnd < highValueTxns.size(); windowEnd++) {
            // Move windowStart forward if outside the time window
            while (windowStart < windowEnd
                    && highValueTxns.get(windowEnd).getStep() - highValueTxns.get(windowStart).getStep() > SHORT_TIME_WINDOW) {
                windowStart++;
            }
            int clusterSize = windowEnd - windowStart + 1;
            if (clusterSize >= RAPID_HIGH_VALUE_MIN_COUNT) {
                List<Integer> ids = new ArrayList<Integer>();
                for (int j = windowStart; j <= windowEnd; j++) {
                    ids.add(highValueTxns.get(j).getTransactionId());
                }
                anomalies.add(new AnomalyResult(
                        customerId,
                        AnomalyResult.AnomalyType.RAPID_HIGH_VALUE_SEQUENCE,
                        AnomalyResult.Severity.HIGH,
                        ids,
                        String.format("Customer %s: %d high-value transactions within %d time steps",
                                customerId, clusterSize, SHORT_TIME_WINDOW)
                ));
            }
        }
        return anomalies;
    }

    /**
     * Detect sudden change in transaction type.
     * If a customer's transaction type changes from a low-risk type (PAYMENT, CASH_IN)
     * to a high-risk type (TRANSFER, CASH_OUT), it is flagged as suspicious.
     */
    public List<AnomalyResult> detectSuddenTypeChange(String customerId, List<Transaction> customerTxns) {
        List<AnomalyResult> anomalies = new ArrayList<AnomalyResult>();
        for (int i = 0; i < customerTxns.size() - 1; i++) {
            Transaction current = customerTxns.get(i);
            Transaction next = customerTxns.get(i + 1);
            if (isLowRiskType(current.getType()) && isHighRiskType(next.getType())) {
                List<Integer> ids = new ArrayList<Integer>();
                ids.add(current.getTransactionId());
                ids.add(next.getTransactionId());
                anomalies.add(new AnomalyResult(
                        customerId,
                        AnomalyResult.AnomalyType.SUDDEN_TYPE_CHANGE,
                        AnomalyResult.Severity.MEDIUM,
                        ids,
                        String.format("Customer %s: sudden type change from %s (txn %d) to %s (txn %d)",
                                customerId, current.getType(), current.getTransactionId(),
                                next.getType(), next.getTransactionId())
                ));
            }
        }
        return anomalies;
    }

    /**
     * Detect individual high-value TRANSFER or CASH_OUT transactions.
     * These are suspicious on their own regardless of sequence context.
     */
    public List<AnomalyResult> detectHighValueTransferOrCashout(List<Transaction> transactions) {
        List<AnomalyResult> anomalies = new ArrayList<AnomalyResult>();
        for (Transaction txn : transactions) {
            String type = txn.getType();
            if (("TRANSFER".equals(type) || "CASH_OUT".equals(type)) && txn.getAmount() > HIGH_VALUE_THRESHOLD) {
                AnomalyResult.Severity severity = txn.getAmount() > 200000
                        ? AnomalyResult.Severity.HIGH : AnomalyResult.Severity.MEDIUM;
                List<Integer> ids = new ArrayList<Integer>();
                ids.add(txn.getTransactionId());
                anomalies.add(new AnomalyResult(
                        txn.getNameOrig(),
                        AnomalyResult.AnomalyType.HIGH_VALUE_TRANSFER_OR_CASHOUT,
                        severity,
                        ids,
                        String.format("High-value %s of %.2f by %s (txn %d)",
                                type, txn.getAmount(), txn.getNameOrig(), txn.getTransactionId())
                ));
            }
        }
        return anomalies;
    }

    /**
     * Detect transactions where the origin account is fully drained to zero.
     * This is a common fraud indicator, especially for TRANSFER and CASH_OUT types.
     */
    public List<AnomalyResult> detectAccountDrain(List<Transaction> transactions) {
        List<AnomalyResult> anomalies = new ArrayList<AnomalyResult>();
        for (Transaction txn : transactions) {
            if (txn.getOldbalanceOrg() > 0 && txn.getNewbalanceOrig() == 0) {
                AnomalyResult.Severity severity;
                if ("TRANSFER".equals(txn.getType()) || "CASH_OUT".equals(txn.getType())) {
                    severity = AnomalyResult.Severity.HIGH;
                } else {
                    severity = AnomalyResult.Severity.MEDIUM;
                }
                List<Integer> ids = new ArrayList<Integer>();
                ids.add(txn.getTransactionId());
                anomalies.add(new AnomalyResult(
                        txn.getNameOrig(),
                        AnomalyResult.AnomalyType.ACCOUNT_DRAIN,
                        severity,
                        ids,
                        String.format("Account %s fully drained: %.2f -> 0 via %s (txn %d, amount=%.2f)",
                                txn.getNameOrig(), txn.getOldbalanceOrg(), txn.getType(),
                                txn.getTransactionId(), txn.getAmount())
                ));
            }
        }
        return anomalies;
    }

    /**
     * Detect cross-account TRANSFER to CASH_OUT patterns.
     * Flags when a TRANSFER sends money to an account that also originates a CASH_OUT
     * in the same time step, indicating potential layering across accounts.
     */
    public List<AnomalyResult> detectCrossAccountTransferCashout(List<Transaction> transactions) {
        List<AnomalyResult> anomalies = new ArrayList<AnomalyResult>();

        // Build a set of accounts that originate CASH_OUT transactions, keyed by step
        Map<Integer, Set<String>> cashoutOriginsByStep = new HashMap<Integer, Set<String>>();
        Map<String, List<Transaction>> cashoutTxnsByOrig = new HashMap<String, List<Transaction>>();
        for (Transaction txn : transactions) {
            if ("CASH_OUT".equals(txn.getType())) {
                int step = txn.getStep();
                if (!cashoutOriginsByStep.containsKey(step)) {
                    cashoutOriginsByStep.put(step, new HashSet<String>());
                }
                cashoutOriginsByStep.get(step).add(txn.getNameOrig());

                if (!cashoutTxnsByOrig.containsKey(txn.getNameOrig())) {
                    cashoutTxnsByOrig.put(txn.getNameOrig(), new ArrayList<Transaction>());
                }
                cashoutTxnsByOrig.get(txn.getNameOrig()).add(txn);
            }
        }

        // Check TRANSFERs whose destination matches a CASH_OUT origin in the same step
        for (Transaction txn : transactions) {
            if ("TRANSFER".equals(txn.getType())) {
                int step = txn.getStep();
                Set<String> cashoutOriginsInStep = cashoutOriginsByStep.get(step);
                if (cashoutOriginsInStep != null && cashoutOriginsInStep.contains(txn.getNameDest())) {
                    List<Transaction> matchingCashouts = cashoutTxnsByOrig.get(txn.getNameDest());
                    if (matchingCashouts != null) {
                        for (Transaction co : matchingCashouts) {
                            if (co.getStep() == step) {
                                List<Integer> ids = new ArrayList<Integer>();
                                ids.add(txn.getTransactionId());
                                ids.add(co.getTransactionId());
                                anomalies.add(new AnomalyResult(
                                        txn.getNameOrig(),
                                        AnomalyResult.AnomalyType.CROSS_ACCOUNT_TRANSFER_CASHOUT,
                                        AnomalyResult.Severity.HIGH,
                                        ids,
                                        String.format("Cross-account pattern: %s TRANSFER (%.2f) to %s, who CASH_OUT (%.2f) in same step %d",
                                                txn.getNameOrig(), txn.getAmount(), txn.getNameDest(),
                                                co.getAmount(), step)
                                ));
                                break;
                            }
                        }
                    }
                }
            }
        }
        return anomalies;
    }

    /**
     * Detect transactions originating from accounts with zero balance.
     * Transacting from a zero-balance account is inherently suspicious.
     */
    public List<AnomalyResult> detectZeroBalanceOrigin(List<Transaction> transactions) {
        List<AnomalyResult> anomalies = new ArrayList<AnomalyResult>();
        for (Transaction txn : transactions) {
            if (txn.getOldbalanceOrg() == 0 && txn.getAmount() > 0
                    && ("TRANSFER".equals(txn.getType()) || "CASH_OUT".equals(txn.getType()))) {
                List<Integer> ids = new ArrayList<Integer>();
                ids.add(txn.getTransactionId());
                anomalies.add(new AnomalyResult(
                        txn.getNameOrig(),
                        AnomalyResult.AnomalyType.ZERO_BALANCE_ORIGIN,
                        AnomalyResult.Severity.HIGH,
                        ids,
                        String.format("%s of %.2f from zero-balance account %s (txn %d)",
                                txn.getType(), txn.getAmount(), txn.getNameOrig(), txn.getTransactionId())
                ));
            }
        }
        return anomalies;
    }

    /**
     * Run all anomaly detection rules across the dataset.
     * Includes both per-customer sequence analysis and cross-account/individual
     * transaction anomaly detection.
     *
     * @param transactions the full list of transactions loaded from CSV
     * @return list of all detected anomalies
     */
    public List<AnomalyResult> analyzeAll(List<Transaction> transactions) {
        Map<String, List<Transaction>> grouped = groupByCustomer(transactions);
        List<AnomalyResult> allAnomalies = new ArrayList<AnomalyResult>();

        // Per-customer sequence-based detection
        for (Map.Entry<String, List<Transaction>> entry : grouped.entrySet()) {
            String customerId = entry.getKey();
            List<Transaction> customerTxns = entry.getValue();

            allAnomalies.addAll(detectRepeatedHighValue(customerId, customerTxns));
            allAnomalies.addAll(detectTransferThenCashout(customerId, customerTxns));
            allAnomalies.addAll(detectSuddenAmountIncrease(customerId, customerTxns));
            allAnomalies.addAll(detectTransferTransferCashout(customerId, customerTxns));
            allAnomalies.addAll(detectRapidHighValueSequence(customerId, customerTxns));
            allAnomalies.addAll(detectSuddenTypeChange(customerId, customerTxns));
        }

        // Individual transaction anomaly detection
        allAnomalies.addAll(detectHighValueTransferOrCashout(transactions));
        allAnomalies.addAll(detectAccountDrain(transactions));
        allAnomalies.addAll(detectZeroBalanceOrigin(transactions));

        // Cross-account pattern detection
        allAnomalies.addAll(detectCrossAccountTransferCashout(transactions));

        // Sort by severity (HIGH first) then by anomaly type for consistent ordering
        Collections.sort(allAnomalies, new Comparator<AnomalyResult>() {
            @Override
            public int compare(AnomalyResult a, AnomalyResult b) {
                int sevCmp = Integer.compare(severityOrder(b.getSeverity()), severityOrder(a.getSeverity()));
                if (sevCmp != 0) return sevCmp;
                return a.getAnomalyType().compareTo(b.getAnomalyType());
            }
        });

        // Limit to MAX_REPORT_ROWS to keep the report concise
        if (allAnomalies.size() > MAX_REPORT_ROWS) {
            allAnomalies = new ArrayList<AnomalyResult>(allAnomalies.subList(0, MAX_REPORT_ROWS));
        }

        return allAnomalies;
    }

    private int severityOrder(AnomalyResult.Severity severity) {
        switch (severity) {
            case HIGH: return 3;
            case MEDIUM: return 2;
            case LOW: return 1;
            default: return 0;
        }
    }

    /**
     * Save the anomaly detection results to a CSV file.
     */
    public void saveAnomalyReport(List<AnomalyResult> anomalies, String outputPath) throws IOException {
        try (CSVWriter writer = new CSVWriter(new FileWriter(outputPath))) {
            writer.writeNext(new String[]{"customer_id", "anomaly_type", "severity",
                    "affected_transaction_ids", "description"});
            for (AnomalyResult anomaly : anomalies) {
                StringBuilder idsStr = new StringBuilder();
                for (int j = 0; j < anomaly.getAffectedTransactionIds().size(); j++) {
                    if (j > 0) idsStr.append(";");
                    idsStr.append(anomaly.getAffectedTransactionIds().get(j));
                }
                writer.writeNext(new String[]{
                        anomaly.getCustomerId(),
                        anomaly.getAnomalyType().name(),
                        anomaly.getSeverity().name(),
                        idsStr.toString(),
                        anomaly.getDescription()
                });
            }
        }
    }

    /**
     * Print a summary of detected anomalies to standard output.
     */
    public void printSummary(List<AnomalyResult> anomalies) {
        System.out.println("\n=== Anomalous Transaction Sequence Report ===");
        System.out.printf("Total anomalies detected: %d%n%n", anomalies.size());

        // Count by type
        Map<AnomalyResult.AnomalyType, Integer> typeCounts = new HashMap<AnomalyResult.AnomalyType, Integer>();
        Map<AnomalyResult.Severity, Integer> severityCounts = new HashMap<AnomalyResult.Severity, Integer>();
        for (AnomalyResult a : anomalies) {
            if (!typeCounts.containsKey(a.getAnomalyType())) {
                typeCounts.put(a.getAnomalyType(), 0);
            }
            typeCounts.put(a.getAnomalyType(), typeCounts.get(a.getAnomalyType()) + 1);

            if (!severityCounts.containsKey(a.getSeverity())) {
                severityCounts.put(a.getSeverity(), 0);
            }
            severityCounts.put(a.getSeverity(), severityCounts.get(a.getSeverity()) + 1);
        }

        System.out.println("Anomalies by type:");
        for (Map.Entry<AnomalyResult.AnomalyType, Integer> entry : typeCounts.entrySet()) {
            System.out.printf("  %s: %d%n", entry.getKey(), entry.getValue());
        }

        System.out.println("\nAnomalies by severity:");
        for (Map.Entry<AnomalyResult.Severity, Integer> entry : severityCounts.entrySet()) {
            System.out.printf("  %s: %d%n", entry.getKey(), entry.getValue());
        }

        System.out.println("\nDetailed anomalies:");
        for (AnomalyResult a : anomalies) {
            System.out.printf("  [%s] %s - %s%n", a.getSeverity(), a.getAnomalyType(), a.getDescription());
        }
    }

    private boolean isLowRiskType(String type) {
        return "PAYMENT".equals(type) || "CASH_IN".equals(type);
    }

    private boolean isHighRiskType(String type) {
        return "TRANSFER".equals(type) || "CASH_OUT".equals(type);
    }

    /**
     * Main entry point for the anomalous transaction sequence detector.
     * Loads Example1.csv, runs all detection rules, prints summary, and saves report.
     */
    public static void main(String[] args) {
        String inputPath = "data/Example1.csv";
        String outputPath = "data/anomaly_report.csv";

        FraudRiskScoring loader = new FraudRiskScoring();
        TransactionSequenceAnalyzer analyzer = new TransactionSequenceAnalyzer();

        try {
            System.out.printf("Loading dataset from '%s'...%n", inputPath);
            List<Transaction> transactions = loader.loadDataset(inputPath);
            System.out.printf("Loaded %d transactions.%n", transactions.size());

            System.out.println("\nAnalyzing transaction sequences for anomalies...");
            List<AnomalyResult> anomalies = analyzer.analyzeAll(transactions);

            analyzer.printSummary(anomalies);

            analyzer.saveAnomalyReport(anomalies, outputPath);
            System.out.printf("%nAnomaly report saved to '%s'%n", outputPath);

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
