package com.pricing.service;

import com.pricing.exception.BatchNotFoundException;
import com.pricing.exception.InvalidBatchOperationException;
import com.pricing.model.PriceRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive test suite for PriceService implementation.
 *
 * <p>Test Coverage:
 * <ul>
 *   <li>Basic batch run lifecycle</li>
 *   <li>Last value semantics (most recent asOf)</li>
 *   <li>Atomic batch completion</li>
 *   <li>Concurrent access by multiple producers and consumers</li>
 *   <li>Error handling and resilience</li>
 *   <li>Edge cases</li>
 * </ul>
 */
@DisplayName("PriceService Tests")
class InMemoryPriceServiceTest {

    private PriceService priceService;

    @BeforeEach
    void setUp() {
        priceService = new InMemoryPriceService();
    }

    @Nested
    @DisplayName("Basic Batch Operations")
    class BasicBatchOperations {

        @Test
        @DisplayName("Should start a batch run and return unique batch ID")
        void shouldStartBatchRun() {
            String batchId1 = priceService.startBatchRun();
            String batchId2 = priceService.startBatchRun();

            assertThat(batchId1).isNotNull().isNotEmpty();
            assertThat(batchId2).isNotNull().isNotEmpty();
            assertThat(batchId1).isNotEqualTo(batchId2);
        }

        @Test
        @DisplayName("Should upload records in chunks")
        void shouldUploadRecordsInChunks() {
            String batchId = priceService.startBatchRun();

            List<PriceRecord> chunk1 = createPriceRecords("AAPL", 100);
            List<PriceRecord> chunk2 = createPriceRecords("GOOGL", 100);

            assertThatNoException().isThrownBy(() -> {
                priceService.uploadRecords(batchId, chunk1);
                priceService.uploadRecords(batchId, chunk2);
            });
        }

        @Test
        @DisplayName("Should complete batch run and publish prices")
        void shouldCompleteBatchRunAndPublishPrices() {
            String batchId = priceService.startBatchRun();

            Instant now = Instant.now();
            List<PriceRecord> records = Arrays.asList(
                new PriceRecord("AAPL", now, 150.0),
                new PriceRecord("GOOGL", now, 2800.0)
            );

            priceService.uploadRecords(batchId, records);
            priceService.completeBatchRun(batchId);

            assertThat(priceService.getLastPrice("AAPL")).isPresent()
                .get().extracting(PriceRecord::getPayload).isEqualTo(150.0);
            assertThat(priceService.getLastPrice("GOOGL")).isPresent()
                .get().extracting(PriceRecord::getPayload).isEqualTo(2800.0);
        }

        @Test
        @DisplayName("Should cancel batch run and discard records")
        void shouldCancelBatchRunAndDiscardRecords() {
            String batchId = priceService.startBatchRun();

            List<PriceRecord> records = Arrays.asList(
                new PriceRecord("AAPL", Instant.now(), 150.0)
            );

            priceService.uploadRecords(batchId, records);
            priceService.cancelBatchRun(batchId);

            assertThat(priceService.getLastPrice("AAPL")).isEmpty();
            assertThat(priceService.getPriceCount()).isZero();
        }
    }

    @Nested
    @DisplayName("Last Value Semantics")
    class LastValueSemantics {

        @Test
        @DisplayName("Should keep only the most recent price based on asOf timestamp")
        void shouldKeepMostRecentPriceByAsOf() {
            String batchId = priceService.startBatchRun();

            Instant older = Instant.parse("2024-01-01T10:00:00Z");
            Instant newer = Instant.parse("2024-01-01T11:00:00Z");

            List<PriceRecord> records = Arrays.asList(
                new PriceRecord("AAPL", older, 100.0),
                new PriceRecord("AAPL", newer, 150.0)
            );

            priceService.uploadRecords(batchId, records);
            priceService.completeBatchRun(batchId);

            Optional<PriceRecord> lastPrice = priceService.getLastPrice("AAPL");
            assertThat(lastPrice).isPresent();
            assertThat(lastPrice.get().getPayload()).isEqualTo(150.0);
            assertThat(lastPrice.get().getAsOf()).isEqualTo(newer);
        }

