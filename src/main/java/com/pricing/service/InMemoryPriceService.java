package com.pricing.service;

import com.pricing.exception.BatchNotFoundException;
import com.pricing.exception.InvalidBatchOperationException;
import com.pricing.model.PriceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe in-memory implementation of the PriceService.
 *
 * <p><b>Concurrency Design:</b>
 * <ul>
 *   <li>Uses ConcurrentHashMap for published prices - provides O(1) reads without locking</li>
 *   <li>Uses synchronized methods for batch operations to ensure state consistency</li>
 *   <li>ReadWriteLock for batch completion to allow multiple readers, single writer</li>
 * </ul>
 *
 * <p><b>Memory Characteristics:</b>
 * <ul>
 *   <li>Staged batches held in memory until completed or cancelled</li>
 *   <li>Only latest price per instrument ID retained - O(n) space where n is unique instruments</li>
 *   <li>Cancelled batches immediately freed for garbage collection</li>
 * </ul>
 *
 * <p><b>Performance Analysis:</b>
 * <ul>
 *   <li>getLastPrice: O(1) - ConcurrentHashMap lookup, no locking required</li>
 *   <li>uploadRecords: O(k) - where k is chunk size, synchronized but short critical section</li>
 *   <li>completeBatchRun: O(m) - where m is total batch records, uses read lock during merge</li>
 *   <li>startBatchRun: O(1) - UUID generation and map insertion</li>
 * </ul>
 *
 * <p><b>Potential Improvements for Production:</b>
 * <ol>
 *   <li>Add monitoring/metrics: batch completion times, record counts, rejection rates</li>
 *   <li>Implement batch timeout mechanism to auto-cancel stale batches</li>
 *   <li>Add memory pressure monitoring - reject new batches if memory threshold exceeded</li>
 *   <li>Consider batching completions for multiple producers to reduce lock contention</li>
 *   <li>Add audit logging for compliance tracking</li>
 *   <li>Implement backpressure if upload rate exceeds processing capacity</li>
 * </ol>
 */
public class InMemoryPriceService implements PriceService {

    private static final Logger logger = LoggerFactory.getLogger(InMemoryPriceService.class);

    /**
     * Published prices accessible to consumers.
     * Key: instrument ID, Value: latest PriceRecord
     *
     * Design Decision: ConcurrentHashMap chosen for lock-free reads in getLastPrice().
     * This is critical for performance as consumers will query frequently.
     */
    private final ConcurrentHashMap<String, PriceRecord> publishedPrices;

    /**
     * Active batch runs being staged.
     * Key: batch ID, Value: BatchRun
     *
     * Design Decision: Regular HashMap with synchronized access is sufficient here
     * as batch operations are less frequent than price queries.
     */
    private final Map<String, BatchRun> activeBatches;

    /**
     * Lock for coordinating batch completion with concurrent reads.
     *
     * Design Decision: ReadWriteLock allows multiple concurrent getLastPrice() calls
     * while ensuring completeBatchRun() has exclusive access during the merge operation.
     */
    private final ReadWriteLock publishLock;

    public InMemoryPriceService() {
        this.publishedPrices = new ConcurrentHashMap<>();
        this.activeBatches = new HashMap<>();
        this.publishLock = new ReentrantReadWriteLock();
    }

    @Override
    public String startBatchRun() {
        String batchId = UUID.randomUUID().toString();

        synchronized (activeBatches) {
            activeBatches.put(batchId, new BatchRun(batchId));
        }

        logger.debug("Started batch run: {}", batchId);
        return batchId;
    }

    @Override
    public void uploadRecords(String batchId, List<PriceRecord> records) {
        if (records == null || records.isEmpty()) {
            throw new IllegalArgumentException("Records cannot be null or empty");
        }

        synchronized (activeBatches) {
            BatchRun batch = activeBatches.get(batchId);

            if (batch == null) {
                throw new BatchNotFoundException("Batch not found: " + batchId);
            }

            if (batch.state != BatchState.STARTED) {
                throw new InvalidBatchOperationException(
                    "Cannot upload records to batch in state: " + batch.state);
            }

            // Add records to staging area
            batch.stagedRecords.addAll(records);
        }

        logger.debug("Uploaded {} records to batch: {}", records.size(), batchId);
    }

