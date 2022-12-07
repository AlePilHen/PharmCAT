package org.pharmgkb.pharmcat.haplotype;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.invoke.MethodHandles;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Sets;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.pharmgkb.common.util.CliHelper;
import org.pharmgkb.common.util.IoUtils;
import org.pharmgkb.pharmcat.definition.DefinitionReader;
import org.pharmgkb.pharmcat.definition.model.DefinitionExemption;
import org.pharmgkb.pharmcat.definition.model.VariantLocus;
import org.pharmgkb.pharmcat.haplotype.model.BaseMatch;
import org.pharmgkb.pharmcat.haplotype.model.DiplotypeMatch;
import org.pharmgkb.pharmcat.haplotype.model.GeneCall;
import org.pharmgkb.pharmcat.haplotype.model.Result;
import org.pharmgkb.pharmcat.util.DataManager;


/**
 * This class runs all autogenerated test VCFs against the {@link NamedAlleleMatcher}.
 * <ul>
 *   <li>By default, test will pass if expected result is one of the highest matching results from the
 *     {@link NamedAlleleMatcher}.</li>
 *   <li>In fuzzy-match mode, test will pass if expected result matches any result from the
 *     {@link NamedAlleleMatcher}.</li>
 *   <li>In exact-match mode, test will only pass if the {@link NamedAlleleMatcher} produces a single result that
 *     matches the expected result.</li>
 * </ul>
 *
 * @author Mark Woon
 */
public class AutogeneratedVcfTester implements AutoCloseable {
  private static final ResultSerializer sf_resultSerializer = new ResultSerializer();
  private final Path m_exemptionsFile;
  private final Path m_outputDir;
  private final boolean m_saveData;
  private final boolean m_exactMatchOnly;
  private final boolean m_fuzzyMatch;
  private final boolean m_skipCyp2d6;
  private int m_numTests;
  private final boolean m_quiet;
  private final ErrorWriter m_errorWriter;


  private AutogeneratedVcfTester(Path outputDir, boolean saveData, boolean exactMatchOnly, boolean fuzzyMatch,
      boolean skipCyp2d6) throws IOException {
    Preconditions.checkArgument(!(exactMatchOnly && fuzzyMatch));
    m_exemptionsFile = DataManager.DEFAULT_DEFINITION_DIR.resolve(DataManager.EXEMPTIONS_JSON_FILE_NAME);
    if (!Files.isRegularFile(m_exemptionsFile)) {
      throw new IllegalStateException("Cannot find exemptions file: " + m_exemptionsFile);
    }
    m_outputDir = outputDir;
    m_errorWriter = new ErrorWriter(m_outputDir);
    m_saveData = saveData;
    m_exactMatchOnly = exactMatchOnly;
    m_fuzzyMatch = fuzzyMatch;
    m_skipCyp2d6 = skipCyp2d6;
    m_quiet = Boolean.parseBoolean(System.getenv("PHARMCAT_TEST_QUIET"));
  }

  @Override
  public void close() {
    IoUtils.closeQuietly(m_errorWriter);
  }


  public static void main(String[] args) {
    try {
      CliHelper cliHelper = new CliHelper(MethodHandles.lookup().lookupClass())
          .addOption("vcf", "vcf-dir", "test VCF directory", true, "vcf")
          .addOption("o", "output-dir", "output directory", true, "o")
          .addOption("g", "gene", "restrict to specific gene", false, "g")
          .addOption("s", "save", "save all results")
          .addOption("e", "exact-match-only", "only pass if matcher produces single exact match")
          .addOption("f", "fuzzy-match", "pass if matcher produces any match")
          .addOption("nocyp2d6", "no-cyp2d6", "skip CYP2D6 tests")
          ;

      cliHelper.execute(args, cli -> {
        try{
          Path vcfDir = cliHelper.getValidDirectory("vcf", false);
          Path outputDir = cliHelper.getValidDirectory("o", true);
          boolean exact = cliHelper.hasOption("e");
          boolean fuzzy = cliHelper.hasOption("f");
          if (exact && fuzzy) {
            System.out.println("exact-match-only and fuzzy-match are mutually exclusive");
            return 1;
          }

          try (AutogeneratedVcfTester tester = new AutogeneratedVcfTester(outputDir, cliHelper.hasOption("s"),
              exact, fuzzy, cliHelper.hasOption("nocyp2d6"))) {
            Stopwatch stopwatch = Stopwatch.createStarted();

            if (cliHelper.hasOption("g")) {
              Path geneDir = vcfDir.resolve(Objects.requireNonNull(cliHelper.getValue("g")));
              if (Files.isDirectory(geneDir)) {
                System.out.println(geneDir + " is not a valid directory");
              }
              tester.testGene(geneDir);
            } else {
              tester.testAllGenes(vcfDir);
            }

            stopwatch.stop();
            System.out.println("Done.");
            System.out.println(DurationFormatUtils.formatDurationHMS(stopwatch.elapsed(TimeUnit.MILLISECONDS)));
          }
          return 0;

        } catch (Exception ex) {
          ex.printStackTrace();
          return 1;
        }
      });
    } catch (Exception ex) {
      ex.printStackTrace();
    }
  }


