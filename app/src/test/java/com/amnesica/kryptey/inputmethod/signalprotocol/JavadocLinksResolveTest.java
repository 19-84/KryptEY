package com.amnesica.kryptey.inputmethod.signalprotocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The half of the comment-drift problem that <em>can</em> be tested.
 *
 * <p>REVIVAL.md records comment drift as a defect class with no test, and it is right about the part
 * it means: a comment explaining <em>why</em> code does something cannot be checked against the code
 * by any mechanism short of reading both. That is how a comment came to explain this project's
 * contact deletion by naming {@code hasExactlyOneContactNamed} — a method whose only occurrence
 * anywhere in the codebase was that comment. It was prose, so nothing could have caught it.
 *
 * <p>But a {@code {@link}} is not prose. It is a machine-checkable claim that a named thing exists,
 * and this build never checked one: javadoc is not run, so a link to a deleted method compiles,
 * ships, and reads to the next author as a live cross-reference. There are 288 of them in the app,
 * 56 of those in the two packages this covers.
 *
 * <p>This resolves every one in the two packages where a dead pointer actually misleads somebody —
 * the protocol layer and the strip. It found none broken, which is worth stating plainly: the
 * finding here is the guard, not a defect. What it changes is that the next deleted method takes its
 * incoming links down with it instead of leaving them to describe a codebase that no longer exists.
 *
 * <p>Deliberately scoped, and not extended to the AOSP-inherited packages: those carry links to
 * platform internals ({@code SoftInputWindow}, {@code SuggestionStripView}) that were already dead
 * when the keyboard was forked, and adopting them here would mean a large allowlist that says
 * nothing about this project's own drift.
 */
public class JavadocLinksResolveTest {

  private static final String[] SECURITY_PACKAGES = {
      "com/amnesica/kryptey/inputmethod/signalprotocol",
      "com/amnesica/kryptey/inputmethod/latin/e2ee",
  };

  private static final Pattern LINK = Pattern.compile("\\{@link(?:plain)?\\s+([^}]+)\\}");
  private static final Pattern TYPE_DECL =
      Pattern.compile("\\b(?:class|interface|enum|record)\\s+(\\w+)");
  private static final Pattern MEMBER_DECL = Pattern.compile(
      "(?:public|private|protected|static|final|abstract|synchronized|native|default|\\s)+"
          + "[\\w<>\\[\\],.?\\s]+?\\s+(\\w+)\\s*[(;=]");
  private static final Pattern IMPORTED = Pattern.compile("import\\s+(?:static\\s+)?([\\w.]+);");

  private static Path mainSources() {
    for (final String candidate : new String[] {"src/main/java", "app/src/main/java"}) {
      final Path path = Paths.get(candidate);
      if (Files.isDirectory(path)) return path;
    }
    throw new IllegalStateException("could not locate the main source tree");
  }

  private static List<Path> allSources() throws IOException {
    try (Stream<Path> walk = Files.walk(mainSources())) {
      return walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
    }
  }

  private static Set<String> matches(final Pattern pattern, final String text) {
    final Set<String> found = new HashSet<>();
    final Matcher matcher = pattern.matcher(text);
    while (matcher.find()) found.add(matcher.group(1));
    return found;
  }

  @Test
  public void everyJavadocLinkInTheSecurityPackagesNamesSomethingThatExists() throws IOException {
    final List<Path> sources = allSources();

    // Every type this project declares, plus every member, indexed by declaring file.
    final Set<String> projectTypes = new HashSet<>();
    final Map<Path, Set<String>> membersOf = new HashMap<>();
    final Map<String, Path> fileForType = new HashMap<>();

    for (final Path source : sources) {
      final String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
      final String simple = source.getFileName().toString().replace(".java", "");
      projectTypes.add(simple);
      fileForType.put(simple, source);
      projectTypes.addAll(matches(TYPE_DECL, text));
      membersOf.put(source, matches(MEMBER_DECL, text));
    }

    final List<String> unresolved = new ArrayList<>();
    int checked = 0;

    for (final Path source : sources) {
      final String relative = source.toString().replace('\\', '/');
      boolean inScope = false;
      for (final String pkg : SECURITY_PACKAGES) {
        if (relative.contains(pkg)) inScope = true;
      }
      if (!inScope) continue;

      final String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
      // Simple names this file can legitimately refer to without them being ours.
      final Set<String> visible = new HashSet<>(projectTypes);
      for (final String imported : matches(IMPORTED, text)) {
        visible.add(imported.substring(imported.lastIndexOf('.') + 1));
      }

      final Matcher matcher = LINK.matcher(text);
      while (matcher.find()) {
        // A link can wrap across lines, and the continuation carries javadoc's leading asterisk
        // into the captured text. No type or member name may contain one, so dropping it is safe -
        // and not dropping it produced this test's first and only "finding", which was its own.
        final String target =
            matcher.group(1).replaceAll("\\s*\\*\\s*", " ").replaceAll("\\s+", " ").trim();
        if (target.equals("*") || target.isEmpty()) continue;
        checked++;

        final int hash = target.indexOf('#');
        final String type = hash < 0 ? target : target.substring(0, hash);
        final String member = hash < 0 ? null : target.substring(hash + 1);

        if (type.isEmpty()) {
          // {@link #member} - must be declared in this very file.
          final String bare = member == null ? "" : member.split("\\(")[0].trim();
          if (!membersOf.get(source).contains(bare)) {
            unresolved.add(source.getFileName() + ": {@link #" + member
                + "} - no such member in this file");
          }
          continue;
        }

        final String simple = type.substring(type.lastIndexOf('.') + 1);
        if (!visible.contains(simple)) {
          unresolved.add(source.getFileName() + ": {@link " + target
              + "} - no such type, and it is not imported here");
          continue;
        }

        // If we own the type, the member must exist on it.
        if (member != null && fileForType.containsKey(simple)) {
          final String bare = member.split("\\(")[0].trim();
          final Set<String> owned = membersOf.get(fileForType.get(simple));
          if (owned != null && !owned.contains(bare)) {
            unresolved.add(source.getFileName() + ": {@link " + target
                + "} - " + simple + " has no member " + bare);
          }
        }
      }
    }

    // 56 when this was written. The first version of this guard asserted "over 100" on an
    // assumption I had not counted, and failed - which is the guard working, on its author.
    assertTrue("this test resolves javadoc links; finding almost none means it has stopped testing "
        + "anything - there were 56 in these packages when it was written", checked >= 40);
    assertEquals("these javadoc links name something that does not exist. javadoc is not run by this "
        + "build, so a link to a deleted method compiles and ships, and reads to the next author as "
        + "a live cross-reference - the same way a comment came to explain contact deletion by "
        + "naming a method that had never existed:\n" + String.join("\n", unresolved),
        0, unresolved.size());
  }
}
