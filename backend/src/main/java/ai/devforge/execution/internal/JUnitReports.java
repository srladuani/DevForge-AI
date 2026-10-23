package ai.devforge.execution.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Totals from JUnit-style XML reports (Gradle, Maven Surefire, Jest/Karma reporters). Report files are untrusted: DTDs and entities are off. */
final class JUnitReports {
    record Totals(int tests, int failures, int errors, int skipped, List<String> failing) {}

    private static final Pattern REPORT = Pattern.compile("(^|/)(build/test-results/[^/]+|target/surefire-reports|target/failsafe-reports|test-results|reports|junit)/([^/]*/)*[^/]*\\.xml$|(^|/)junit[^/]*\\.xml$");
    private static final int MAX_FILES = 2000;

    private JUnitReports() {}

    static Totals read(Path root) {
        int tests = 0, failures = 0, errors = 0, skipped = 0, files = 0;
        List<String> failing = new ArrayList<>();
        boolean any = false;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk.filter(f -> !f.toString().contains("/node_modules/") && Files.isRegularFile(f) && REPORT.matcher(root.relativize(f).toString().replace('\\', '/')).find())::iterator) {
                if (++files > MAX_FILES) break;
                Element top = parse(p);
                if (top == null) continue;
                NodeList suites = top.getTagName().equals("testsuites") ? top.getElementsByTagName("testsuite") : null;
                List<Element> list = new ArrayList<>();
                if (suites == null) list.add(top);
                else for (int i = 0; i < suites.getLength(); i++) if (suites.item(i) instanceof Element e && e.getParentNode() == top) list.add(e);
                for (Element s : list) {
                    any = true;
                    tests += num(s, "tests");
                    failures += num(s, "failures");
                    errors += num(s, "errors");
                    skipped += num(s, "skipped");
                    NodeList cases = s.getElementsByTagName("testcase");
                    for (int i = 0; i < cases.getLength() && failing.size() < 10; i++) {
                        Element c = (Element) cases.item(i);
                        if (c.getElementsByTagName("failure").getLength() + c.getElementsByTagName("error").getLength() > 0) failing.add(c.getAttribute("classname") + "." + c.getAttribute("name"));
                    }
                }
            }
        } catch (IOException e) {
            // unreadable tree: report what was found
        }
        return any ? new Totals(tests, failures, errors, skipped, failing) : new Totals(-1, 0, 0, 0, List.of());
    }

    private static Element parse(Path p) {
        try {
            if (Files.size(p) > 20_000_000) return null;
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            Element e = f.newDocumentBuilder().parse(p.toFile()).getDocumentElement();
            return e.getTagName().equals("testsuite") || e.getTagName().equals("testsuites") ? e : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static int num(Element e, String attr) {
        try {
            return (int) Double.parseDouble(e.getAttribute(attr));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