  private void testAllGenes(Path vcfDir) throws IOException {
    Stopwatch stopwatch = Stopwatch.createStarted();

    try (DirectoryStream<Path> geneDirStream = Files.newDirectoryStream(vcfDir)) {
      for (Path geneDir : geneDirStream) {
        if (Files.isDirectory(geneDir)) {
          if (geneDir.getFileName().toString().equals("CYP2D6") && m_skipCyp2d6) {
            continue;
          }
          testGene(geneDir);
        }
      }
    }

    String elapsedTime = DurationFormatUtils.formatDurationHMS(stopwatch.elapsed(TimeUnit.MILLISECONDS));
    m_errorWriter.printSummary(m_numTests, elapsedTime);
  }

  private void testGene(Path geneDir) throws IOException {
    String gene = geneDir.getFileName().toString();
    System.out.println("Testing " + gene + "...");
    Path definitionFile = DataManager.DEFAULT_DEFINITION_DIR.resolve(gene + "_translation.json");
    if (!Files.isRegularFile(definitionFile)) {
      throw new IllegalStateException("Cannot find definition file for " + gene + ": " + definitionFile);
    }
    DefinitionReader definitionReader = new DefinitionReader();
    definitionReader.read(definitionFile);
    definitionReader.readExemptions(m_exemptionsFile);
    DefinitionExemption exemptions = definitionReader.getExemption(gene);
    NamedAlleleMatcher namedAlleleMatcher = new NamedAlleleMatcher(definitionReader, false, false, true);

    m_errorWriter.startGene(gene);
    int x = 0;
    Stopwatch stopwatch = Stopwatch.createStarted();
    try (DirectoryStream<Path> vcfStream = Files.newDirectoryStream(geneDir)) {
      for (Path vcfFile : vcfStream) {
        if (VcfReader.isVcfFile(vcfFile)) {
          x += 1;
          try {
            test(gene, namedAlleleMatcher, vcfFile, definitionReader, exemptions);
          } catch (RuntimeException ex) {
            throw new RuntimeException("Error on " + vcfFile, ex);
          }
          if (!m_quiet && x % 1000 == 0) {
            System.out.println("  " + x + " in " +
                DurationFormatUtils.formatDurationHMS(stopwatch.elapsed(TimeUnit.MILLISECONDS)));
          }
        }
      }
    }
    m_errorWriter.endGene();
    if (!m_quiet) {
      System.out.println("  " + x + " in " +
          DurationFormatUtils.formatDurationHMS(stopwatch.elapsed(TimeUnit.MILLISECONDS)));
    }
  }


  private void test(String gene, NamedAlleleMatcher namedAlleleMatcher, Path vcfFile, DefinitionReader definitionReader,
      @Nullable DefinitionExemption exemption) throws IOException {

    m_numTests += 1;
    VcfReader vcfReader = new VcfReader(vcfFile);
    String expectedDiplotype = vcfReader.getVcfMetadata().getRawProperties().get("PharmCATnamedAlleles").get(0);

    List<String> expectedAlleles = Arrays.asList(expectedDiplotype.split("/"));
    Collections.sort(expectedAlleles);
    expectedDiplotype = String.join("/", expectedAlleles);

    boolean hasUnknownCall = expectedAlleles.contains("?");
    boolean hasComboCall = !hasUnknownCall && vcfFile.getFileName().toString().contains("noCall");
    Result result = namedAlleleMatcher.call(vcfFile);

    if (gene.equals("DPYD")) {
      if (result.getGeneCalls().get(0).getDiplotypes().isEmpty()) {
        if (!hasUnknownCall) {
          testDpyd(vcfFile, exemption, result, expectedDiplotype, expectedAlleles);
        }
      } else {
        test(gene, vcfFile, definitionReader, exemption, result, expectedDiplotype, hasUnknownCall, hasComboCall);
      }
    } else {
      test(gene, vcfFile, definitionReader, exemption, result, expectedDiplotype, hasUnknownCall, hasComboCall);
    }
    if (m_saveData) {
      saveData(vcfFile, result);
    }
  }


