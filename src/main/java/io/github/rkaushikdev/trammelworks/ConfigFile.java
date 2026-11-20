package io.github.rkaushikdev.trammelworks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;

/**
 * Reading the site config off disk, and letting the estate override it.
 *
 * <p>Commissioning writes {@code /etc/stanchion/site.yaml} once. After that the only way anyone
 * changes anything on a live forecourt is an environment variable pushed by the fleet agent, so the
 * overlay order is file, then environment, and the environment can only set the scalars named in
 * {@link #ENV_OVERRIDES}. A typo in an override is an error rather than a new setting nobody reads.
 */
public final class ConfigFile {

    /** Every override starts with this. */
    public static final String ENV_PREFIX = "STANCHION_";

    /**
     * Environment name to section and key; an empty section means the top level. The list is
     * deliberately short: everything on it is something a support engineer has needed to change on
     * a live site.
     */
    public static final Map<String, List<String>> ENV_OVERRIDES = orderedOverrides();

    private ConfigFile() {}

    private static Map<String, List<String>> orderedOverrides() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        out.put("STANCHION_SITE", List.of("", "site"));
        out.put("STANCHION_LOG_ROOT", List.of("ingest", "log_root"));
        out.put("STANCHION_POLL_INTERVAL", List.of("ingest", "poll_interval"));
        out.put("STANCHION_SPOOL_DIR", List.of("spool", "directory"));
        out.put("STANCHION_SPOOL_BUDGET", List.of("spool", "budget"));
        out.put("STANCHION_ENDPOINT", List.of("ship", "endpoint"));
        out.put("STANCHION_MONTHLY_ALLOWANCE", List.of("ship", "monthly_allowance"));
        out.put("STANCHION_BATCH", List.of("ship", "batch"));
        return java.util.Collections.unmodifiableMap(out);
    }

    /** Parse a config document, refusing anything that is not a mapping. */
    public static Map<String, Object> parseYaml(String text) {
        Object loaded;
        try {
            loaded = new Yaml(new PlainConstructor()).load(text);
        } catch (YAMLException exc) {
            throw new ConfigError(
                    "config is not valid yaml", String.valueOf(exc.getMessage()).replace("\n", " "));
        }
        if (loaded == null) {
            throw new ConfigError("config file is empty");
        }
        return Sections.mapping(loaded, "config must be a mapping", typeName(loaded));
    }

    private static String typeName(Object value) {
        return switch (value) {
            case String text -> "str";
            case Boolean flag -> "bool";
            case Double number -> "float";
            case Number number -> "int";
            case Collection<?> items -> "list";
            default -> value.getClass().getSimpleName();
        };
    }

    /** Overlay the permitted variables of this process's environment onto a parsed mapping. */
    public static Map<String, Object> applyEnv(Map<String, ?> raw) {
        return applyEnv(raw, System.getenv());
    }

    /** Overlay the permitted variables of {@code environ} onto a parsed mapping, leaving it untouched. */
    public static Map<String, Object> applyEnv(Map<String, ?> raw, Map<String, String> environ) {
        Map<String, Object> merged = new LinkedHashMap<>();
        raw.forEach(
                (key, value) -> merged.put(key, value instanceof Map<?, ?> block ? new LinkedHashMap<>(block) : value));
        Map<String, String> ordered = new TreeMap<>(Json::compareCodePoints);
        ordered.putAll(environ);
        for (Map.Entry<String, String> entry : ordered.entrySet()) {
            String name = entry.getKey();
            if (!name.startsWith(ENV_PREFIX)) {
                continue;
            }
            List<String> target = ENV_OVERRIDES.get(name);
            if (target == null) {
                throw new ConfigError("unknown environment override", name);
            }
            String section = target.get(0);
            String key = target.get(1);
            if (section.isEmpty()) {
                merged.put(key, entry.getValue());
                continue;
            }
            Map<String, Object> block = new LinkedHashMap<>();
            if (merged.get(section) instanceof Map<?, ?> current) {
                current.forEach((inner, value) -> block.put(String.valueOf(inner), value));
            }
            block.put(key, entry.getValue());
            merged.put(section, block);
        }
        return merged;
    }

    /** Load, overlay with this process's environment, and validate the config for this site. */
    public static SiteConfig loadSiteConfig(Path path) {
        return loadSiteConfig(path, System.getenv());
    }

    /** Load, overlay and validate the config for this site. */
    public static SiteConfig loadSiteConfig(Path path, Map<String, String> environ) {
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (NoSuchFileException exc) {
            throw new ConfigError("config file not found", path.toString());
        } catch (IOException exc) {
            String detail = Files.isDirectory(path)
                    ? "[Errno 21] Is a directory: " + Repr.of(path.toString())
                    : String.valueOf(exc.getMessage());
            throw new ConfigError("config file unreadable", detail);
        }
        return SiteConfig.siteFromMapping(applyEnv(parseYaml(text), environ));
    }

    /** Render a config back to YAML, for the doctor command and for support. */
    public static String dumpSiteConfig(SiteConfig config) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setAllowUnicode(false);
        return new Yaml(new PlainConstructor(), new PlainRepresenter(options), options).dump(sorted(config.asDict()));
    }

    private static Object sorted(Object value) {
        if (value instanceof Map<?, ?> block) {
            Map<String, Object> out = new TreeMap<>(Json::compareCodePoints);
            block.forEach((key, inner) -> out.put(String.valueOf(key), sorted(inner)));
            return out;
        }
        if (value instanceof Collection<?> items) {
            List<Object> out = new ArrayList<>();
            items.forEach(item -> out.add(sorted(item)));
            return out;
        }
        return value;
    }

    /** The safe constructor, with timestamps left as the text that was written. */
    private static final class PlainConstructor extends SafeConstructor {
        PlainConstructor() {
            super(new LoaderOptions());
            yamlConstructors.put(Tag.TIMESTAMP, new ConstructYamlStr());
        }
    }

    /** The representer, writing floats in their shortest round tripping spelling. */
    private static final class PlainRepresenter extends Representer {
        PlainRepresenter(DumperOptions options) {
            super(options);
            representers.put(Double.class, data -> representScalar(Tag.FLOAT, floatText((Double) data)));
        }
    }

    private static String floatText(double value) {
        if (Double.isNaN(value)) {
            return ".nan";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? ".inf" : "-.inf";
        }
        String text = Repr.of(value).toLowerCase(java.util.Locale.ROOT);
        if (!text.contains(".") && text.contains("e")) {
            text = text.replaceFirst("e", ".0e");
        }
        return text;
    }
}