        @Test
        @DisplayName("Should retain newer price from previous batch if new batch has older timestamp")
        void shouldRetainNewerPriceAcrossBatches() {
            // First batch with newer timestamp
            String batch1 = priceService.startBatchRun();
            Instant newer = Instant.parse("2024-01-01T12:00:00Z");
            priceService.uploadRecords(batch1,
                Collections.singletonList(new PriceRecord("AAPL", newer, 200.0)));
            priceService.completeBatchRun(batch1);

            // Second batch with older timestamp
            String batch2 = priceService.startBatchRun();
            Instant older = Instant.parse("2024-01-01T10:00:00Z");
            priceService.uploadRecords(batch2,
                Collections.singletonList(new PriceRecord("AAPL", older, 100.0)));
            priceService.completeBatchRun(batch2);

            Optional<PriceRecord> lastPrice = priceService.getLastPrice("AAPL");
            assertThat(lastPrice).isPresent();
            assertThat(lastPrice.get().getPayload()).isEqualTo(200.0);
            assertThat(lastPrice.get().getAsOf()).isEqualTo(newer);
        }

        @Test
        @DisplayName("Should update price when new batch has newer timestamp")
        void shouldUpdatePriceWithNewerTimestamp() {
            // First batch
            String batch1 = priceService.startBatchRun();
            Instant older = Instant.parse("2024-01-01T10:00:00Z");
            priceService.uploadRecords(batch1,
                Collections.singletonList(new PriceRecord("AAPL", older, 100.0)));
            priceService.completeBatchRun(batch1);

            // Second batch with newer timestamp
            String batch2 = priceService.startBatchRun();
            Instant newer = Instant.parse("2024-01-01T12:00:00Z");
            priceService.uploadRecords(batch2,
                Collections.singletonList(new PriceRecord("AAPL", newer, 200.0)));
            priceService.completeBatchRun(batch2);

            Optional<PriceRecord> lastPrice = priceService.getLastPrice("AAPL");
            assertThat(lastPrice).isPresent();
            assertThat(lastPrice.get().getPayload()).isEqualTo(200.0);
            assertThat(lastPrice.get().getAsOf()).isEqualTo(newer);
        }
    }

    @Nested
    @DisplayName("Atomicity and Consistency")
    class AtomicityAndConsistency {

        @Test
        @DisplayName("Should not expose records until batch is completed")
        void shouldNotExposeRecordsUntilBatchCompleted() {
            String batchId = priceService.startBatchRun();

            List<PriceRecord> records = Arrays.asList(
                new PriceRecord("AAPL", Instant.now(), 150.0)
            );

            priceService.uploadRecords(batchId, records);

            // Price should not be visible until batch is completed
            assertThat(priceService.getLastPrice("AAPL")).isEmpty();

            priceService.completeBatchRun(batchId);

            // Now price should be visible
            assertThat(priceService.getLastPrice("AAPL")).isPresent();
        }