  private void testDpyd(Path vcfFile, @Nullable DefinitionExemption exemption, Result result,
      String expectedDiplotype, List<String> expectedAlleles) throws IOException {

    Set<String> matches = result.getGeneCalls().get(0).getHaplotypes().stream()
        .map(BaseMatch::getName)
        .collect(Collectors.toSet());
    boolean gotAll = true;
    for (String a : expectedAlleles) {
      if (!matches.remove(a)) {
        gotAll = false;
        break;
      }
    }

    if (!gotAll) {
      fail(vcfFile, result, Collections.emptyList(), Collections.emptyList(),
          expectedDiplotype, "Found " + String.join(", ", matches), exemption);
      return;
    }
    if (m_exactMatchOnly && matches.size() != expectedAlleles.size()) {
      warn(vcfFile, result, Collections.emptyList(), Collections.emptyList(),
          expectedDiplotype, "Found " + String.join(", ", matches), exemption);
    }
  }


  private void test(String gene, Path vcfFile, DefinitionReader definitionReader,
      @Nullable DefinitionExemption exemption, Result result, String expectedDiplotype,
      boolean hasUnknownCall, boolean hasComboCall) throws IOException {

    Set<DiplotypeMatch> matches = result.getGeneCalls().get(0).getDiplotypes();
    boolean gotExpected = false;
    boolean gotExpectedInTopPair = false;
    List<DiplotypeMatch> topPairs = new ArrayList<>();
    List<DiplotypeMatch> alternatePairs = new ArrayList<>();
    if (matches.size() > 0) {
      int topScore = matches.iterator().next().getScore();
      for (DiplotypeMatch match : matches) {
        boolean isMatch = isMatch(expectedDiplotype, match);
        if (isMatch) {
          gotExpected = true;
        }
        if (match.getScore() == topScore) {
          topPairs.add(match);
          if (isMatch) {
            gotExpectedInTopPair = true;
          }
        } else {
          alternatePairs.add(match);
        }
      }
    }

    if (hasUnknownCall || hasComboCall) {
      if (topPairs.size() > 0) {
        fail(vcfFile, result, topPairs, alternatePairs, "no call (" + expectedDiplotype + ")", null, exemption);
      }
      return;
    }

    if (m_exactMatchOnly) {
      // expect only one result
      if (matches.size() > 1) {
        String extraMsg = null;
        if (gotExpected) {
          List<String> errors = checkOverlaps(definitionReader.getPositions(gene), matches);
          if (errors.size() > 0) {
            if (errors.size() == 1) {
              extraMsg = errors.get(0);
            } else {
              extraMsg = String.join("\n  ", errors);
            }
          } else {
            warn(vcfFile, result, topPairs, alternatePairs, expectedDiplotype, null, exemption);
          }
        }
        fail(vcfFile, result, topPairs, alternatePairs, expectedDiplotype, extraMsg, exemption);

      } else if (!gotExpectedInTopPair) {
        fail(vcfFile, result, topPairs, alternatePairs, expectedDiplotype, null, exemption);
      }

    } else if (m_fuzzyMatch) {
      if (!gotExpected) {
        fail(vcfFile, result, topPairs, alternatePairs, expectedDiplotype, null, exemption);
      }

    } else {
      if (!gotExpectedInTopPair) {
        fail(vcfFile, result, topPairs, alternatePairs, expectedDiplotype, null, exemption);
      } else if (topPairs.size() > 1) {
        warn(vcfFile, result, topPairs, alternatePairs, expectedDiplotype, null, exemption);
      }
    }
  }

