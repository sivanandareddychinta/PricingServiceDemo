package com.pricing.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents a price record for a financial instrument.
 *
 * Design Decision: Using Instant for asOf field for precise timestamp handling
 * and to avoid timezone complexities. The payload is kept as Object to allow
 * maximum flexibility as per requirements.
 */
public final class PriceRecord {
    private final String id;
    private final Instant asOf;
    private final Object payload;

    public PriceRecord(String id, Instant asOf, Object payload) {
        this.id = Objects.requireNonNull(id, "id cannot be null");
        this.asOf = Objects.requireNonNull(asOf, "asOf cannot be null");
        this.payload = Objects.requireNonNull(payload, "payload cannot be null");
    }

    public String getId() {
        return id;
    }

    public Instant getAsOf() {
        return asOf;
    }

    public Object getPayload() {
        return payload;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PriceRecord that = (PriceRecord) o;
        return Objects.equals(id, that.id) &&
               Objects.equals(asOf, that.asOf) &&
               Objects.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, asOf, payload);
    }

    @Override
    public String toString() {
        return "PriceRecord{" +
                "id='" + id + '\'' +
                ", asOf=" + asOf +
                ", payload=" + payload +
                '}';
    }
}
