package org.pharmgkb.pharmcat.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.invoke.MethodHandles;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.pharmgkb.common.util.CliHelper;
import org.pharmgkb.common.util.PathUtils;
import org.pharmgkb.pharmcat.definition.DefinitionReader;
import org.pharmgkb.pharmcat.definition.model.DefinitionExemption;
import org.pharmgkb.pharmcat.definition.model.DefinitionFile;
import org.pharmgkb.pharmcat.definition.model.NamedAllele;
import org.pharmgkb.pharmcat.phenotype.PhenotypeMap;
import org.pharmgkb.pharmcat.phenotype.model.GenePhenotype;
import org.pharmgkb.pharmcat.reporter.DrugCollection;
import org.pharmgkb.pharmcat.reporter.MessageHelper;
import org.pharmgkb.pharmcat.reporter.PgkbGuidelineCollection;
import org.pharmgkb.pharmcat.reporter.model.DataSource;
import org.pharmgkb.pharmcat.reporter.model.cpic.Drug;
import org.pharmgkb.pharmcat.reporter.model.cpic.Publication;
import org.pharmgkb.pharmcat.reporter.model.pgkb.Group;
import org.pharmgkb.pharmcat.reporter.model.pgkb.GuidelinePackage;
import org.pharmgkb.pharmcat.reporter.model.pgkb.Markdown;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * This class manages external resources (e.g. allele definition files, dosing guideline annotations).
 *
 * @author Mark Woon
 */
public class DataManager {
  private static final Logger sf_logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  public static final Path DEFAULT_DEFINITION_DIR = PathUtils.getPathToResource("org/pharmgkb/pharmcat/definition/alleles");
  public static final String EXEMPTIONS_JSON_FILE_NAME = "exemptions.json";
  private static final String POSITIONS_VCF = "pharmcat_positions.vcf";
  private static final String DPWG_ALLELES_FILE_NAME = "dpwg_allele_translations.json";
  private static final String CPIC_ALLELES_FILE_NAME = "allele_definitions.json";
  private static final String sf_dpwgZipFileName = "dpwg_pharmcat.zip";
  private static final String sf_googleDocUrlFmt = "https://docs.google.com/spreadsheets/d/%s/export?format=tsv";

  private final DataSerializer m_dataSerializer = new DataSerializer();
  private final boolean m_verbose;


  private DataManager(boolean verbose) {
    m_verbose = verbose;
  }