  private List<String> checkOverlaps(VariantLocus[] positions, Set<DiplotypeMatch> matches) {
    //noinspection UnstableApiUsage
    Set<Set<DiplotypeMatch>> combinations = Sets.combinations(matches, 2);
    List<String> overlaps = new ArrayList<>();
    for (Set<DiplotypeMatch> combo : combinations) {
      Iterator<DiplotypeMatch> it = combo.iterator();
      DiplotypeMatch m1 = it.next();
      DiplotypeMatch m2 = it.next();
      int size = m1.getHaplotype1().getHaplotype().getAlleles().length;
      String[] alleles1 = new String[size];
      String[] alleles2 = new String[size];
      for (int x = 0; x < size; x += 1) {
        alleles1[x] = buildAllele(m1, x);
        alleles2[x] = buildAllele(m2, x);
      }
      if (!Arrays.equals(alleles1, alleles2)) {
        StringBuilder errBuilder = new StringBuilder();
        for (int x = 0; x < size; x += 1) {
          if (!Objects.requireNonNull(alleles1[x]).equals(alleles2[x])) {
            if (errBuilder.length() > 0) {
              errBuilder.append("\n");
            }
            errBuilder.append("  Mismatch in ")
                .append(positions[x])
                .append(": ")
                .append(alleles1[x])
                .append(" vs. ")
                .append(alleles2[x]);
          }
        }
        overlaps.add(m1.getName() + " and " + m2.getName() + " DO NOT overlap:\n" + errBuilder);
      }
    }
    return overlaps;
  }

  private String buildAllele(DiplotypeMatch m, int x) {
    SortedSet<String> bases = new TreeSet<>();
    if (m.getHaplotype1().getHaplotype().getAlleles()[x] != null) {
      String allele = m.getHaplotype1().getHaplotype().getAlleles()[x];
      if (allele.length() == 1) {
        bases.addAll(Iupac.lookup(allele).getBases());
      } else {
        bases.add(allele);
      }
    }
    if (m.getHaplotype2() != null && m.getHaplotype2().getHaplotype().getAlleles()[x] != null) {
      String allele = m.getHaplotype2().getHaplotype().getAlleles()[x];
      if (allele.length() == 1) {
        bases.addAll(Iupac.lookup(allele).getBases());
      } else {
        bases.add(allele);
      }
    }
    if (bases.size() > 0) {
      return String.join(",", bases);
    }
    return null;
  }


  /**
   * Checks if diplotype matches expected alleles.
   */
  private static boolean isMatch(String expected, DiplotypeMatch match) {
    List<String> rezAlleles = Arrays.asList(match.getName().split("/"));
    Collections.sort(rezAlleles);
    String rez = String.join("/", rezAlleles);
    return rez.equals(expected);
  }


  private void warn(Path vcfFile, Result result, List<DiplotypeMatch> topPairs, List<DiplotypeMatch> alternatePairs,
      String expected, @Nullable String extraMsg, @Nullable DefinitionExemption exemption) throws IOException {
    addError(vcfFile, result, topPairs, alternatePairs, expected, extraMsg, exemption, true);
  }

  private void fail(Path vcfFile, Result result, List<DiplotypeMatch> topPairs, List<DiplotypeMatch> alternatePairs,
      String expected, @Nullable String extraMsg, @Nullable DefinitionExemption exemption) throws IOException {
    addError(vcfFile, result, topPairs, alternatePairs, expected, extraMsg, exemption, false);
  }

  private void addError(Path vcfFile, Result result, List<DiplotypeMatch> topPairs, List<DiplotypeMatch> alternatePairs,
      String expected, @Nullable String extraMsg, @Nullable DefinitionExemption exemption, boolean warn) throws IOException {

    ErrorMessage errorMessage = new ErrorMessage(vcfFile, result, topPairs, alternatePairs, expected, extraMsg,
        exemption, warn, m_quiet);
    m_errorWriter.print(errorMessage);

    saveData(vcfFile, result);
  }

  private void saveData(Path vcfFile, Result result) throws IOException {
    String baseFilename = com.google.common.io.Files.getNameWithoutExtension(vcfFile.getFileName().toString());
    Files.copy(vcfFile, m_outputDir.resolve(vcfFile.getFileName()), StandardCopyOption.REPLACE_EXISTING);
    sf_resultSerializer.toJson(result, m_outputDir.resolve(baseFilename + ".json"));
    sf_resultSerializer.toHtml(result, m_outputDir.resolve(baseFilename + ".html"));
  }

  private static boolean isFuzzyMatch(List<String> expectedAlleles, Collection<DiplotypeMatch> matches) {
    Collections.sort(expectedAlleles);
    String expected = String.join("/", expectedAlleles);
    return matches.stream()
        .anyMatch(dm -> isMatch(expected, dm));
  }


