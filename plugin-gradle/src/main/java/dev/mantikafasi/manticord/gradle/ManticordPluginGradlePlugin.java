package dev.mantikafasi.manticord.gradle;

import com.android.build.api.dsl.ApplicationExtension;
import com.android.build.api.variant.ApplicationAndroidComponentsExtension;
import kotlin.Unit;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

public final class ManticordPluginGradlePlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        ManticordPluginExtension extension = project.getExtensions().create(
            "manticordPlugin",
            ManticordPluginExtension.class
        );
        extension.getDescription().convention("");
        extension.getManticordVersionMinimum().convention(1);
        extension.getManticordVersionMaximumExclusive().convention(2);
        extension.getPriority().convention(0);
        extension.getUsesResources().convention(false);
        extension.getLoadMode().convention("ASYNC");
        extension.getRequirements().convention(java.util.List.of());

        project.getPlugins().withId("com.android.application", ignored ->
            project.getExtensions().getByType(ApplicationAndroidComponentsExtension.class)
                .finalizeDsl(android -> {
                    configureManifest(android, extension);
                    return Unit.INSTANCE;
                })
        );
    }

    private static void configureManifest(ApplicationExtension android, ManticordPluginExtension extension) {
        String id = extension.getId().get();
        String entrypoint = extension.getEntrypoint().get();
        if (!id.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("manticordPlugin.id is invalid");
        if (entrypoint.isBlank()) throw new IllegalArgumentException("manticordPlugin.entrypoint must not be blank");
        String loadMode = extension.getLoadMode().get();
        if (!loadMode.equals("ASYNC") && !loadMode.equals("BLOCKING")) {
            throw new IllegalArgumentException("manticordPlugin load mode must be ASYNC or BLOCKING");
        }
        int manticordMinimum = extension.getManticordVersionMinimum().get();
        int manticordMaximum = extension.getManticordVersionMaximumExclusive().get();
        if (manticordMinimum < 1 || manticordMaximum <= manticordMinimum) {
            throw new IllegalArgumentException("manticordPlugin app version range is invalid");
        }
        Set<String> requirementIds = new HashSet<>();
        for (String requirement : extension.getRequirements().get()) {
            String requirementId = requirement.substring(0, requirement.indexOf(':'));
            if (!requirementIds.add(requirementId)) {
                throw new IllegalArgumentException("manticordPlugin requirements must be unique");
            }
        }

        Map<String, Object> placeholders = new LinkedHashMap<>();
        placeholders.put("manticordPluginId", id);
        placeholders.put("manticordPluginEntrypoint", entrypoint);
        placeholders.put("manticordPluginDescription", extension.getDescription().get());
        placeholders.put("manticordPluginVersionMinimum", manticordMinimum);
        placeholders.put("manticordPluginVersionMaximumExclusive", manticordMaximum);
        placeholders.put("manticordPluginPriority", extension.getPriority().get());
        placeholders.put("manticordPluginUsesResources", extension.getUsesResources().get());
        placeholders.put("manticordPluginLoadMode", loadMode);
        placeholders.put("manticordPluginRequirements", String.join(";", extension.getRequirements().get()));

        android.getDefaultConfig()
            .getManifestPlaceholders()
            .putAll(placeholders);
    }
}