  public static void main(String[] args) {

    try {
      CliHelper cliHelper = new CliHelper(MethodHandles.lookup().lookupClass())
          .addOption("dl", "download-dir", "directory to save downloaded files", false, "dl")
          .addOption("sdl", "skip-download", "skip download")
          .addOption("a", "alleles-dir", "directory to save generated allele definition files", false, "a")
          .addOption("sa", "skip-alleles", "skip alleles")
          .addOption("g", "guidelines-dir", "directory to save guidelines data to", false, "dir")
          .addOption("sg", "skip-drugs", "skip drugs")
          .addOption("m", "messages-dir", "directory to write messages to", false, "m")
          .addOption("sm", "skip-messages", "skip messages")
          .addOption("p", "phenotypes-dir", "directory to save phenotypes to", false, "p")
          .addOption("sp", "skip-phenotypes", "skip phenotype files")
          .addOption("doc", "documentation-dir", "directory to save documentation to", false, "dir")
          ;


      if (!cliHelper.parse(args)) {
        System.exit(1);
      }

      boolean skipDownload = cliHelper.hasOption("sdl");
      Path downloadDir;
      if (cliHelper.hasOption("dl")) {
        downloadDir = cliHelper.getValidDirectory("dl", true);
        System.out.println("Downloading to " + downloadDir);
      } else {
        if (skipDownload) {
          System.out.println("Cannot skip download without providing download directory containing necessary files");
          System.exit(1);
        }
        downloadDir = Files.createTempDirectory("pharmcat");
        downloadDir.toFile().deleteOnExit();
      }

      try {
        DataManager manager = new DataManager(cliHelper.isVerbose());

        if (!cliHelper.hasOption("sm")) {
          Path messagesTsv = downloadDir.resolve("messages.tsv");
          if (!skipDownload) {
            // download messages
            FileUtils.copyURLToFile(new URL(String.format(sf_googleDocUrlFmt, "1MkWV6TlJTnw-KRNWeylyUJAUocCgupcJLlmV2fRdtcM")),
                messagesTsv.toFile());
          }

          Path messageDir = cliHelper.getValidDirectory("m", true);
          Path messagesJson = messageDir.resolve(MessageHelper.MESSAGES_JSON_FILE_NAME);
          manager.transformMessages(messagesTsv, messagesJson);
        }

        boolean skipGuidelines = cliHelper.hasOption("sg");
        boolean skipPhenotypes = cliHelper.hasOption("sp");
        boolean skipAlleles = cliHelper.hasOption("sa");
        if (!skipGuidelines || !skipPhenotypes || !skipAlleles) {
          Path dpwgZipFile = downloadDir.resolve(sf_dpwgZipFileName);
          if (!skipDownload) {
            System.out.println("Downloading DPWG data...");
            // download DPWG data
            FileUtils.copyURLToFile(
                new URL("https://s3.pgkb.org/data/" + sf_dpwgZipFileName),
                dpwgZipFile.toFile());
            ZipUtils.unzip(dpwgZipFile, downloadDir);
          }
          if (Files.exists(dpwgZipFile)) {
            ZipUtils.unzip(dpwgZipFile, downloadDir);
          } else {
            System.out.println("WARNING: Cannot find " + dpwgZipFile + " - will have to rely on unpacked content");
          }
        }

        // must get guidelines before alleles
        DrugCollection drugs;
        PgkbGuidelineCollection pgkbGuidelineCollection;
        if (!skipGuidelines) {
          Path drugsDir = cliHelper.getValidDirectory("g", true);
          Path cpicDlFile = downloadDir.resolve(DrugCollection.CPIC_FILE_NAME);
          if (!skipDownload) {
            // download CPIC guidelines
            FileUtils.copyURLToFile(
                new URL(DrugCollection.CPIC_URL),
                cpicDlFile.toFile());

          }
          // transform CPIC guidelines
          Path cpicGuidelinesDir = drugsDir.resolve("guidelines/cpic");
          manager.transformCpicGuidelines(cpicDlFile, cpicGuidelinesDir);
          drugs = new DrugCollection(cpicGuidelinesDir);
          if (!cpicGuidelinesDir.toString().equals(DrugCollection.GUIDELINES_DIR.toString())) {
            DrugCollection oldDrugs = new DrugCollection();
            oldDrugs.diff(drugs).forEach(sf_logger::info);
          }

          // transform DPWG guidelines
          Path dpwgGuidelinesDir = drugsDir.resolve("guidelines/dpwg");
          manager.transformDpwgGuidelines(downloadDir, dpwgGuidelinesDir);
          pgkbGuidelineCollection = new PgkbGuidelineCollection(dpwgGuidelinesDir);
        } else {
          // if we're skipping new drug data, then use the default data
          drugs = new DrugCollection();
          pgkbGuidelineCollection = new PgkbGuidelineCollection();
        }

        PhenotypeMap phenotypeMap;
        if (!skipPhenotypes) {
          if (!skipDownload) {
            // download phenotypes
            FileUtils.copyURLToFile(
                new URL("https://files.cpicpgx.org/data/report/current/gene_phenotypes.json"),
                downloadDir.resolve("cpic_phenotypes.json").toFile());
          }

          // transform phenotypes
          Path phenoDir = cliHelper.getValidDirectory("p", true);
          if (!phenoDir.getFileName().endsWith("phenotype")) {
            phenoDir = phenoDir.resolve("phenotype");
          }
          manager.transformPhenotypes(downloadDir, phenoDir);
          phenotypeMap = new PhenotypeMap(phenoDir);
        } else {
          // if we're skipping new phenotype data, then use the default data
          phenotypeMap = new PhenotypeMap();
        }
        validatePhenotypes(phenotypeMap);

        DefinitionReader definitionReader;
        if (!skipAlleles) {
          Path allelesDir = cliHelper.getValidDirectory("a", true);

          Path exemptionsTsv = downloadDir.resolve("exemptions.tsv");
          if (!skipDownload) {
            // download exemptions
            FileUtils.copyURLToFile(new URL(String.format(sf_googleDocUrlFmt, "1xHvvXQIMv3xbqNhuN7zG6WP4DB7lpQDmLvz18w-u_lk")),
                exemptionsTsv.toFile());
          }
          // transform exemptions
          Path exemptionsJson = allelesDir.resolve(EXEMPTIONS_JSON_FILE_NAME);
          Map<String, DefinitionExemption> exemptionsMap = manager.transformExemptions(exemptionsTsv, exemptionsJson);

          if (!skipDownload) {
            // download allele definitions
            // use S3 link to avoid caching problems
            FileUtils.copyURLToFile(
                new URL("http://files.cpicpgx.org.s3-us-west-2.amazonaws.com/data/report/current/allele_definitions.json"),
                downloadDir.resolve(CPIC_ALLELES_FILE_NAME).toFile());
          }
          // transform allele definitions
          definitionReader = manager.transformAlleleDefinitions(downloadDir, allelesDir, exemptionsMap);

        } else {
          // if we're skipping new gene data, then use the default data
          definitionReader = new DefinitionReader();
          definitionReader.read(DataManager.DEFAULT_DEFINITION_DIR);
        }

        List<String> genesUsedInDrugRecommendations = new ArrayList<>(drugs.list().stream()
            .flatMap(drug -> drug.getGenes().stream())
            .sorted().distinct().toList());
        genesUsedInDrugRecommendations.addAll(pgkbGuidelineCollection.getGenes());
        genesUsedInDrugRecommendations.removeAll(definitionReader.getGeneAlleleCount().keySet());
        genesUsedInDrugRecommendations.stream()
            .filter(g -> !g.startsWith("HLA"))
            .map(g -> "WARNING: Gene used in drug recommendation has no allele mapping: " + g)
            .forEach(System.out::println);


        if (cliHelper.hasOption("doc")) {
          Path docsDir = cliHelper.getValidDirectory("doc", true);
          new GeneDrugSummary(definitionReader, phenotypeMap, drugs, pgkbGuidelineCollection).write(docsDir);
        }

      } finally {
        if (downloadDir != null && !cliHelper.hasOption("dl")) {
          FileUtils.deleteQuietly(downloadDir.toFile());
        }
      }

    } catch (Exception ex) {
      ex.printStackTrace();
      System.exit(1);
    }
  }


