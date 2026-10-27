package ai.devforge.agent.internal.real;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class EditToolboxTest {
    private static String file(int lines) { return IntStream.rangeClosed(1, lines).mapToObj(i -> "line " + i).collect(Collectors.joining("\n")) + "\n"; }

    @Test
    void anEditReplyShowsTheChangedLinesWithNumbersAndALittleContext() {
        String content = file(30).replace("line 10\n", "line ten\nline ten-and-a-half\n");
        String shown = EditToolbox.changedLines(content, "line ten\nline ten-and-a-half");
        assertThat(shown).startsWith(" Now:\n").contains("8: line 8").contains("10: line ten").contains("11: line ten-and-a-half").contains("13: line 12").doesNotContain("line 14").doesNotContain("line 7");
    }

    @Test
    void aLongEditIsShownOnlyUpToALimitAndSaysSo() {
        String block = file(60).replace("\n", "\n").strip();
        String shown = EditToolbox.changedLines("head\n" + block + "\ntail\n", block);
        assertThat(shown.lines().count()).isLessThanOrEqualTo(23);
        assertThat(shown).contains("edited region continues to line");
    }

    @Test
    void aDeletionIsReportedAndTextThatCannotBeLocatedAddsNothing() {
        assertThat(EditToolbox.changedLines(file(5), "")).isEqualTo(" The text was removed.");
        assertThat(EditToolbox.changedLines(file(5), "not there")).isEmpty();
    }

    @Test
    void readingAFileShowsARangeAndNeverMoreThanTheLimit() {
        String content = file(400);
        assertThat(EditToolbox.numbered(content, 10, 12)).isEqualTo("10: line 10\n11: line 11\n12: line 12\n… (lines 13-401 not shown; pass start_line and end_line)\n");
        String whole = EditToolbox.numbered(content, 1, 0);
        assertThat(whole.lines().filter(l -> l.matches("\\d+: .*")).count()).isEqualTo(150);
        assertThat(whole).contains("not shown; pass start_line and end_line");
        assertThat(EditToolbox.numbered(file(3), 1, 0)).doesNotContain("not shown");
    }
}