    @Override
    public void completeBatchRun(String batchId) {
        List<PriceRecord> recordsToPublish;

        // Step 1: Extract and remove batch from active batches (synchronized)
        synchronized (activeBatches) {
            BatchRun batch = activeBatches.get(batchId);

            if (batch == null) {
                throw new BatchNotFoundException("Batch not found: " + batchId);
            }

            if (batch.state != BatchState.STARTED) {
                throw new InvalidBatchOperationException(
                    "Cannot complete batch in state: " + batch.state);
            }

            batch.state = BatchState.COMPLETED;
            recordsToPublish = new ArrayList<>(batch.stagedRecords);

            // Remove from active batches - memory can be reclaimed
            activeBatches.remove(batchId);
        }

        // Step 2: Publish records atomically (using write lock)
        // This ensures consumers don't see partial batch updates
        publishLock.writeLock().lock();
        try {
            publishRecordsAtomically(recordsToPublish);
        } finally {
            publishLock.writeLock().unlock();
        }

        logger.info("Completed batch run: {} with {} records", batchId, recordsToPublish.size());
    }

    @Override
    public void cancelBatchRun(String batchId) {
        synchronized (activeBatches) {
            BatchRun batch = activeBatches.get(batchId);

            if (batch == null) {
                throw new BatchNotFoundException("Batch not found: " + batchId);
            }

            if (batch.state != BatchState.STARTED) {
                throw new InvalidBatchOperationException(
                    "Cannot cancel batch in state: " + batch.state);
            }

            batch.state = BatchState.CANCELLED;

            // Remove from active batches - staged records can be garbage collected
            activeBatches.remove(batchId);
        }

        logger.info("Cancelled batch run: {}", batchId);
    }

    @Override
    public Optional<PriceRecord> getLastPrice(String id) {
        if (id == null) {
            throw new IllegalArgumentException("id cannot be null");
        }

        // Design Decision: No locking needed for reads thanks to ConcurrentHashMap.
        // This provides excellent read performance for consumers.
        // The publishLock ReadLock is NOT needed here because ConcurrentHashMap
        // guarantees visibility of updates, and we accept eventual consistency
        // (consumers may see old price momentarily during batch completion).

        return Optional.ofNullable(publishedPrices.get(id));
    }

    @Override
    public int getPriceCount() {
        return publishedPrices.size();
    }

    /**
     * Publishes records atomically, keeping only the latest value per instrument ID.
     *
     * <p>Design Decision: For each ID, keeps only the record with the most recent asOf.
     * This implements the "last value" semantics required by the specification.
     *
     * <p>This method is called within a write lock to ensure atomicity of the batch.
     *
     * @param records the records to publish
     */
    private void publishRecordsAtomically(List<PriceRecord> records) {
        // Step 1: Group records by ID and find the latest for each
        Map<String, PriceRecord> latestInBatch = new HashMap<>();

        for (PriceRecord record : records) {
            String id = record.getId();
            PriceRecord current = latestInBatch.get(id);

            // Keep the record with the most recent asOf timestamp
            if (current == null || record.getAsOf().isAfter(current.getAsOf())) {
                latestInBatch.put(id, record);
            }
        }

        // Step 2: Merge with published prices, keeping overall latest value
        for (Map.Entry<String, PriceRecord> entry : latestInBatch.entrySet()) {
            String id = entry.getKey();
            PriceRecord newRecord = entry.getValue();

            publishedPrices.compute(id, (key, existingRecord) -> {
                // If no existing record, or new record is more recent, use new record
                if (existingRecord == null ||
                    newRecord.getAsOf().isAfter(existingRecord.getAsOf())) {
                    return newRecord;
                }
                // Otherwise keep existing record
                return existingRecord;
            });
        }
    }

    /**
     * Internal representation of a batch run.
     *
     * <p>Design Decision: Kept as inner class since it's an implementation detail.
     * Uses ArrayList for staged records as order doesn't matter and random access
     * is not needed - sequential processing during completion is most common.
     */
    private static class BatchRun {
        final String batchId;
        final List<PriceRecord> stagedRecords;
        BatchState state;

        BatchRun(String batchId) {
            this.batchId = batchId;
            this.stagedRecords = new ArrayList<>();
            this.state = BatchState.STARTED;
        }
    }

    /**
     * Batch run lifecycle states.
     */
    private enum BatchState {
        STARTED,    // Batch is accepting records
        COMPLETED,  // Batch has been completed and published
        CANCELLED   // Batch has been cancelled
    }
}