  private void transformCpicGuidelines(Path cpicDlFile, Path guidelinesDir) throws IOException {
    if (!Files.exists(guidelinesDir)) {
      Files.createDirectories(guidelinesDir);
    }
    System.out.println("Saving CPIC guidelines to " + guidelinesDir);
    int count = 0;
    try (BufferedReader br = Files.newBufferedReader(cpicDlFile)) {
      List<Drug> drugs = DataSerializer.GSON.fromJson(br, DrugCollection.DRUG_LIST_TYPE);
      drugs.sort(Comparator.naturalOrder());

      for (Drug drug : drugs) {
        count += 1;
        drug.setSource(DataSource.CPIC);
        String filename = sanitizeFiename(drug.getDrugName()) + ".json";
        try (Writer writer = Files.newBufferedWriter(guidelinesDir.resolve(filename))) {
          DataSerializer.GSON.toJson(drug, writer);
        }
      }
    }
    System.out.println("Found " + count + " CPIC guidelines");
  }

  private void transformDpwgGuidelines(Path downloadDir, Path guidelinesDir) throws IOException {
    if (!Files.exists(guidelinesDir)) {
      Files.createDirectories(guidelinesDir);
    }
    System.out.println("Saving DPWG guidelines to " + guidelinesDir);
    AtomicInteger count = new AtomicInteger();
    try (Stream<Path> stream = Files.list(downloadDir.resolve("guidelines"))) {
      stream.forEach((file) -> {
        try {
          String filename = FilenameUtils.getName(file.toString());
          if (filename.startsWith("Annotation_of_DPWG_Guideline_for_")) {
            filename = filename.substring(33);
          }
          count.incrementAndGet();
          try (Reader reader = Files.newBufferedReader(file)) {
            GuidelinePackage guidelinePackage = DataSerializer.GSON.fromJson(reader, GuidelinePackage.class);
            guidelinePackage.getCitations().forEach(Publication::normalize);
            guidelinePackage.getGroups().stream()
                .map(Group::getActivityScore)
                .filter(Objects::nonNull)
                .forEach(Markdown::cleanupActivityScore);
            try (Writer writer = Files.newBufferedWriter(guidelinesDir.resolve(filename))) {
              DataSerializer.GSON.toJson(guidelinePackage, writer);
            }
          }
        } catch (IOException ex) {
          throw new RuntimeException("Error copying " + file.getFileName(), ex);
        }
      });
    }
    System.out.println("Found " + count.get() + " DPWG guidelines");
  }