  private static class ErrorMessage implements Comparable<ErrorMessage> {
    String key;
    String baseFilename;
    String gene;
    String missingPositions;
    boolean isWarning;
    String expected;
    String actual;
    String alt;
    String message;
    boolean noAlt;
    boolean actualFuzzyMatch;
    boolean altFuzzyMatch;
    String status;

    ErrorMessage(Path vcfFile, Result result, List<DiplotypeMatch> topPairs, List<DiplotypeMatch> alternatePairs,
        String expectedDiplotype, @Nullable String extraMsg, @Nullable DefinitionExemption exemption, boolean warn,
        boolean quiet) {

      baseFilename = com.google.common.io.Files.getNameWithoutExtension(vcfFile.getFileName().toString());
      GeneCall geneCall = result.getGeneCalls().get(0);
      gene = geneCall.getGene();
      MatchData matchData = geneCall.getMatchData();
      StringBuilder keyBuilder = new StringBuilder(geneCall.getGene());
      if (matchData.getMissingPositions().size() > 0) {
        missingPositions = matchData.getMissingPositions().stream()
            .map(VariantLocus::toString)
            .collect(Collectors.joining(", "));
        keyBuilder.append("-")
            .append(missingPositions);
      }
      key = keyBuilder.append("-")
          .append(alternatePairs.size())
          .append("-")
          .append(baseFilename)
          .toString();

      expected = expectedDiplotype;
      List<String> expectedAlleles = Arrays.asList(expectedDiplotype.split("/"));

      String type = warn ? " [WARNING]" : " [FAILURE]";
      isWarning = warn;
      actual = topPairs.stream()
          .map(DiplotypeMatch::getName)
          .collect(Collectors.joining(", "));
      actualFuzzyMatch = isFuzzyMatch(expectedAlleles, topPairs);
      if (alternatePairs.size() > 0) {
        alt = alternatePairs.stream()
            .map(m -> m.getName() + " (" + m.getScore() + ")")
            .collect(Collectors.joining(", "));
        altFuzzyMatch = isFuzzyMatch(expectedAlleles, alternatePairs);
      } else {
        noAlt = true;
      }

      if (actualFuzzyMatch) {
        status = "actual fuzzy match";
      } else if (altFuzzyMatch) {
        status = "alt fuzzy match";
      } else {
        status = "no match";
      }

      if (!quiet) {
        System.out.print("* " + baseFilename);
        if (missingPositions != null) {
          System.out.print(" - missing: " + missingPositions);
        }
        System.out.println(type);
        System.out.println("  Expected: " + expectedDiplotype);
        System.out.println("    Actual: " + actual);
        if (alt != null) {
          System.out.println("      Alts: " + alt);
        }
        System.out.println("    Status: " + status);
        if (extraMsg != null) {
          System.out.println("  " + extraMsg);
        }
        System.out.println();
      }

      StringBuilder errBuilder = new StringBuilder()
          .append(baseFilename);
      if (missingPositions != null) {
        errBuilder.append(" - missing: ")
            .append(missingPositions);
      }
      errBuilder.append(type)
          .append("\n")
          .append("  Expected: ").append(expectedDiplotype)
          .append("\n")
          .append("    Actual: ").append(actual);
      if (topPairs.size() > 0) {
        errBuilder.append(" (")
            .append(topPairs.get(0).getScore())
            .append(")");
      }
      errBuilder.append("\n");
      if (alt != null) {
        errBuilder.append("      Alts: ")
            .append(alt)
            .append("\n");
      }
      errBuilder.append("    Status: ")
          .append(status)
          .append("\n");
      if (extraMsg != null) {
        errBuilder.append(extraMsg)
            .append("\n");
      }
      if (exemption != null) {
        if (exemption.isAllHits() == Boolean.FALSE) {
          errBuilder.append("EXEMPTION: top candidates only")
              .append("\n");
        }
      }
      errBuilder.append("\n");

      message = errBuilder.toString();
    }

    @Override
    public int compareTo(ErrorMessage o) {
      return key.compareTo(o.key);
    }

    @Override
    public String toString() {
      return message;
    }
  }

