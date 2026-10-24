package com.nux.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * MessagesTest proves every catalogue agrees on its keys, but it can't see a string that
 * never went through the catalogue: whole windows shipped in English while their keys
 * sat unused. This scans the sources for user-facing text handed straight to a control
 * or a palette entry. Units, symbols and the product name are fine; words are not.
 */
class NoLiteralUiTextTest {

    /** A control constructor or text setter, or a palette registration, given a literal with a word in it. */
    private static final Pattern LITERAL_TEXT = Pattern.compile(
            "(new (Label|Button|CheckBox|MenuItem|MenuButton|Tooltip|Tab|TitledPane|Hyperlink)"
                    + "|\\.set(Text|PromptText|Title|HeaderText|ContentText)"
                    + "|registry\\.register\\([^,]+,)\\s*\\(?\"([^\"]*[A-Za-z]{2,}[^\"]*)\"");

    /** Literal texts that are not language: the product name. */
    private static final List<String> ALLOWED = List.of("Nux");

    @Test
    void userFacingTextGoesThroughTheCatalogue() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java/com/nux"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher m = LITERAL_TEXT.matcher(lines.get(i));
                    while (m.find()) {
                        if (!ALLOWED.contains(m.group(4))) {
                            offenders.add(file.getFileName() + ":" + (i + 1) + "  \"" + m.group(4) + "\"");
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), offenders, "use Messages.tr(key) for these");
    }
}