  private DefinitionFile[] parseDefinitionFiles(Path downloadDir, String fileName) throws IOException {
    Path definitionsFile = downloadDir.resolve(fileName);
    if (!Files.exists(definitionsFile)) {
      throw new IOException("Cannot find alleles definitions (" + definitionsFile + ")");
    }
    String json = FileUtils.readFileToString(definitionsFile.toFile(), Charsets.UTF_8);
    return DataSerializer.GSON.fromJson(json, DefinitionFile[].class);
  }


  /**
   * Does the work for stepping through the files and applying the format.
   */
  private DefinitionReader transformAlleleDefinitions(Path downloadDir, Path definitionsDir,
      Map<String, DefinitionExemption> exemptionsMap) throws Exception {

    System.out.println("Generating allele definitions...");
    List<DefinitionFile> definitionFiles = new ArrayList<>();
    for (DefinitionFile df : parseDefinitionFiles(downloadDir, CPIC_ALLELES_FILE_NAME)) {
      df.setSource(DataSource.CPIC);
      definitionFiles.add(df);
    }
    for (DefinitionFile df : parseDefinitionFiles(downloadDir, DPWG_ALLELES_FILE_NAME)) {
      df.setSource(DataSource.PHARMGKB);
      definitionFiles.add(df);
    }

    SortedMap<String, DefinitionFile> definitionFileMap = new TreeMap<>();
    try (VcfHelper vcfHelper = new VcfHelper()) {
      for (DefinitionFile df : definitionFiles) {
        String gene = df.getGeneSymbol();
        if (gene.equals("MT-RNR1")) {
          continue;
        }
        DefinitionExemption exemption = exemptionsMap.get(gene);
        if (exemption != null) {
          if (exemption.getIgnoredAlleles().size() > 0) {
            System.out.println("Removing ignored named alleles in " + gene + "...");
            df.removeIgnoredNamedAlleles(exemption);
          }
          if (exemption.getIgnoredPositions().size() > 0) {
            System.out.println("Removing ignored positions in " + gene + "...");
            df.removeIgnoredPositions(exemption);
          }
        }

        df.doVcfTranslation(vcfHelper);
        definitionFileMap.put(gene, df);
      }
    }

    fixCyp2c19(definitionFileMap.get("CYP2C19"));

    System.out.println("Saving allele definitions in " + definitionsDir.toString());
    Set<String> currentFiles = new HashSet<>();
    try (Stream<Path> list = Files.list(definitionsDir)) {
      list.map(PathUtils::getFilename)
          .filter(f -> f.endsWith("_translation.json"))
          .forEachOrdered(currentFiles::add);
    }

    for (String gene : definitionFileMap.keySet()) {
      DefinitionFile definitionFile = definitionFileMap.get(gene);
      // output file
      Path jsonFile = definitionsDir.resolve(gene + "_translation.json");
      m_dataSerializer.serializeToJson(definitionFile, jsonFile);
      if (m_verbose) {
        System.out.println("Wrote " + jsonFile);
      }
      if (!currentFiles.remove(gene + "_translation.json")) {
        System.out.println("New gene: " + gene);
      }
    }

    exportVcfData(definitionsDir);

    deleteObsoleteFiles(definitionsDir, currentFiles);
    DefinitionReader definitionReader = new DefinitionReader();
    definitionReader.read(definitionsDir);
    return definitionReader;
  }



  /**
   * Copy any missing alleles from *1 from *38.
   */
  private void fixCyp2c19(DefinitionFile definitionFile) {
    Preconditions.checkNotNull(definitionFile);
    NamedAllele star1 = definitionFile.getNamedAlleles().stream()
        .filter(na -> na.getName().equals("*1"))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Cannot find CYP2C19*1"));
    NamedAllele star38 = definitionFile.getNamedAlleles().stream()
        .filter(na -> na.getName().equals("*38"))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Cannot find CYP2C19*38"));
    star1.initialize(definitionFile.getVariants());
    star38.initialize(definitionFile.getVariants());
    for (int x = 0; x < star38.getAlleles().length; x += 1) {
      if (star1.getAlleles()[x] == null) {
        star1.getAlleles()[x] = star38.getAlleles()[x];
      }
    }
  }


