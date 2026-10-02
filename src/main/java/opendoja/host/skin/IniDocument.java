package opendoja.host.skin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal INI reader used for {@code skin.ini}.
 *
 * <p>Format rules:
 * <ul>
 *   <li>{@code [section]} headers. The same section name may appear any number of times; order is preserved.</li>
 *   <li>{@code key = value} pairs. Section and key names are case-insensitive.</li>
 *   <li>Whole-line comments start with {@code ;} or {@code #}.</li>
 *   <li>Inline comments start with {@code ;} preceded by whitespace. {@code #} is never an inline comment
 *       marker because it starts color codes such as {@code #D7D8DB}.</li>
 * </ul>
 */
final class IniDocument {
    private final List<Section> sections = new ArrayList<>();

    static IniDocument parse(String text) {
        IniDocument document = new IniDocument();
        Section current = null;
        String source = text == null ? "" : text;
        if (!source.isEmpty() && source.charAt(0) == '\uFEFF') {
            source = source.substring(1);
        }
        for (String rawLine : source.split("\\R", -1)) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[")) {
                int end = line.indexOf(']');
                if (end > 0) {
                    current = new Section(line.substring(1, end).strip().toLowerCase(Locale.ROOT));
                    document.sections.add(current);
                }
                continue;
            }
            int equals = line.indexOf('=');
            if (equals <= 0 || current == null) {
                // Keys outside any section and malformed lines are ignored.
                continue;
            }
            String key = line.substring(0, equals).strip().toLowerCase(Locale.ROOT);
            String value = stripInlineComment(line.substring(equals + 1)).strip();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            current.values.put(key, value);
        }
        return document;
    }

    List<Section> sections() {
        return sections;
    }

    Section first(String name) {
        for (Section section : sections) {
            if (section.name.equals(name)) {
                return section;
            }
        }
        return null;
    }

    private static String stripInlineComment(String value) {
        for (int i = 1; i < value.length(); i++) {
            if (value.charAt(i) == ';' && Character.isWhitespace(value.charAt(i - 1))) {
                return value.substring(0, i);
            }
        }
        return value;
    }

    static final class Section {
        private final String name;
        private final Map<String, String> values = new LinkedHashMap<>();

        private Section(String name) {
            this.name = name;
        }

        String name() {
            return name;
        }

        /** Returns the trimmed value, or {@code null} when the key is absent. */
        String get(String key) {
            return values.get(key);
        }

        /** Returns the first present key among {@code keys}, or {@code null}. */
        String getAny(String... keys) {
            for (String key : keys) {
                String value = values.get(key);
                if (value != null) {
                    return value;
                }
            }
            return null;
        }
    }
}
