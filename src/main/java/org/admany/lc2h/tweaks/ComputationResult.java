package org.admany.lc2h.tweaks;

import java.util.Objects;


public final class ComputationResult {

    private final ComputationRequest request;
    private final Object payload;
    private final long computeNanos;

    public ComputationResult(ComputationRequest request, Object payload, long computeNanos) {
        this.request = Objects.requireNonNull(request, "request");
        this.payload = payload;
        this.computeNanos = computeNanos;
    }

    public ComputationRequest request() {
        return request;
    }

    public Object payload() {
        return payload;
    }

    public long computeNanos() {
        return computeNanos;
    }
}