  private void exportVcfData(Path definitionsDir) throws IOException {

    DefinitionReader definitionReader = new DefinitionReader();
    definitionReader.read(definitionsDir);

    SortedSet<String> genes = new TreeSet<>(definitionReader.getGeneAlleleCount().keySet());

    Path positionsFile = definitionsDir.resolve(POSITIONS_VCF);
    System.out.println("Saving positions VCF to " + positionsFile);
    VcfHelper.extractPositions(genes, definitionReader, positionsFile);
    Path bgzFile = DockerRunner.bgzip(positionsFile);
    System.out.println("Saved bgzip'd positions VCF to " + bgzFile);
    DockerRunner.indexVcf(bgzFile);
  }


  private Map<String, DefinitionExemption> transformExemptions(Path tsvFile, Path jsonFile) throws IOException {
    System.out.println("Saving exemptions to " + jsonFile.toString());
    Set<DefinitionExemption> exemptions = m_dataSerializer.deserializeExemptionsFromTsv(tsvFile);
    m_dataSerializer.serializeToJson(exemptions, jsonFile);

    Map<String, DefinitionExemption> exemptionsMap = new HashMap<>();
    exemptions.forEach(exemption -> exemptionsMap.put(exemption.getGene(), exemption));
    return exemptionsMap;
  }

  private void transformMessages(Path tsvFile, Path jsonFile) throws IOException {
    System.out.println("Saving messages to " + jsonFile.toString());
    m_dataSerializer.serializeToJson(m_dataSerializer.deserializeMessagesFromTsv(tsvFile), jsonFile);
  }


  private void deleteObsoleteFiles(Path dir, Set<String> obsoleteFilenames) {

    for (String filename : obsoleteFilenames) {
      Path file = dir.resolve(filename);
      System.out.println("Deleting obsolete file: " + file);
      FileUtils.deleteQuietly(file.toFile());
    }
  }


  private void transformPhenotypes(Path downloadDir, Path phenoDir) throws IOException {
    Path cpicDir = phenoDir.resolve("cpic");
    System.out.println("Saving CPIC phenotypes to " + cpicDir);
    doTransformPhenotypes(downloadDir.resolve("cpic_phenotypes.json"), cpicDir, DataSource.CPIC);


    Path dpwgDir = phenoDir.resolve("dpwg");
    System.out.println("Saving DPWG phenotypes to " + dpwgDir);
    doTransformPhenotypes(downloadDir.resolve("dpwg_phenotypes.json"), dpwgDir, DataSource.DPWG);
  }

  private void doTransformPhenotypes(Path phenotypeFile, Path outputDir, DataSource source) throws IOException {
    if (!Files.exists(outputDir)) {
      Files.createDirectories(outputDir);
    }
    try (BufferedReader reader = Files.newBufferedReader(phenotypeFile)) {
      GenePhenotype[] rez = DataSerializer.GSON.fromJson(reader, GenePhenotype[].class);
      Set<String> genes = new HashSet<>();
      for (GenePhenotype gp : rez) {
        if (!genes.add(gp.getGene())) {
          throw new IllegalStateException("Multiple " + source + " GenePhenotypes for " + gp.getGene());
        }
        try (Writer writer = Files.newBufferedWriter(outputDir.resolve(sanitizeFiename(gp.getGene()) + ".json"))) {
          DataSerializer.GSON.toJson(gp, writer);
        }
      }
      System.out.println("Found " + rez.length + " " + source + " phenotypes");
    }
  }


  private static void validatePhenotypes(PhenotypeMap phenotypeMap) {
    // validate DPYD phenotypes (DpydCaller depends on this expectation)
    GenePhenotype dpwgGp = Objects.requireNonNull(phenotypeMap.getPhenotype("DPYD", DataSource.DPWG));
    GenePhenotype cpicGp = Objects.requireNonNull(phenotypeMap.getPhenotype("DPYD", DataSource.CPIC));
    for (String hap : dpwgGp.getHaplotypes().keySet()) {
      if (!cpicGp.getHaplotypes().containsKey(hap)) {
        throw new IllegalStateException("DPWG has DPYD " + hap + " but CPIC does not");
      }
    }
  }

  private static String sanitizeFiename(String basename) {
    return basename.replaceAll("\\p{Punct}", " ")
        .replaceAll("\\s+", "_");
  }
}
