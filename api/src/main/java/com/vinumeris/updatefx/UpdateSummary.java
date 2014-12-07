package com.vinumeris.updatefx;

import java.util.List;

/**
 * Represents a report of what work was done during an update operation.
 */
public class UpdateSummary {
    /** The version number of the best known version of the app. Can be the same as the current version */
    public final int highestVersion;
    /** The signed index data that was fetched */
    public final UFXProtocol.Updates updates;
    /** The list of descriptions for the current best version (one for each language) */
    public final List<UFXProtocol.UpdateDescription> descriptions;

    public UpdateSummary(int highestVersion, UFXProtocol.Updates updates) {
        this.highestVersion = highestVersion;
        this.updates = updates;

        List<UFXProtocol.UpdateDescription> d = null;
        for (UFXProtocol.Update update : updates.getUpdatesList()) {
            if (update.getVersion() == highestVersion) {
                d = update.getDescriptionList();
                break;
            }
        }
        descriptions = d;
    }
}
