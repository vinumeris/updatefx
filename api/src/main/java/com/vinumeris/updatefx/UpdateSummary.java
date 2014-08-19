package com.vinumeris.updatefx;

/**
 * Represents a report of what work was done during an update operation.
 */
public class UpdateSummary {
    public final int newVersion;

    public UpdateSummary(int newVersion) {
        this.newVersion = newVersion;
    }
}
