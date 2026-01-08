package com.pricing;

import com.pricing.model.PriceRecord;
import com.pricing.service.InMemoryPriceService;
import com.pricing.service.PriceService;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Simple demonstration of the Price Service usage.
 *
 * <p>This example shows:
 * <ul>
 *   <li>Starting a batch run</li>
 *   <li>Uploading price records</li>
 *   <li>Completing the batch</li>
 *   <li>Querying for last prices</li>
 * </ul>
 */
public class PriceServiceExample {

    public static void main(String[] args) {
        // Create the service
        PriceService service = new InMemoryPriceService();

        System.out.println("=== Price Service Demo ===\n");

        // Producer workflow
        demonstrateProducerWorkflow(service);

        System.out.println();

        // Consumer workflow
        demonstrateConsumerWorkflow(service);

        System.out.println();

        // Demonstrate last value semantics
        demonstrateLastValueSemantics(service);
    }

    private static void demonstrateProducerWorkflow(PriceService service) {
        System.out.println("1. Producer Workflow");
        System.out.println("   Starting batch run...");

        String batchId = service.startBatchRun();
        System.out.println("   Batch ID: " + batchId);

        System.out.println("   Uploading records...");

        List<PriceRecord> records = Arrays.asList(
            new PriceRecord("AAPL", Instant.now(), 150.25),
            new PriceRecord("GOOGL", Instant.now(), 2800.50),
            new PriceRecord("MSFT", Instant.now(), 380.75),
            new PriceRecord("AMZN", Instant.now(), 3200.00),
            new PriceRecord("TSLA", Instant.now(), 245.30)
        );

        service.uploadRecords(batchId, records);
        System.out.println("   Uploaded " + records.size() + " records");

        System.out.println("   Completing batch...");
        service.completeBatchRun(batchId);
        System.out.println("   Batch completed successfully!");
    }

    private static void demonstrateConsumerWorkflow(PriceService service) {
        System.out.println("2. Consumer Workflow");

        String[] symbols = {"AAPL", "GOOGL", "MSFT", "AMZN", "TSLA"};

        for (String symbol : symbols) {
            Optional<PriceRecord> price = service.getLastPrice(symbol);

            if (price.isPresent()) {
                PriceRecord record = price.get();
                System.out.printf("   %s: $%.2f (as of %s)%n",
                    record.getId(),
                    record.getPayload(),
                    record.getAsOf());
            }
        }

        System.out.println("   Total instruments: " + service.getPriceCount());
    }

    private static void demonstrateLastValueSemantics(PriceService service) {
        System.out.println("3. Last Value Semantics Demo");

        // Upload older price for AAPL
        String batch1 = service.startBatchRun();
        Instant older = Instant.parse("2024-01-01T10:00:00Z");
        service.uploadRecords(batch1,
            Arrays.asList(new PriceRecord("AAPL", older, 100.0)));
        service.completeBatchRun(batch1);

        System.out.println("   After uploading older price:");
        service.getLastPrice("AAPL").ifPresent(record ->
            System.out.printf("   AAPL: $%.2f (as of %s)%n",
                record.getPayload(), record.getAsOf()));

        // Upload newer price for AAPL
        String batch2 = service.startBatchRun();
        Instant newer = Instant.parse("2024-01-01T12:00:00Z");
        service.uploadRecords(batch2,
            Arrays.asList(new PriceRecord("AAPL", newer, 200.0)));
        service.completeBatchRun(batch2);

        System.out.println("   After uploading newer price:");
        service.getLastPrice("AAPL").ifPresent(record ->
            System.out.printf("   AAPL: $%.2f (as of %s)%n",
                record.getPayload(), record.getAsOf()));

        System.out.println("   → Service retained the most recent price!");
    }
}
