package com.fraudrisk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TransactionSequenceAnalyzerTest {

    private TransactionSequenceAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new TransactionSequenceAnalyzer();
    }

    // --- Helper method ---

    private Transaction createTransaction(int id, int step, String type, double amount,
                                          String nameOrig, double oldBalOrg, double newBalOrig,
                                          String nameDest, double oldBalDest, double newBalDest,
                                          int isFraud, int isFlaggedFraud) {
        Transaction txn = new Transaction();
        txn.setTransactionId(id);
        txn.setStep(step);
        txn.setType(type);
        txn.setAmount(amount);
        txn.setNameOrig(nameOrig);
        txn.setOldbalanceOrg(oldBalOrg);
        txn.setNewbalanceOrig(newBalOrig);
        txn.setNameDest(nameDest);
        txn.setOldbalanceDest(oldBalDest);
        txn.setNewbalanceDest(newBalDest);
        txn.setIsFraud(isFraud);
        txn.setIsFlaggedFraud(isFlaggedFraud);
        return txn;
    }

    // --- groupByCustomer tests ---

    @Test
    void testGroupByCustomerSingleCustomer() {
        List<Transaction> transactions = new ArrayList<Transaction>();
        transactions.add(createTransaction(0, 1, "PAYMENT", 1000, "C1", 5000, 4000, "M1", 0, 0, 0, 0));
        transactions.add(createTransaction(1, 2, "TRANSFER", 2000, "C1", 4000, 2000, "C2", 0, 2000, 0, 0));

        Map<String, List<Transaction>> grouped = analyzer.groupByCustomer(transactions);
        assertEquals(1, grouped.size());
        assertTrue(grouped.containsKey("C1"));
        assertEquals(2, grouped.get("C1").size());
    }

    @Test
    void testGroupByCustomerMultipleCustomers() {
        List<Transaction> transactions = new ArrayList<Transaction>();
        transactions.add(createTransaction(0, 1, "PAYMENT", 1000, "C1", 5000, 4000, "M1", 0, 0, 0, 0));
        transactions.add(createTransaction(1, 1, "TRANSFER", 2000, "C2", 4000, 2000, "C3", 0, 2000, 0, 0));
        transactions.add(createTransaction(2, 2, "CASH_OUT", 1500, "C1", 4000, 2500, "C4", 0, 1500, 0, 0));

        Map<String, List<Transaction>> grouped = analyzer.groupByCustomer(transactions);
        assertEquals(2, grouped.size());
        assertEquals(2, grouped.get("C1").size());
        assertEquals(1, grouped.get("C2").size());
    }

    @Test
    void testGroupByCustomerSortsByStep() {
        List<Transaction> transactions = new ArrayList<Transaction>();
        transactions.add(createTransaction(0, 3, "PAYMENT", 1000, "C1", 5000, 4000, "M1", 0, 0, 0, 0));
        transactions.add(createTransaction(1, 1, "TRANSFER", 2000, "C1", 4000, 2000, "C2", 0, 2000, 0, 0));
        transactions.add(createTransaction(2, 2, "CASH_OUT", 1500, "C1", 2000, 500, "C3", 0, 1500, 0, 0));

        Map<String, List<Transaction>> grouped = analyzer.groupByCustomer(transactions);
        List<Transaction> sorted = grouped.get("C1");
        assertEquals(1, sorted.get(0).getStep());
        assertEquals(2, sorted.get(1).getStep());
        assertEquals(3, sorted.get(2).getStep());
    }

    @Test
    void testGroupByCustomerEmptyList() {
        List<Transaction> transactions = new ArrayList<Transaction>();
        Map<String, List<Transaction>> grouped = analyzer.groupByCustomer(transactions);
        assertTrue(grouped.isEmpty());
    }

    // --- detectRepeatedHighValue tests ---

    @Test
    void testDetectRepeatedHighValueConsecutive() {
        List<Transaction> txns = new ArrayList<Transaction>();
        txns.add(createTransaction(0, 1, "TRANSFER", 50000, "C1", 100000, 50000, "C2", 0, 50000, 0, 0));
        txns.add(createTransaction(1, 2, "TRANSFER", 60000, "C1", 50000, 0, "C3", 0, 60000, 0, 0));

        List<AnomalyResult> anomalies = analyzer.detectRepeatedHighValue("C1", txns);
        assertEquals(1, anomalies.size());
        assertEquals(AnomalyResult.AnomalyType.REPEATED_HIGH_VALUE, anomalies.get(0).getAnomalyType());
        assertEquals(AnomalyResult.Severity.HIGH, anomalies.get(0).getSeverity());
        assertEquals(2, anomalies.get(0).getAffectedTransactionIds().size());
    }

    @Test
    void testDetectRepeatedHighValueNoConsecutive() {
        List<Transaction> txns = new ArrayList<Transaction>();
        txns.add(createTransaction(0, 1, "TRANSFER", 50000, "C1", 100000, 50000, "C2", 0, 50000, 0, 0));
        txns.add(createTransaction(1, 2, "PAYMENT", 100, "C1", 50000, 49900, "M1", 0, 0, 0, 0));
        txns.add(createTransaction(2, 3, "TRANSFER", 40000, "C1", 49900, 9900, "C3", 0, 40000, 0, 0));

        List<AnomalyResult> anomalies = analyzer.detectRepeatedHighValue("C1", txns);
        assertEquals(0, anomalies.size());
    }

    @Test
    void testDetectRepeatedHighValueBelowThreshold() {
        List<Transaction> txns = new ArrayList<Transaction>();
        txns.add(createTransaction(0, 1, "PAYMENT", 5000, "C1", 10000, 5000, "M1", 0, 0, 0, 0));
        txns.add(createTransaction(1, 2, "PAYMENT", 3000, "C1", 5000, 2000, "M2", 0, 0, 0, 0));

        List<AnomalyResult> anomalies = analyzer.detectRepeatedHighValue("C1", txns);
        assertEquals(0, anomalies.size());
    }

    @Test
    void testDetectRepeatedHighValueThreeConsecutive() {
        List<Transaction> txns = new ArrayList<Transaction>();
        txns.add(createTransaction(0, 1, "TRANSFER", 20000, "C1", 100000, 80000, "C2", 0, 20000, 0, 0));
        txns.add(createTransaction(1, 2, "TRANSFER", 30000, "C1", 80000, 50000, "C3", 0, 30000, 0, 0));
        txns.add(createTransaction(2, 3, "CASH_OUT", 50000, "C1", 50000, 0, "C4", 0, 50000, 0, 0));

        List<AnomalyResult> anomalies = analyzer.detectRepeatedHighValue("C1", txns);
        assertEquals(1, anomalies.size());
        assertEquals(3, anomalies.get(0).getAffectedTransactionIds().size());
    }

    // --- detectTransferThenCashout tests ---

    @Test
    void testDetectTransferThenCashout() {
        List<Transaction> txns = new ArrayList<Transaction>();
        txns.add(createTransaction(0, 1, "TRANSFER", 50000, "C1", 100000, 50000, "C2", 0, 50000, 0, 0));
        txns.add(createTransaction(1, 2, "CASH_OUT", 30000, "C1", 50000, 20000, "C3", 0, 30000, 0, 0));

        List<AnomalyResult> anomalies = analyzer.detectTransferThenCashout("C1", txns);
        assertEquals(1, anomalies.size());
        assertEquals(AnomalyResult.AnomalyType.TRANSFER_THEN_CASHOUT, anomalies.get(0).getAnomalyType());
        assertEquals(AnomalyResult.Severity.HIGH, anomalies.get(0).getSeverity());
    }

    @Test
    void testDetectTransferThenCashoutNoPattern() {
        List<Transaction> txns = new ArrayList<Transaction>();
        txns.add(createTransaction(0, 1, "PAYMENT", 1000, "C1", 5000, 4000, "M1", 0, 0, 0, 0));
        txns.add(createTransaction(1, 2, "TRANSFER", 2000, "C1", 4000, 2000, "C2", 0, 2000, 0, 0));

        List<AnomalyResult> anomalies = analyzer.detectTransferThenCashout("C1", txns);
        assertEquals(0, anomalies.size());
    }

    @Test
    void testDetectTransferThenCashoutReversed() {
        // CASH_OUT followed by TRANSFER should NOT trigger
        List<Transaction> txns = new ArrayList<Transaction>();
        txns.add(createTransaction(0, 1, "CASH_OUT", 5000, "C1", 10000, 5000, "C2", 0, 5000, 0, 0));
        txns.add(createTransaction(1, 2, "TRANSFER", 3000, "C1", 5000, 2000, "C3", 0, 3000, 0, 0));

        List<AnomalyResult> anomalies = analyzer.detectTransferThenCashout("C1", txns);
        assertEquals(0, anomalies.size());
    }

    @Test
    void testDetectTransferThenCashoutMultipleOccurrences() {
        List<Transaction> txns = new ArrayList<Transaction>();
        txns.add(createTransaction(0, 1, "TRANSFER", 10000, "C1", 50000, 40000, "C2", 0, 10000, 0, 0));
        txns.add(createTransaction(1, 2, "CASH_OUT", 5000, "C1", 40000, 35000, "C3", 0, 5000, 0, 0));
        txns.add(createTransaction(2, 3, "TRANSFER", 20000, "C1", 35000, 15000, "C4", 0, 20000, 0, 0));
        txns.add(createTransaction(3, 4, "CASH_OUT", 15000, "C1", 15000, 0, "C5", 0, 15000, 0, 0));

        List<AnomalyResult> anomalies = analyzer.detectTransferThenCashout("C1", txns);
        assertEquals(2, anomalies.size());
    }

    // --- detectSuddenAmountIncrease tests ---

    @Test
    void testDetectSuddenAmountIncreaseSpike() {
        List<Transaction> txns = new ArrayList<Transaction>();
        txns.add(createTransaction(0, 1, "PAYMENT", 100, "C1", 5000, 4900, "M1", 0, 0, 0, 0));
        txns.add(createTransaction(1, 2, "PAYMENT", 150, "C1", 4900, 4750, "M2", 0, 0, 0, 0));
        txns.add(createTransaction(2, 3, "TRANSFER", 50000, "C1", 4750, 0, "C2", 0, 50000, 0, 0));

        List<AnomalyResult> anomalies = analyzer.detectSuddenAmountIncrease("C1", txns);
        assertEquals(1, anomalies.size());
        assertEquals(AnomalyResult.AnomalyType.SUDDEN_AMOUNT_INCREASE, anomalies.get(0).getAnomalyType());
        assertEquals(AnomalyResult.Severity.MEDIUM, anomalies.get(0).getSeverity());
        assertEquals(2, anomalies.get(0).getAffectedTransactionIds().get(0).intValue());
    }

    @Test
    void testDetectSuddenAmountIncreaseNoSpike() {
        List<Transaction> txns = new ArrayList<Transaction>();
        txns.add(createTransaction(0, 1, "PAYMENT", 1000, "C1", 5000, 4000, "M1", 0, 0, 0, 0));
        txns.add(createTransaction(1, 2, "PAYMENT", 1200, "C1", 4000, 2800, "M2", 0, 0, 0, 0));
        txns.add(createTransaction(2, 3, "PAYMENT", 800, "C1", 2800, 2000, "M3", 0, 0, 0, 0));

        List<AnomalyResult> anomalies = analyzer.detectSuddenAmountIncrease("C1", txns);
        assertEquals(0, anomalies.size());
    }

    @Test
    void testDetectSuddenAmountIncreaseSingleTransaction() {
        List<Transaction> txns = new ArrayList<Transaction>();
        txns.add(createTransaction(0, 1, "PAYMENT", 1000, "C1", 5000, 4000, "M1", 0, 0, 0, 0));

        List<AnomalyResult> anomalies = analyzer.detectSuddenAmountIncrease("C1", txns);
        assertEquals(0, anomalies.size());
    }

    // --- detectTransferTransferCashout tests ---

    @Test
    void testDetectTransferTransferCashout() {
        List<Transaction> txns = new ArrayList<Transaction>();
        txns.add(createTransaction(0, 1, "TRANSFER", 10000, "C1", 50000, 40000, "C2", 0, 10000, 0, 0));
        txns.add(createTransaction(1, 2, "TRANSFER", 20000, "C1", 40000, 20000, "C3", 0, 20000, 0, 0));
        txns.add(createTransaction(2, 3, "CASH_OUT", 20000, "C1", 20000, 0, "C4", 0, 20000, 0, 0));

        List<AnomalyResult> anomalies = analyzer.detectTransferTransferCashout("C1", txns);
        assertEquals(1, anomalies.size());
        assertEquals(AnomalyResult.AnomalyType.TRANSFER_TRANSFER_CASHOUT, anomalies.get(0).getAnomalyType());
        assertEquals(AnomalyResult.Severity.HIGH, anomalies.get(0).getSeverity());
        assertEquals(3, anomalies.get(0).getAffectedTransactionIds().size());
    }

    @Test
    void testDetectTransferTransferCashoutNoPattern() {
        List<Transaction> txns = new ArrayList<Transaction>();
        txns.add(createTransaction(0, 1, "TRANSFER", 10000, "C1", 50000, 40000, "C2", 0, 10000, 0, 0));
        txns.add(createTransaction(1, 2, "PAYMENT", 500, "C1", 40000, 39500, "M1", 0, 0, 0, 0));
        txns.add(createTransaction(2, 3, "CASH_OUT", 20000, "C1", 39500, 19500, "C4", 0, 20000, 0, 0));

        List<AnomalyResult> anomalies = analyzer.detectTransferTransferCashout("C1", txns);
        assertEquals(0, anomalies.size());
    }

    @Test
    void testDetectTransferTransferCashoutTooFewTransactions() {
        List<Transaction> txns = new ArrayList<Transaction>();
        txns.add(createTransaction(0, 1, "TRANSFER", 10000, "C1", 50000, 40000, "C2", 0, 10000, 0, 0));
        txns.add(createTransaction(1, 2, "TRANSFER", 20000, "C1", 40000, 20000, "C3", 0, 20000, 0, 0));

        List<AnomalyResult> anomalies = analyzer.detectTransferTransferCashout("C1", txns);
        assertEquals(0, anomalies.size());
    }

    // --- detectRapidHighValueSequence tests ---

    @Test
    void testDetectRapidHighValueSequenceWithinWindow() {
        List<Transaction> txns = new ArrayList<Transaction>();
        txns.add(createTransaction(0, 1, "TRANSFER", 50000, "C1", 200000, 150000, "C2", 0, 50000, 0, 0));
        txns.add(createTransaction(1, 2, "CASH_OUT", 60000, "C1", 150000, 90000, "C3", 0, 60000, 0, 0));

        List<AnomalyResult> anomalies = analyzer.detectRapidHighValueSequence("C1", txns);
        assertEquals(1, anomalies.size());
        assertEquals(AnomalyResult.AnomalyType.RAPID_HIGH_VALUE_SEQUENCE, anomalies.get(0).getAnomalyType());
        assertEquals(AnomalyResult.Severity.HIGH, anomalies.get(0).getSeverity());
    }

    @Test
    void testDetectRapidHighValueSequenceOutsideWindow() {
        List<Transaction> txns = new ArrayList<Transaction>();
        txns.add(createTransaction(0, 1, "TRANSFER", 50000, "C1", 200000, 150000, "C2", 0, 50000, 0, 0));
        txns.add(createTransaction(1, 10, "CASH_OUT", 60000, "C1", 150000, 90000, "C3", 0, 60000, 0, 0));

        List<AnomalyResult> anomalies = analyzer.detectRapidHighValueSequence("C1", txns);
        assertEquals(0, anomalies.size());
    }

    @Test
    void testDetectRapidHighValueSequenceOnlyOneHighValue() {
        List<Transaction> txns = new ArrayList<Transaction>();
        txns.add(createTransaction(0, 1, "TRANSFER", 50000, "C1", 200000, 150000, "C2", 0, 50000, 0, 0));
        txns.add(createTransaction(1, 2, "PAYMENT", 100, "C1", 150000, 149900, "M1", 0, 0, 0, 0));

        List<AnomalyResult> anomalies = analyzer.detectRapidHighValueSequence("C1", txns);
        assertEquals(0, anomalies.size());
    }

    @Test
    void testDetectRapidHighValueSequenceSingleTransaction() {
        List<Transaction> txns = new ArrayList<Transaction>();
        txns.add(createTransaction(0, 1, "TRANSFER", 50000, "C1", 200000, 150000, "C2", 0, 50000, 0, 0));

        List<AnomalyResult> anomalies = analyzer.detectRapidHighValueSequence("C1", txns);
        assertEquals(0, anomalies.size());
    }

    // --- detectSuddenTypeChange tests ---

    @Test
    void testDetectSuddenTypeChangePaymentToTransfer() {
        List<Transaction> txns = new ArrayList<Transaction>();
        txns.add(createTransaction(0, 1, "PAYMENT", 1000, "C1", 5000, 4000, "M1", 0, 0, 0, 0));
        txns.add(createTransaction(1, 2, "TRANSFER", 3000, "C1", 4000, 1000, "C2", 0, 3000, 0, 0));

        List<AnomalyResult> anomalies = analyzer.detectSuddenTypeChange("C1", txns);
        assertEquals(1, anomalies.size());
        assertEquals(AnomalyResult.AnomalyType.SUDDEN_TYPE_CHANGE, anomalies.get(0).getAnomalyType());
        assertEquals(AnomalyResult.Severity.MEDIUM, anomalies.get(0).getSeverity());
    }

    @Test
    void testDetectSuddenTypeChangePaymentToCashout() {
        List<Transaction> txns = new ArrayList<Transaction>();
        txns.add(createTransaction(0, 1, "PAYMENT", 1000, "C1", 5000, 4000, "M1", 0, 0, 0, 0));
        txns.add(createTransaction(1, 2, "CASH_OUT", 3000, "C1", 4000, 1000, "C2", 0, 3000, 0, 0));

        List<AnomalyResult> anomalies = analyzer.detectSuddenTypeChange("C1", txns);
        assertEquals(1, anomalies.size());
    }

    @Test
    void testDetectSuddenTypeChangeNoChange() {
        List<Transaction> txns = new ArrayList<Transaction>();
        txns.add(createTransaction(0, 1, "TRANSFER", 1000, "C1", 5000, 4000, "C2", 0, 1000, 0, 0));
        txns.add(createTransaction(1, 2, "CASH_OUT", 3000, "C1", 4000, 1000, "C3", 0, 3000, 0, 0));

        // TRANSFER -> CASH_OUT: TRANSFER is high-risk, not low-risk, so no SUDDEN_TYPE_CHANGE
        List<AnomalyResult> anomalies = analyzer.detectSuddenTypeChange("C1", txns);
        assertEquals(0, anomalies.size());
    }

    @Test
    void testDetectSuddenTypeChangeCashInToTransfer() {
        List<Transaction> txns = new ArrayList<Transaction>();
        txns.add(createTransaction(0, 1, "CASH_IN", 5000, "C1", 0, 5000, "C2", 10000, 5000, 0, 0));
        txns.add(createTransaction(1, 2, "TRANSFER", 4000, "C1", 5000, 1000, "C3", 0, 4000, 0, 0));

        List<AnomalyResult> anomalies = analyzer.detectSuddenTypeChange("C1", txns);
        assertEquals(1, anomalies.size());
    }

    // --- analyzeAll tests ---

    @Test
    void testAnalyzeAllIntegration() {
        List<Transaction> transactions = new ArrayList<Transaction>();
        // Customer C1: PAYMENT -> TRANSFER -> CASH_OUT (has transfer-then-cashout + sudden type change)
        transactions.add(createTransaction(0, 1, "PAYMENT", 1000, "C1", 100000, 99000, "M1", 0, 0, 0, 0));
        transactions.add(createTransaction(1, 2, "TRANSFER", 50000, "C1", 99000, 49000, "C2", 0, 50000, 0, 0));
        transactions.add(createTransaction(2, 3, "CASH_OUT", 49000, "C1", 49000, 0, "C3", 0, 49000, 0, 0));

        List<AnomalyResult> anomalies = analyzer.analyzeAll(transactions);
        assertFalse(anomalies.isEmpty());

        // Should detect at least: transfer-then-cashout, sudden type change, sudden amount increase
        boolean hasTransferCashout = false;
        boolean hasSuddenTypeChange = false;
        for (AnomalyResult a : anomalies) {
            if (a.getAnomalyType() == AnomalyResult.AnomalyType.TRANSFER_THEN_CASHOUT) {
                hasTransferCashout = true;
            }
            if (a.getAnomalyType() == AnomalyResult.AnomalyType.SUDDEN_TYPE_CHANGE) {
                hasSuddenTypeChange = true;
            }
        }
        assertTrue(hasTransferCashout, "Should detect TRANSFER->CASH_OUT pattern");
        assertTrue(hasSuddenTypeChange, "Should detect sudden type change from PAYMENT to TRANSFER");
    }

    @Test
    void testAnalyzeAllNoAnomalies() {
        List<Transaction> transactions = new ArrayList<Transaction>();
        // Single low-value payment per customer
        transactions.add(createTransaction(0, 1, "PAYMENT", 500, "C1", 5000, 4500, "M1", 0, 0, 0, 0));
        transactions.add(createTransaction(1, 1, "PAYMENT", 300, "C2", 3000, 2700, "M2", 0, 0, 0, 0));

        List<AnomalyResult> anomalies = analyzer.analyzeAll(transactions);
        assertEquals(0, anomalies.size());
    }

    @Test
    void testAnalyzeAllTransferTransferCashout() {
        List<Transaction> transactions = new ArrayList<Transaction>();
        transactions.add(createTransaction(0, 1, "TRANSFER", 20000, "C1", 100000, 80000, "C2", 0, 20000, 0, 0));
        transactions.add(createTransaction(1, 2, "TRANSFER", 30000, "C1", 80000, 50000, "C3", 0, 30000, 0, 0));
        transactions.add(createTransaction(2, 3, "CASH_OUT", 50000, "C1", 50000, 0, "C4", 0, 50000, 0, 0));

        List<AnomalyResult> anomalies = analyzer.analyzeAll(transactions);

        boolean hasTriplePattern = false;
        for (AnomalyResult a : anomalies) {
            if (a.getAnomalyType() == AnomalyResult.AnomalyType.TRANSFER_TRANSFER_CASHOUT) {
                hasTriplePattern = true;
            }
        }
        assertTrue(hasTriplePattern, "Should detect TRANSFER->TRANSFER->CASH_OUT pattern");
    }

    @Test
    void testAnalyzeAllEmptyList() {
        List<Transaction> transactions = new ArrayList<Transaction>();
        List<AnomalyResult> anomalies = analyzer.analyzeAll(transactions);
        assertEquals(0, anomalies.size());
    }

    // --- AnomalyResult model tests ---

    @Test
    void testAnomalyResultGetters() {
        List<Integer> ids = new ArrayList<Integer>();
        ids.add(1);
        ids.add(2);
        AnomalyResult result = new AnomalyResult("C1",
                AnomalyResult.AnomalyType.TRANSFER_THEN_CASHOUT,
                AnomalyResult.Severity.HIGH,
                ids,
                "Test description");

        assertEquals("C1", result.getCustomerId());
        assertEquals(AnomalyResult.AnomalyType.TRANSFER_THEN_CASHOUT, result.getAnomalyType());
        assertEquals(AnomalyResult.Severity.HIGH, result.getSeverity());
        assertEquals(2, result.getAffectedTransactionIds().size());
        assertEquals("Test description", result.getDescription());
    }

    @Test
    void testAnomalyResultToString() {
        List<Integer> ids = new ArrayList<Integer>();
        ids.add(5);
        AnomalyResult result = new AnomalyResult("C99",
                AnomalyResult.AnomalyType.SUDDEN_AMOUNT_INCREASE,
                AnomalyResult.Severity.MEDIUM,
                ids,
                "Spike detected");

        String str = result.toString();
        assertTrue(str.contains("C99"));
        assertTrue(str.contains("SUDDEN_AMOUNT_INCREASE"));
        assertTrue(str.contains("MEDIUM"));
    }

    @Test
    void testAnomalyResultDefensiveCopy() {
        List<Integer> ids = new ArrayList<Integer>();
        ids.add(1);
        AnomalyResult result = new AnomalyResult("C1",
                AnomalyResult.AnomalyType.REPEATED_HIGH_VALUE,
                AnomalyResult.Severity.HIGH,
                ids,
                "Test");

        // Modify original list
        ids.add(99);
        // AnomalyResult should not be affected
        assertEquals(1, result.getAffectedTransactionIds().size());

        // Modify returned list
        result.getAffectedTransactionIds().add(88);
        // Internal list should not be affected
        assertEquals(1, result.getAffectedTransactionIds().size());
    }

    // --- saveAnomalyReport tests ---

    @Test
    void testSaveAnomalyReport(@TempDir Path tempDir) throws Exception {
        List<AnomalyResult> anomalies = new ArrayList<AnomalyResult>();
        List<Integer> ids1 = new ArrayList<Integer>();
        ids1.add(0);
        ids1.add(1);
        anomalies.add(new AnomalyResult("C1",
                AnomalyResult.AnomalyType.TRANSFER_THEN_CASHOUT,
                AnomalyResult.Severity.HIGH,
                ids1,
                "TRANSFER followed by CASH_OUT"));

        List<Integer> ids2 = new ArrayList<Integer>();
        ids2.add(5);
        anomalies.add(new AnomalyResult("C2",
                AnomalyResult.AnomalyType.SUDDEN_AMOUNT_INCREASE,
                AnomalyResult.Severity.MEDIUM,
                ids2,
                "Amount spike detected"));

        Path outputFile = tempDir.resolve("anomalies.csv");
        analyzer.saveAnomalyReport(anomalies, outputFile.toString());

        assertTrue(outputFile.toFile().exists());
        assertTrue(outputFile.toFile().length() > 0);
    }

    // --- Full pipeline with CSV loading ---

    @Test
    void testFullPipelineWithCsv(@TempDir Path tempDir) throws Exception {
        Path csvFile = tempDir.resolve("test_transactions.csv");
        try (FileWriter fw = new FileWriter(csvFile.toFile())) {
            fw.write("step,type,amount,nameOrig,oldbalanceOrg,newbalanceOrig,nameDest,oldbalanceDest,newbalanceDest,isFraud,isFlaggedFraud\n");
            // Customer C1: PAYMENT(small) -> TRANSFER(large) -> CASH_OUT(large)
            fw.write("1,PAYMENT,500,C1,100000,99500,M1,0,0,0,0\n");
            fw.write("2,TRANSFER,80000,C1,99500,19500,C2,0,80000,0,0\n");
            fw.write("3,CASH_OUT,19500,C1,19500,0,C3,0,19500,0,0\n");
            // Customer C4: normal payments
            fw.write("1,PAYMENT,200,C4,5000,4800,M5,0,0,0,0\n");
        }

        FraudRiskScoring loader = new FraudRiskScoring();
        List<Transaction> transactions = loader.loadDataset(csvFile.toString());
        assertEquals(4, transactions.size());

        List<AnomalyResult> anomalies = analyzer.analyzeAll(transactions);
        // C1 should have anomalies, C4 should not
        boolean c1HasAnomaly = false;
        boolean c4HasAnomaly = false;
        for (AnomalyResult a : anomalies) {
            if ("C1".equals(a.getCustomerId())) c1HasAnomaly = true;
            if ("C4".equals(a.getCustomerId())) c4HasAnomaly = true;
        }
        assertTrue(c1HasAnomaly, "Customer C1 should have anomalies");
        assertFalse(c4HasAnomaly, "Customer C4 should not have anomalies");

        // Save report
        Path outputFile = tempDir.resolve("anomaly_report.csv");
        analyzer.saveAnomalyReport(anomalies, outputFile.toString());
        assertTrue(outputFile.toFile().exists());
    }
}
