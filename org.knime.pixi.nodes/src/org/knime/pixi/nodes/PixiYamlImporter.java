package org.knime.pixi.nodes;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.knime.core.node.KNIMEException;
import org.yaml.snakeyaml.Yaml;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.io.IndentStyle;
import com.electronwill.nightconfig.toml.TomlWriter;

/**
 * Utility class to import conda environment.yaml files into pixi format
 *
 * @author Carsten Haubold, KNIME GmbH, Konstanz, Germany
 * @since 5.11
 */
final class PixiYamlImporter {

    private static final List<String> ALL_PLATFORMS = Arrays.asList("win-64", "linux-64", "osx-64", "osx-arm64");

    private PixiYamlImporter() {
        // Utility class
    }

    /**
     * Convert a conda environment.yaml to a pixi.toml manifest with all platforms enabled
     *
     * @param yamlContent the conda environment.yaml content
     * @return the pixi.toml content as string with all platforms configured
     */
    @SuppressWarnings("restriction")
    public static String convertYamlToToml(final String yamlContent) {
        if (yamlContent == null || yamlContent.isBlank()) {
            throw new KNIMEException("Could not import conda environment.yaml: content is empty").toUnchecked();
        }

        try {
            final Map<String, Object> root = parseRoot(yamlContent);
            final List<String> channels = parseChannels(root.get("channels"));
            final ParsedDependencies parsedDependencies = parseDependencies(root.get("dependencies"));

            return createPixiToml(channels, parsedDependencies.condaDependencies(), parsedDependencies.pypiDependencies());
        } catch (RuntimeException e) {
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseRoot(final String yamlContent) {
        final Object parsed = new Yaml().load(yamlContent);
        if (!(parsed instanceof Map<?, ?> parsedMap)) {
            throw new KNIMEException(
                "Could not import conda environment.yaml: expected a map at the top level").toUnchecked();
        }
        final Map<String, Object> root = new LinkedHashMap<>();
        for (final Map.Entry<?, ?> entry : parsedMap.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new KNIMEException("Could not import conda environment.yaml: all top-level keys must be strings")
                    .toUnchecked();
            }
            root.put(key, entry.getValue());
        }
        return root;
    }

    private static List<String> parseChannels(final Object channelsObj) {
        if (channelsObj == null) {
            return List.of("conda-forge");
        }
        if (!(channelsObj instanceof List<?> channelsList)) {
            throw new KNIMEException("Could not import conda environment.yaml: 'channels' must be a list")
                .toUnchecked();
        }

        final List<String> channels = new ArrayList<>();
        for (final Object entry : channelsList) {
            if (!(entry instanceof String channel) || channel.isBlank()) {
                throw new KNIMEException(
                    "Could not import conda environment.yaml: channel entries must be non-empty strings").toUnchecked();
            }
            channels.add(channel);
        }

        return channels.isEmpty() ? List.of("conda-forge") : channels;
    }

    private static ParsedDependencies parseDependencies(final Object dependenciesObj) {
        if (dependenciesObj == null) {
            return new ParsedDependencies(new LinkedHashMap<>(), new LinkedHashMap<>());
        }

        if (!(dependenciesObj instanceof List<?> dependenciesList)) {
            throw new KNIMEException("Could not import conda environment.yaml: 'dependencies' must be a list")
                .toUnchecked();
        }

        final Map<String, CondaDependency> condaDependencies = new LinkedHashMap<>();
        final Map<String, String> pypiDependencies = new LinkedHashMap<>();

        for (final Object dependencyObj : dependenciesList) {
            if (dependencyObj instanceof String dependencyString) {
                final CondaDependency condaDependency = parseCondaDependency(dependencyString);
                condaDependencies.put(condaDependency.name(), condaDependency);
            } else if (dependencyObj instanceof Map<?, ?> dependencyMap) {
                parseDependencyMap(dependencyMap, pypiDependencies);
            } else {
                throw new KNIMEException(
                    "Could not import conda environment.yaml: dependencies must be package strings or a pip subsection")
                    .toUnchecked();
            }
        }

        return new ParsedDependencies(condaDependencies, pypiDependencies);
    }

    private static void parseDependencyMap(final Map<?, ?> dependencyMap, final Map<String, String> pypiDependencies) {
        if (dependencyMap.size() != 1 || !dependencyMap.containsKey("pip")) {
            throw new KNIMEException(
                "Could not import conda environment.yaml: only a single 'pip' subsection map is supported")
                .toUnchecked();
        }

        final Object pipObj = dependencyMap.get("pip");
        if (!(pipObj instanceof List<?> pipList)) {
            throw new KNIMEException("Could not import conda environment.yaml: the 'pip' subsection must be a list")
                .toUnchecked();
        }

        for (final Object pipEntryObj : pipList) {
            if (!(pipEntryObj instanceof String pipEntry)) {
                throw new KNIMEException(
                    "Could not import conda environment.yaml: pip dependency entries must be strings").toUnchecked();
            }
            final ParsedPackageSpec parsedSpec = parsePackageSpec(pipEntry);
            pypiDependencies.put(parsedSpec.name(), normalizeVersionConstraint(parsedSpec.version(), true));
        }
    }

    private static CondaDependency parseCondaDependency(final String dependencyString) {
        final ParsedPackageSpec parsedSpec = parsePackageSpec(dependencyString);
        return new CondaDependency(parsedSpec.name(), normalizeVersionConstraint(parsedSpec.version(), false),
            parsedSpec.channel());
    }

    private static ParsedPackageSpec parsePackageSpec(final String packageSpec) {
        if (packageSpec == null || packageSpec.isBlank()) {
            throw new KNIMEException("Could not import conda environment.yaml: package entries must not be blank")
                .toUnchecked();
        }

        String remaining = packageSpec.trim();
        String channel = null;

        final int channelSeparatorIndex = remaining.indexOf("::");
        if (channelSeparatorIndex >= 0) {
            channel = remaining.substring(0, channelSeparatorIndex).trim();
            remaining = remaining.substring(channelSeparatorIndex + 2).trim();
            if (channel.isBlank()) {
                throw new KNIMEException("Could not import conda environment.yaml: channel prefix must not be blank")
                    .toUnchecked();
            }
        }

        final int operatorIndex = firstOperatorIndex(remaining);
        final String packageName;
        final String versionPart;
        if (operatorIndex < 0) {
            packageName = remaining;
            versionPart = "";
        } else {
            packageName = remaining.substring(0, operatorIndex).trim();
            versionPart = remaining.substring(operatorIndex).trim();
        }

        if (packageName.isBlank()) {
            throw new KNIMEException("Could not import conda environment.yaml: package name must not be blank")
                .toUnchecked();
        }

        return new ParsedPackageSpec(packageName, versionPart, channel);
    }

    private static int firstOperatorIndex(final String spec) {
        for (int i = 0; i < spec.length(); i++) {
            final char c = spec.charAt(i);
            if (c == '=' || c == '<' || c == '>' || c == '!' || c == '~') {
                return i;
            }
        }
        return -1;
    }

    private static String normalizeVersionConstraint(final String rawVersion, final boolean isPypi) {
        if (rawVersion == null || rawVersion.isBlank()) {
            return "*";
        }

        String version = rawVersion.trim();
        if (version.startsWith("=") && !version.startsWith("==")) {
            version = version.substring(1).trim();
        }

        if (isPypi) {
            return version.isBlank() ? "*" : version;
        }

        return PixiPackageSpec
            .formatVersionConstraint(new PixiPackageSpec("pkg", PixiPackageSpec.PackageSource.CONDA, version));
    }

    private static String createPixiToml(final List<String> channels, final Map<String, CondaDependency> condaDependencies,
        final Map<String, String> pypiDependencies) {
        final Config config = Config.inMemory();

        final CommentedConfig workspace = CommentedConfig.inMemory();
        workspace.set("channels", channels);
        workspace.set("platforms", ALL_PLATFORMS);
        config.set("workspace", workspace);

        final CommentedConfig dependencies = CommentedConfig.inMemory();
        for (final CondaDependency dependency : condaDependencies.values()) {
            if (dependency.channel() != null && !dependency.channel().isBlank()) {
                final Config dependencyConfig = Config.inMemory();
                dependencyConfig.set("version", dependency.version());
                dependencyConfig.set("channel", dependency.channel());
                dependencies.set(Collections.singletonList(dependency.name()), dependencyConfig);
            } else {
                dependencies.set(Collections.singletonList(dependency.name()), dependency.version());
            }
        }
        config.set("dependencies", dependencies);

        if (!pypiDependencies.isEmpty()) {
            final CommentedConfig pypi = CommentedConfig.inMemory();
            for (final Map.Entry<String, String> entry : pypiDependencies.entrySet()) {
                pypi.set(Collections.singletonList(entry.getKey()), entry.getValue());
            }
            config.set("pypi-dependencies", pypi);
        }

        final StringWriter writer = new StringWriter();
        final TomlWriter tomlWriter = new TomlWriter();
        tomlWriter.setIndent(IndentStyle.SPACES_2);
        tomlWriter.write(config, writer);
        return writer.toString();
    }

    private record ParsedPackageSpec(String name, String version, String channel) {
    }

    private record CondaDependency(String name, String version, String channel) {
    }

    private record ParsedDependencies(Map<String, CondaDependency> condaDependencies, Map<String, String> pypiDependencies) {
    }

}
