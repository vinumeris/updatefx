package com.vinumeris.updatefx;

import java.util.List;

/**
 * Represents a report of what work was done during an update operation.
 */
public class UpdateSummary {
    public final int newVersion;
    public final UFXProtocol.Updates updates;

    public final List<UFXProtocol.UpdateDescription> descriptions;

    public UpdateSummary(int newVersion, UFXProtocol.Updates updates) {
        this.newVersion = newVersion;
        this.updates = updates;

        List<UFXProtocol.UpdateDescription> d = null;
        for (UFXProtocol.Update update : updates.getUpdatesList()) {
            if (update.getVersion() == newVersion) {
                d = update.getDescriptionList();
                break;
            }
        }
        descriptions = d;
    }
}
