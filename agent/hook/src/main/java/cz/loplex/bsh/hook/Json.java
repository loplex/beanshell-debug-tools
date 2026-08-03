package cz.loplex.bsh.hook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The smallest JSON this agent can get away with: enough to speak DAP, and nothing else.
 *
 * <p><b>Why not a library.</b> The hook is loaded by the bootstrap classloader so that instrumented
 * {@code bsh.*} classes can resolve it whichever loader they came from. Bootstrap sees only the JDK,
 * so a dependency here is not a matter of taste — Jackson or Gson simply would not be visible. And
 * shading one in would put a second copy of it on the bootstrap path of somebody else's JVM, which is
 * exactly the class of problem this agent has already been burned by (see agent/README.md's
 * landmines).
 *
 * <p><b>What it does and does not do.</b> Parses and writes objects, arrays, strings, numbers,
 * booleans and null; numbers come back as {@link Integer} when they fit and {@link Double} otherwise,
 * which matches how DAP uses them. It does not aim to be a general JSON implementation: no streaming,
 * no pretty-printing, no reviver hooks. DAP messages are small and their shapes are fixed, so the
 * parser is a plain recursive descent over a string that is already fully in memory.
 *
 * <p>Objects preserve insertion order ({@link LinkedHashMap}), which costs nothing and makes a logged
 * message read the way the code that built it does.
 */
final class Json {

    private Json() {
    }

    // ---------------------------------------------------------------- writing

    /** Renders a value: Map, List, String, Number, Boolean or null. */
    static String write(Object value) {
        StringBuilder sb = new StringBuilder(256);
        writeTo(sb, value);
        return sb.toString();
    }

    private static void writeTo(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString(sb, (String) value);
        } else if (value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Integer || value instanceof Long) {
            sb.append(value);
        } else if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            // JSON has no Infinity or NaN. Neither can arise from anything this agent sends, but a
            // value rendered from a script could, and producing invalid JSON would break the session
            // rather than that one value.
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
            } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                sb.append((long) d);
            } else {
                sb.append(d);
            }
        } else if (value instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(entry.getKey()));
                sb.append(':');
                writeTo(sb, entry.getValue());
            }
            sb.append('}');
        } else if (value instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object element : (List<?>) value) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeTo(sb, element);
            }
            sb.append(']');
        } else {
            // Anything else is rendered as its toString(). Only reachable by a programming mistake
            // here, and a quoted string is a far better failure than a malformed message.
            writeString(sb, String.valueOf(value));
        }
    }

    private static void writeString(StringBuilder sb, String text) {
        sb.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    // Control characters must be escaped; a script's own value may well contain them.
                    // Everything else goes out as-is and is UTF-8 encoded by the writer.
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ---------------------------------------------------------------- parsing

    /** Parses one JSON document. Throws {@link IllegalArgumentException} on anything malformed. */
    static Object parse(String text) {
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.value();
        parser.skipWhitespace();
        if (parser.position < text.length()) {
            throw new IllegalArgumentException("trailing content at " + parser.position);
        }
        return value;
    }

    /** A member of a parsed object, or null when absent or not an object at all. */
    @SuppressWarnings("unchecked")
    static Object get(Object object, String key) {
        return object instanceof Map ? ((Map<String, Object>) object).get(key) : null;
    }

    /** A member as an int, or [fallback] when absent or not a number. */
    static int getInt(Object object, String key, int fallback) {
        Object value = get(object, key);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    /** A member as a String, or [fallback] when absent or not a string. */
    static String getString(Object object, String key, String fallback) {
        Object value = get(object, key);
        return value instanceof String ? (String) value : fallback;
    }

    /** A member as a list, or an empty list when absent or not an array. */
    @SuppressWarnings("SameParameterValue")
    static List<?> getList(Object object, String key) {
        Object value = get(object, key);
        return value instanceof List<?> ? (List<?>) value : new ArrayList<>();
    }

    private static final class Parser {

        private final String text;
        private int position;

        Parser(String text) {
            this.text = text;
        }

        void skipWhitespace() {
            while (position < text.length()) {
                char c = text.charAt(position);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    position++;
                } else {
                    return;
                }
            }
        }

        Object value() {
            if (position >= text.length()) {
                throw new IllegalArgumentException("unexpected end of input");
            }
            char c = text.charAt(position);
            switch (c) {
                case '{':
                    return object();
                case '[':
                    return array();
                case '"':
                    return string();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return number();
            }
        }

        private Map<String, Object> object() {
            Map<String, Object> result = new LinkedHashMap<>();
            position++;  // '{'
            skipWhitespace();
            if (peek() == '}') {
                position++;
                return result;
            }
            while (true) {
                skipWhitespace();
                String key = string();
                skipWhitespace();
                if (peek() != ':') {
                    throw new IllegalArgumentException("expected ':' at " + position);
                }
                position++;
                skipWhitespace();
                result.put(key, value());
                skipWhitespace();
                char c = peek();
                position++;
                if (c == '}') {
                    return result;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("expected ',' or '}' at " + (position - 1));
                }
            }
        }

        private List<Object> array() {
            List<Object> result = new ArrayList<>();
            position++;  // '['
            skipWhitespace();
            if (peek() == ']') {
                position++;
                return result;
            }
            while (true) {
                skipWhitespace();
                result.add(value());
                skipWhitespace();
                char c = peek();
                position++;
                if (c == ']') {
                    return result;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("expected ',' or ']' at " + (position - 1));
                }
            }
        }

        private String string() {
            if (peek() != '"') {
                throw new IllegalArgumentException("expected a string at " + position);
            }
            position++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (position >= text.length()) {
                    throw new IllegalArgumentException("unterminated string");
                }
                char c = text.charAt(position++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char escape = text.charAt(position++);
                switch (escape) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        sb.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                        position += 4;
                        break;
                    default:
                        throw new IllegalArgumentException("bad escape \\" + escape);
                }
            }
        }

        private Object number() {
            int start = position;
            if (peek() == '-' || peek() == '+') {
                position++;
            }
            boolean floating = false;
            while (position < text.length()) {
                char c = text.charAt(position);
                if (c >= '0' && c <= '9') {
                    position++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    floating = true;
                    position++;
                } else {
                    break;
                }
            }
            String literal = text.substring(start, position);
            if (literal.isEmpty()) {
                throw new IllegalArgumentException("expected a number at " + start);
            }
            if (!floating) {
                try {
                    return Integer.valueOf(literal);
                } catch (NumberFormatException wider) {
                    // Falls through to Double, which is what DAP would do with an oversized integer.
                }
            }
            return Double.valueOf(literal);
        }

        private char peek() {
            if (position >= text.length()) {
                throw new IllegalArgumentException("unexpected end of input");
            }
            return text.charAt(position);
        }

        private void expect(String literal) {
            if (!text.startsWith(literal, position)) {
                throw new IllegalArgumentException("expected " + literal + " at " + position);
            }
            position += literal.length();
        }
    }
}
