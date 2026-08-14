package dev.mantikafasi.manticord.gradle;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

public abstract class ManticordPluginExtension {
    public abstract Property<String> getId();
    public abstract Property<String> getEntrypoint();
    public abstract Property<String> getDescription();
    public abstract Property<Integer> getManticordVersionMinimum();
    public abstract Property<Integer> getManticordVersionMaximumExclusive();
    public abstract Property<Integer> getPriority();
    public abstract Property<Boolean> getUsesResources();
    public abstract Property<String> getLoadMode();
    public abstract ListProperty<String> getRequirements();

    public void async() {
        getLoadMode().set("ASYNC");
    }

    public void blocking() {
        getLoadMode().set("BLOCKING");
    }

    public void requires(String pluginId, int minimumVersionCode) {
        requires(pluginId, minimumVersionCode, 0);
    }

    public void requires(String pluginId, int minimumVersionCode, int maximumVersionCodeExclusive) {
        if (!pluginId.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("required plugin ID is invalid");
        if (minimumVersionCode < 0) throw new IllegalArgumentException("minimum version must not be negative");
        if (maximumVersionCodeExclusive != 0 && maximumVersionCodeExclusive <= minimumVersionCode) {
            throw new IllegalArgumentException("maximum version must be greater than minimum");
        }
        getRequirements().add(pluginId + ":" + minimumVersionCode + ":" + maximumVersionCodeExclusive);
    }
}
