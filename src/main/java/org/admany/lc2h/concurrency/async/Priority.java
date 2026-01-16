package org.admany.lc2h.concurrency.async;

public enum Priority {
    HIGH,   // Visible chunks, prioritize immediately
    LOW     // Background chunks, lower priority
}
