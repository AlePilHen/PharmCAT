package org.pharmgkb.pharmcat.definition;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.Test;
import org.pharmgkb.common.util.PathUtils;
import org.pharmgkb.pharmcat.TestUtil;
import org.pharmgkb.pharmcat.definition.model.DefinitionFile;

import static org.junit.Assert.*;


/**
 * JUnit test for {@link GeneratedDefinitionSerializer}.
 *
 * @author Mark Woon
 */
public class GeneratedDefinitionSerializerTest {

  @Test
  public void testJson1() throws Exception {

    // has indels
    Path inFile = TestUtil.getFile("org/pharmgkb/pharmcat/definition/CYP3A5.good.tsv");
    DefinitionFile[] definitionFiles = testJson(inFile);
    assertEquals(8, definitionFiles[1].getVariants().length);
    assertEquals("g.99652770_99652771insA", definitionFiles[1].getVariants()[6].getChrPosition());
    assertTrue(definitionFiles[1].getVariants()[6].isInDel());
  }

  @Test
  public void testJson2() throws Exception {

    // contains population frequencies
    Path inFile = TestUtil.getFile("org/pharmgkb/pharmcat/definition/CYP2C19.tsv");
    DefinitionFile[] definitionFiles = testJson(inFile);
    assertEquals(8, definitionFiles[1].getPopulations().size());
    assertNotNull(definitionFiles[1].getNamedAlleles().get(0).getPopFreqMap());
    assertFalse(definitionFiles[1].getNamedAlleles().get(0).getPopFreqMap().isEmpty());
    assertEquals("0.34", definitionFiles[1].getNamedAlleles().get(0).getPopFreqMap().get("African Allele Frequency"));
  }

  private DefinitionFile[] testJson(Path inFile) throws IOException {
    DefinitionFile definitionFile = new CuratedDefinitionParser(inFile).parse();

    Path jsonFile = inFile.getParent().resolve(PathUtils.getBaseFilename(inFile) + ".json");
    GeneratedDefinitionSerializer cdSerializer = new GeneratedDefinitionSerializer();
    // write it out
    cdSerializer.serializeToJson(definitionFile, jsonFile);
    // read it back in
    DefinitionFile jsonDefinitionFile = cdSerializer.deserializeFromJson(jsonFile);

    assertEquals(definitionFile, jsonDefinitionFile);
    return new DefinitionFile[] { definitionFile, jsonDefinitionFile };
  }


  @Test
  public void testTsv() throws Exception {

    Path inFile = TestUtil.getFile("org/pharmgkb/pharmcat/definition/CYP3A5.good.tsv");
    DefinitionFile definitionFile = new CuratedDefinitionParser(inFile).parse();

    Path tsvFile = inFile.getParent().resolve(PathUtils.getBaseFilename(inFile) + "-generated.tsv");
    GeneratedDefinitionSerializer cdSerializer = new GeneratedDefinitionSerializer();
    // write it out
    cdSerializer.serializeToTsv(definitionFile, tsvFile);

    // TODO(markwoon): check that file is correct!
    System.out.println(tsvFile);
  }
}