        @Test
        @DisplayName("Should publish all prices in batch atomically")
        void shouldPublishAllPricesAtomically() {
            String batchId = priceService.startBatchRun();

            Instant now = Instant.now();
            List<PriceRecord> records = Arrays.asList(
                new PriceRecord("AAPL", now, 150.0),
                new PriceRecord("GOOGL", now, 2800.0),
                new PriceRecord("MSFT", now, 380.0)
            );

            priceService.uploadRecords(batchId, records);
            priceService.completeBatchRun(batchId);

            // All prices should be available
            assertThat(priceService.getLastPrice("AAPL")).isPresent();
            assertThat(priceService.getLastPrice("GOOGL")).isPresent();
            assertThat(priceService.getLastPrice("MSFT")).isPresent();
            assertThat(priceService.getPriceCount()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("Error Handling and Resilience")
    class ErrorHandlingAndResilience {

        @Test
        @DisplayName("Should throw exception when uploading to non-existent batch")
        void shouldThrowExceptionForNonExistentBatch() {
            String fakeBatchId = "non-existent-batch";

            assertThatThrownBy(() ->
                priceService.uploadRecords(fakeBatchId,
                    Collections.singletonList(new PriceRecord("AAPL", Instant.now(), 150.0)))
            ).isInstanceOf(BatchNotFoundException.class)
             .hasMessageContaining("Batch not found");
        }

        @Test
        @DisplayName("Should throw exception when completing non-existent batch")
        void shouldThrowExceptionWhenCompletingNonExistentBatch() {
            assertThatThrownBy(() ->
                priceService.completeBatchRun("non-existent-batch")
            ).isInstanceOf(BatchNotFoundException.class);
        }

        @Test
        @DisplayName("Should throw exception when cancelling non-existent batch")
        void shouldThrowExceptionWhenCancellingNonExistentBatch() {
            assertThatThrownBy(() ->
                priceService.cancelBatchRun("non-existent-batch")
            ).isInstanceOf(BatchNotFoundException.class);
        }

        @Test
        @DisplayName("Should throw exception when uploading to completed batch")
        void shouldThrowExceptionWhenUploadingToCompletedBatch() {
            String batchId = priceService.startBatchRun();
            priceService.uploadRecords(batchId,
                Collections.singletonList(new PriceRecord("AAPL", Instant.now(), 150.0)));
            priceService.completeBatchRun(batchId);

            assertThatThrownBy(() ->
                priceService.uploadRecords(batchId,
                    Collections.singletonList(new PriceRecord("GOOGL", Instant.now(), 2800.0)))
            ).isInstanceOf(BatchNotFoundException.class);
        }

        @Test
        @DisplayName("Should throw exception when completing already completed batch")
        void shouldThrowExceptionWhenCompletingAlreadyCompletedBatch() {
            String batchId = priceService.startBatchRun();
            priceService.uploadRecords(batchId,
                Collections.singletonList(new PriceRecord("AAPL", Instant.now(), 150.0)));
            priceService.completeBatchRun(batchId);

            assertThatThrownBy(() ->
                priceService.completeBatchRun(batchId)
            ).isInstanceOf(BatchNotFoundException.class);
        }

        @Test
        @DisplayName("Should throw exception when uploading null or empty records")
        void shouldThrowExceptionForInvalidRecords() {
            String batchId = priceService.startBatchRun();

            assertThatThrownBy(() ->
                priceService.uploadRecords(batchId, null)
            ).isInstanceOf(IllegalArgumentException.class)
             .hasMessageContaining("cannot be null or empty");

            assertThatThrownBy(() ->
                priceService.uploadRecords(batchId, Collections.emptyList())
            ).isInstanceOf(IllegalArgumentException.class)
             .hasMessageContaining("cannot be null or empty");
        }

        @Test
        @DisplayName("Should throw exception when getting price with null ID")
        void shouldThrowExceptionForNullId() {
            assertThatThrownBy(() ->
                priceService.getLastPrice(null)
            ).isInstanceOf(IllegalArgumentException.class)
             .hasMessageContaining("id cannot be null");
        }
    }

    @Nested
    @DisplayName("Concurrent Access")
    class ConcurrentAccess {

        @Test
        @DisplayName("Should handle multiple concurrent producers")
        void shouldHandleMultipleConcurrentProducers() throws Exception {
            int numberOfProducers = 10;
            int recordsPerProducer = 100;
            ExecutorService executor = Executors.newFixedThreadPool(numberOfProducers);

            List<Future<?>> futures = new ArrayList<>();

            for (int i = 0; i < numberOfProducers; i++) {
                final int producerId = i;
                futures.add(executor.submit(() -> {
                    String batchId = priceService.startBatchRun();
                    List<PriceRecord> records = createPriceRecords("INST-" + producerId,
                        recordsPerProducer);
                    priceService.uploadRecords(batchId, records);
                    priceService.completeBatchRun(batchId);
                }));
            }

            // Wait for all producers to complete
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }

            executor.shutdown();

            // All instruments should have prices
            assertThat(priceService.getPriceCount()).isEqualTo(numberOfProducers);
        }

        @Test
        @DisplayName("Should allow consumers to query while batches are being processed")
        void shouldAllowConsumersWhileBatchesProcessing() throws Exception {
            // Setup: publish initial price
            String initialBatch = priceService.startBatchRun();
            priceService.uploadRecords(initialBatch,
                Collections.singletonList(new PriceRecord("AAPL", Instant.now(), 100.0)));
            priceService.completeBatchRun(initialBatch);

            ExecutorService executor = Executors.newFixedThreadPool(20);
            CountDownLatch latch = new CountDownLatch(1);
            List<Future<Optional<PriceRecord>>> consumerFutures = new ArrayList<>();

            // Start consumer threads that will query repeatedly
            for (int i = 0; i < 10; i++) {
                consumerFutures.add(executor.submit(() -> {
                    latch.await(); // Wait for signal
                    Optional<PriceRecord> price = priceService.getLastPrice("AAPL");
                    return price;
                }));
            }

            // Start producer threads that will upload batches
            List<Future<?>> producerFutures = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                producerFutures.add(executor.submit(() -> {
                    latch.await(); // Wait for signal
                    String batchId = priceService.startBatchRun();
                    priceService.uploadRecords(batchId,
                        Collections.singletonList(new PriceRecord("GOOGL", Instant.now(), 2800.0)));
                    priceService.completeBatchRun(batchId);
                }));
            }

            // Start all threads simultaneously
            latch.countDown();

            // Wait for all to complete
            for (Future<?> future : producerFutures) {
                future.get(10, TimeUnit.SECONDS);
            }

            for (Future<Optional<PriceRecord>> future : consumerFutures) {
                Optional<PriceRecord> result = future.get(10, TimeUnit.SECONDS);
                assertThat(result).isPresent(); // Should always get AAPL price
            }

            executor.shutdown();

            // Both instruments should have prices
            assertThat(priceService.getPriceCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("Should handle race condition when multiple batches update same instrument")
        void shouldHandleRaceConditionForSameInstrument() throws Exception {
            int numberOfBatches = 20;
            ExecutorService executor = Executors.newFixedThreadPool(numberOfBatches);

            Instant baseTime = Instant.now();
            List<Future<?>> futures = new ArrayList<>();

            for (int i = 0; i < numberOfBatches; i++) {
                final int batchNumber = i;
                futures.add(executor.submit(() -> {
                    String batchId = priceService.startBatchRun();
                    // Each batch has a different timestamp
                    Instant timestamp = baseTime.plus(batchNumber, ChronoUnit.SECONDS);
                    priceService.uploadRecords(batchId,
                        Collections.singletonList(
                            new PriceRecord("AAPL", timestamp, 100.0 + batchNumber)));
                    priceService.completeBatchRun(batchId);
                }));
            }

            // Wait for all batches to complete
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }

            executor.shutdown();

            // Should only have one entry for AAPL
            assertThat(priceService.getPriceCount()).isEqualTo(1);

            // Should have the most recent price
            Optional<PriceRecord> lastPrice = priceService.getLastPrice("AAPL");
            assertThat(lastPrice).isPresent();
            assertThat(lastPrice.get().getPayload()).isEqualTo(100.0 + (numberOfBatches - 1));
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("Should handle empty service correctly")
        void shouldHandleEmptyService() {
            assertThat(priceService.getLastPrice("AAPL")).isEmpty();
            assertThat(priceService.getPriceCount()).isZero();
        }

        @Test
        @DisplayName("Should handle batch with no records")
        void shouldHandleBatchWithNoRecords() {
            String batchId = priceService.startBatchRun();
            priceService.completeBatchRun(batchId);

            assertThat(priceService.getPriceCount()).isZero();
        }

        @Test
        @DisplayName("Should handle flexible payload types")
        void shouldHandleFlexiblePayloadTypes() {
            String batchId = priceService.startBatchRun();

            Instant now = Instant.now();
            Map<String, Object> complexPayload = new HashMap<>();
            complexPayload.put("price", 150.0);
            complexPayload.put("volume", 1000);
            complexPayload.put("currency", "USD");

            List<PriceRecord> records = Arrays.asList(
                new PriceRecord("INST1", now, 150.0),                    // Double
                new PriceRecord("INST2", now, "150.0"),                   // String
                new PriceRecord("INST3", now, complexPayload),            // Map
                new PriceRecord("INST4", now, Arrays.asList(150, 151))   // List
            );

            priceService.uploadRecords(batchId, records);
            priceService.completeBatchRun(batchId);

            assertThat(priceService.getLastPrice("INST1")).isPresent()
                .get().extracting(PriceRecord::getPayload).isEqualTo(150.0);
            assertThat(priceService.getLastPrice("INST2")).isPresent()
                .get().extracting(PriceRecord::getPayload).isEqualTo("150.0");
            assertThat(priceService.getLastPrice("INST3")).isPresent()
                .get().extracting(PriceRecord::getPayload).isEqualTo(complexPayload);
        }

        @Test
        @DisplayName("Should handle large batch efficiently")
        void shouldHandleLargeBatchEfficiently() {
            String batchId = priceService.startBatchRun();

            // Upload 10 chunks of 1000 records each (as per spec)
            for (int chunk = 0; chunk < 10; chunk++) {
                List<PriceRecord> records = createPriceRecords("INST", 1000);
                priceService.uploadRecords(batchId, records);
            }

            long startTime = System.nanoTime();
            priceService.completeBatchRun(batchId);
            long endTime = System.nanoTime();

            long durationMs = (endTime - startTime) / 1_000_000;

            // Should complete in reasonable time (adjust threshold as needed)
            assertThat(durationMs).isLessThan(1000); // Less than 1 second

            assertThat(priceService.getPriceCount()).isEqualTo(1000);
        }
    }

    // Helper methods

    private List<PriceRecord> createPriceRecords(String idPrefix, int count) {
        Instant now = Instant.now();
        return IntStream.range(0, count)
            .mapToObj(i -> new PriceRecord(
                idPrefix + "-" + i,
                now.plusSeconds(i),
                Math.random() * 1000))
            .collect(Collectors.toList());
    }
}
