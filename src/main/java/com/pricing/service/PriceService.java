package com.pricing.service;

import com.pricing.exception.BatchNotFoundException;
import com.pricing.exception.InvalidBatchOperationException;
import com.pricing.model.PriceRecord;

import java.util.List;
import java.util.Optional;

/**
 * Service for tracking and retrieving last value prices for financial instruments.
 *
 * <p>This service provides an in-memory implementation for:
 * <ul>
 *   <li>Producers to publish prices in batch runs</li>
 *   <li>Consumers to retrieve the latest prices by instrument ID</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b> All methods in this interface are thread-safe and can be
 * called concurrently by multiple producers and consumers.
 *
 * <p><b>Batch Run Lifecycle:</b>
 * <pre>
 * 1. startBatchRun() -> returns batchId
 * 2. uploadRecords(batchId, records) -> can be called multiple times
 * 3. completeBatchRun(batchId) OR cancelBatchRun(batchId)
 * </pre>
 *
 * <p><b>Performance Characteristics:</b>
 * <ul>
 *   <li>getLastPrice(): O(1) - constant time lookup using ConcurrentHashMap</li>
 *   <li>uploadRecords(): O(n) - where n is the number of records in the chunk</li>
 *   <li>completeBatchRun(): O(m) - where m is the total number of records in the batch</li>
 * </ul>
 */
public interface PriceService {

    /**
     * Starts a new batch run and returns a unique batch identifier.
     *
     * <p>Design Decision: Returns a String ID rather than a typed BatchId object
     * for simplicity. UUID is used internally for uniqueness.
     *
     * @return a unique batch run identifier
     */
    String startBatchRun();

    /**
     * Uploads a chunk of price records to an ongoing batch run.
     *
     * <p>Design Decision: Accepts a List instead of array for better Java Collections
     * integration. The requirement specifies chunks of 1000 records, but this method
     * does not enforce the size - allowing flexibility for producers.
     *
     * <p>Records are staged in memory until the batch is completed. They are not
     * visible to consumers until completeBatchRun() is called.
     *
     * @param batchId the batch run identifier
     * @param records the list of price records to upload (must not be null or empty)
     * @throws BatchNotFoundException if the batch ID does not exist
     * @throws InvalidBatchOperationException if the batch is not in STARTED state
     * @throws IllegalArgumentException if records is null or empty
     */
    void uploadRecords(String batchId, List<PriceRecord> records);

    /**
     * Completes a batch run and makes all its records available to consumers atomically.
     *
     * <p>Design Decision: All records in the batch are published atomically - either all
     * prices are visible or none. This ensures consistency for consumers.
     *
     * <p>For each instrument ID, only the record with the most recent asOf timestamp
     * will be retained as the "last value".
     *
     * @param batchId the batch run identifier
     * @throws BatchNotFoundException if the batch ID does not exist
     * @throws InvalidBatchOperationException if the batch is not in STARTED state
     */
    void completeBatchRun(String batchId);

    /**
     * Cancels a batch run and discards all its records.
     *
     * <p>Design Decision: Cancelled batches are immediately removed from memory,
     * preventing memory leaks from abandoned batches.
     *
     * @param batchId the batch run identifier
     * @throws BatchNotFoundException if the batch ID does not exist
     * @throws InvalidBatchOperationException if the batch is not in STARTED state
     */
    void cancelBatchRun(String batchId);

    /**
     * Retrieves the last price record for a given instrument ID.
     *
     * <p>Design Decision: Returns Optional to explicitly handle the case where
     * no price exists for the given ID, avoiding null returns.
     *
     * <p>The "last" price is determined by the most recent asOf timestamp among
     * all completed batch runs.
     *
     * <p><b>Concurrent Access Resilience:</b> This method can be safely called
     * while batches are being processed. It will return the latest committed
     * price, unaffected by in-progress batches.
     *
     * @param id the instrument identifier
     * @return an Optional containing the last price record, or empty if no price exists
     * @throws IllegalArgumentException if id is null
     */
    Optional<PriceRecord> getLastPrice(String id);

    /**
     * Returns the total number of instruments with published prices.
     *
     * <p>This is primarily useful for monitoring and testing purposes.
     *
     * @return the count of instruments with at least one price
     */
    int getPriceCount();
}