  private static class ErrorWriter implements AutoCloseable {
    private final Path m_dir;
    private final PrintWriter m_writer;
    private PrintWriter m_csvWriter;
    private String m_gene;
    private final SortedSet<ErrorMessage> m_errorMessages = new TreeSet<>();
    private int m_numWarnings;
    private int m_numFailures;
    private int m_numAltNoMatch;
    private int m_numNoAltNoMatch;


    private ErrorWriter(Path dir) throws IOException {
      m_dir = dir;
      m_writer = new PrintWriter(Files.newBufferedWriter(dir.resolve("autogenerated_test_report.txt")));
    }


    @Override
    public void close() {
      IoUtils.closeQuietly(m_writer);
      if (m_csvWriter != null) {
        IoUtils.closeQuietly(m_csvWriter);
      }
    }

    private void startGene(String gene) {
      m_gene = gene;
      m_errorMessages.clear();
      m_writer.flush();
    }

    private void endGene() throws IOException {
      if (m_errorMessages.isEmpty()) {
        return;
      }

      Path file = m_dir.resolve("autogenerated_test_report-" + m_gene + ".txt");
      if (m_csvWriter == null) {
        m_csvWriter = new PrintWriter(Files.newBufferedWriter(m_dir.resolve("autogenerated_test_report.tsv")));
        m_csvWriter.println("File\tGene\tMissing Positions\tExpected\tActual\tAlt\tIs Warning\tNo Alt\tActual Fuzzy\t" +
            "Alt Fuzzy");
      }
      try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(file))) {
        int numNoAlts = 0;
        int numNoAltActualFuzzy = 0;
        int numAltAltFuzzy = 0;
        int numAltActualFuzzy = 0;

        for (ErrorMessage errorMessage : m_errorMessages) {
          writer.println(errorMessage);
          if (errorMessage.noAlt) {
            numNoAlts += 1;
            if (errorMessage.actualFuzzyMatch) {
              numNoAltActualFuzzy += 1;
            }
          } else {
            if (errorMessage.actualFuzzyMatch) {
              numAltActualFuzzy += 1;
            } else if (errorMessage.altFuzzyMatch) {
              numAltAltFuzzy += 1;
            }
          }
          if (errorMessage.isWarning) {
            m_numWarnings += 1;
          } else {
            m_numFailures += 1;
          }

          m_csvWriter.println(errorMessage.baseFilename + "\t" +
              errorMessage.gene + "\t" +
              StringUtils.stripToEmpty(errorMessage.missingPositions)  + "\t" +
              errorMessage.expected + "\t" +
              errorMessage.actual + "\t" +
              StringUtils.stripToEmpty(errorMessage.alt) + "\t" +
              errorMessage.isWarning + "\t" +
              errorMessage.noAlt + "\t" +
              errorMessage.actualFuzzyMatch + "\t" +
              errorMessage.altFuzzyMatch);
        }

        NumberFormat numFormatter = NumberFormat.getInstance();
        int total = m_errorMessages.size();
        int numAltNoMatch = total - numNoAlts - numAltActualFuzzy - numAltAltFuzzy;
        int numNoAltNoMatch = numNoAlts - numNoAltActualFuzzy;
        writer.println("");
        writer.println("# problems             = " + numFormatter.format(total));
        writer.println("# no alts              = " + numFormatter.format(numNoAlts));
        writer.println("# no alts & no match   = " + numFormatter.format(numNoAltNoMatch));
        writer.println("# with alts & no match = " + numFormatter.format(numAltNoMatch));
        m_numNoAltNoMatch += numNoAltNoMatch;
        m_numAltNoMatch += numAltNoMatch;
      }
    }

    private void print(ErrorMessage errorMessage) {
      m_errorMessages.add(errorMessage);
    }

    private void printSummary(int numTests, String elapsedTime) {
      NumberFormat numFormatter = NumberFormat.getInstance();
      m_writer.println("");
      m_writer.println("# tests    = " + numFormatter.format(numTests));
      m_writer.println("# passed   = " + numFormatter.format(numTests - m_numFailures - m_numWarnings));
      m_writer.println("# warnings = " + numFormatter.format(m_numWarnings));
      m_writer.println("# failed   = " + numFormatter.format(m_numFailures));
      m_writer.println("# no alts & no match   = " + numFormatter.format(m_numNoAltNoMatch));
      m_writer.println("# with alts & no match = " + numFormatter.format(m_numAltNoMatch));
      m_writer.println("Elapsed    = " + elapsedTime);
    }
  }
}
