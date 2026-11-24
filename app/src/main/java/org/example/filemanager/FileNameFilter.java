package org.example.filemanager;

import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;

/** One filter grammar for the quick and menu entry points. */
final class FileNameFilter {
    private final String query;
    private final Pattern pattern;
    private final boolean inverted;

    private FileNameFilter(String query, Pattern pattern, boolean inverted) {
        this.query = query; this.pattern = pattern; this.inverted = inverted;
    }

    static FileNameFilter compile(String input, boolean explicitRegex) {
        String expression = input;
        boolean inverse = false, regex = explicitRegex;
        // The explicit checkbox keeps old saved regular expressions literal, including leading ! or /.
        if (!explicitRegex) {
            if (expression.startsWith("\\!") || expression.startsWith("\\/")) expression = expression.substring(1);
            else {
                if (expression.startsWith("!")) { inverse = true; expression = expression.substring(1); }
                if (expression.startsWith("/")) { regex = true; expression = expression.substring(1); }
            }
        }
        return new FileNameFilter(expression.toLowerCase(Locale.ROOT), regex && !expression.isEmpty()
                ? Pattern.compile(expression, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE) : null,
                inverse && !expression.isEmpty());
    }

    boolean background() { return pattern != null; }

    boolean matches(String name) {
        if (query.isEmpty()) return true;
        boolean match = pattern != null ? pattern.matcher(new InterruptibleText(name)).find()
                : name.toLowerCase(Locale.ROOT).contains(query);
        return inverted != match;
    }

    private record InterruptibleText(String value) implements CharSequence {
        public int length() { return value.length(); }
        public char charAt(int index) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            return value.charAt(index);
        }
        public CharSequence subSequence(int start, int end) { return new InterruptibleText(value.substring(start, end)); }
        public String toString() { return value; }
    }
}
