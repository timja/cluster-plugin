package io.jenkins.plugins.cluster.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class XmlMergeTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String s(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    private static final String BASE = """
            <?xml version='1.1' encoding='UTF-8'?>
            <hudson>
              <systemMessage>hello</systemMessage>
              <quietPeriod>5</quietPeriod>
              <scmCheckoutRetryCount>0</scmCheckoutRetryCount>
            </hudson>""";

    @Test
    void differentSettingsBothSurvive() {
        String mine = BASE.replace("<quietPeriod>5<", "<quietPeriod>9<");
        String theirs = BASE.replace("<scmCheckoutRetryCount>0<", "<scmCheckoutRetryCount>4<");
        XmlMerge.Result r = XmlMerge.merge(b(BASE), b(mine), b(theirs));
        assertNotNull(r);
        assertEquals(0, r.conflicts());
        assertEquals("""
                <?xml version='1.1' encoding='UTF-8'?>
                <hudson>
                  <systemMessage>hello</systemMessage>
                  <quietPeriod>9</quietPeriod>
                  <scmCheckoutRetryCount>4</scmCheckoutRetryCount>
                </hudson>""", s(r.merged()));
    }

    @Test
    void controlCharactersInTextMerge() {
        String base = BASE.replace(">hello<", ">bell &#x1; escape &#x1b;<");
        String mine = base.replace("<quietPeriod>5<", "<quietPeriod>9<");
        String theirs = base.replace("<scmCheckoutRetryCount>0<", "<scmCheckoutRetryCount>4<");
        XmlMerge.Result r = XmlMerge.merge(b(base), b(mine), b(theirs));
        assertNotNull(r, "an XML 1.1 file with control characters merges");
        assertEquals(0, r.conflicts());
        XmlMerge.Result again = XmlMerge.merge(r.merged(), r.merged(), r.merged());
        assertNotNull(again, "and reads back: " + s(r.merged()));
        assertTrue(
                s(r.merged()).contains("<quietPeriod>9<") && s(r.merged()).contains("<scmCheckoutRetryCount>4<"),
                s(r.merged()));
        assertTrue(s(r.merged()).matches("(?s).*bell &#(x)?0*1; escape &#(x1b|27);.*"), s(r.merged()));
    }

    @Test
    void theMergeSaysTheEncodingItIsWrittenIn() {
        String base = BASE.replace("encoding='UTF-8'", "encoding='ISO-8859-1'");
        String mine = base.replace("<quietPeriod>5<", "<quietPeriod>9<");
        String theirs = base.replace(">hello<", ">héllo wörld<");
        XmlMerge.Result r = XmlMerge.merge(
                base.getBytes(StandardCharsets.ISO_8859_1),
                mine.getBytes(StandardCharsets.ISO_8859_1),
                theirs.getBytes(StandardCharsets.ISO_8859_1));
        assertNotNull(r);
        assertTrue(s(r.merged()).startsWith("<?xml version='1.1' encoding='UTF-8'?>"), s(r.merged()));
        assertTrue(s(r.merged()).contains(">héllo wörld<"), s(r.merged()));
    }

    @Test
    void sameSettingChangedOnBothSidesKeepsMineAndCounts() {
        XmlMerge.Result r =
                XmlMerge.merge(b(BASE), b(BASE.replace(">hello<", ">mine<")), b(BASE.replace(">hello<", ">theirs<")));
        assertNotNull(r);
        assertEquals(1, r.conflicts());
        assertEquals(BASE.replace(">hello<", ">mine<"), s(r.merged()));
    }

    @Test
    void elementsAddedAndRemoved() {
        String mine = BASE.replace("  <quietPeriod>5</quietPeriod>\n", ""); // reset to the default: element gone
        String theirs = BASE.replace("</hudson>", "  <label>linux</label>\n</hudson>");
        XmlMerge.Result r = XmlMerge.merge(b(BASE), b(mine), b(theirs));
        assertNotNull(r);
        assertEquals(0, r.conflicts());
        assertEquals("""
                <?xml version='1.1' encoding='UTF-8'?>
                <hudson>
                  <systemMessage>hello</systemMessage>
                  <scmCheckoutRetryCount>0</scmCheckoutRetryCount>
                  <label>linux</label>
                </hudson>""", s(r.merged()));
    }

    @Test
    void multiLineTextIsKeptVerbatim() {
        String base =
                "<?xml version='1.1' encoding='UTF-8'?>\n<project>\n  <description>a\n  b\n    c</description>\n  <disabled>false</disabled>\n</project>";
        String theirs = base.replace("<disabled>false<", "<disabled>true<");
        XmlMerge.Result r = XmlMerge.merge(b(base), b(base.replace("    c<", "    d<")), b(theirs));
        assertNotNull(r);
        assertEquals(
                "<?xml version='1.1' encoding='UTF-8'?>\n<project>\n  <description>a\n  b\n    d</description>\n  <disabled>true</disabled>\n</project>",
                s(r.merged()));
    }

    @Test
    void listsMergeAsWholes() {
        String base = "<x>\n  <item>1</item>\n  <item>2</item>\n</x>";
        XmlMerge.Result r = XmlMerge.merge(b(base), b(base.replace(">1<", ">one<")), b(base.replace(">2<", ">two<")));
        assertNotNull(r);
        assertEquals(1, r.conflicts(), "both changed the list of items");
    }

    @Test
    void differentPartsOfOneNestedElementBothSurvive() {
        // a user record: one controller changes the password, another adds an API token
        String base = """
                <?xml version='1.1' encoding='UTF-8'?>
                <user>
                  <id>admin</id>
                  <properties>
                    <hudson.security.HudsonPrivateSecurityRealm_-Details><passwordHash>old</passwordHash></hudson.security.HudsonPrivateSecurityRealm_-Details>
                    <jenkins.security.ApiTokenProperty><tokenStore/></jenkins.security.ApiTokenProperty>
                  </properties>
                </user>""";
        String mine = base.replace("<passwordHash>old<", "<passwordHash>new<");
        String theirs = base.replace("<tokenStore/>", "<tokenStore><name>harness</name></tokenStore>");
        XmlMerge.Result r = XmlMerge.merge(b(base), b(mine), b(theirs));
        assertNotNull(r);
        assertEquals(0, r.conflicts());
        String merged = s(r.merged());
        assertTrue(merged.contains("<passwordHash>new</passwordHash>"), merged);
        assertTrue(merged.contains("<tokenStore><name>harness</name></tokenStore>"), merged);
        assertTrue(merged.contains("<id>admin</id>"), merged);
    }

    @Test
    void sameNestedPartChangedOnBothSidesKeepsMine() {
        String base = "<user>\n  <properties>\n    <a>1</a>\n    <b>1</b>\n  </properties>\n</user>";
        XmlMerge.Result r =
                XmlMerge.merge(b(base), b(base.replace("<a>1<", "<a>mine<")), b(base.replace("<a>1<", "<a>theirs<")));
        assertNotNull(r);
        assertEquals(1, r.conflicts());
        assertTrue(s(r.merged()).contains("<a>mine</a>"));
    }

    private static final String BUILDERS = """
            <project>
              <builders>
                <hudson.tasks.Maven><targets>install</targets></hudson.tasks.Maven>
              </builders>
            </project>""";

    private static String builders(String merged) {
        return merged.substring(merged.indexOf("<builders>"), merged.indexOf("</builders>"))
                .replaceAll("\\s+", "");
    }

    @Test
    void buildStepsAddedElsewhereKeepTheirPlace() {
        String mine = BUILDERS.replace(">install<", ">deploy<");
        String theirs = BUILDERS.replace(
                "<hudson.tasks.Maven>",
                "<hudson.tasks.Shell><command>make</command></hudson.tasks.Shell>\n    <hudson.tasks.Maven>");
        XmlMerge.Result r = XmlMerge.merge(b(BUILDERS), b(mine), b(theirs));
        assertNotNull(r);
        assertEquals(0, r.conflicts());
        assertEquals(
                "<builders><hudson.tasks.Shell><command>make</command></hudson.tasks.Shell>"
                        + "<hudson.tasks.Maven><targets>deploy</targets></hudson.tasks.Maven>",
                builders(s(r.merged())),
                "the shell step runs first");

        String appended = BUILDERS.replace(
                "</hudson.tasks.Maven>",
                "</hudson.tasks.Maven>\n    <hudson.tasks.Ant><targets>dist</targets></hudson.tasks.Ant>");
        r = XmlMerge.merge(b(BUILDERS), b(appended), b(theirs));
        assertNotNull(r);
        assertEquals(0, r.conflicts());
        assertEquals(
                "<builders><hudson.tasks.Shell><command>make</command></hudson.tasks.Shell>"
                        + "<hudson.tasks.Maven><targets>install</targets></hudson.tasks.Maven><hudson.tasks.Ant><targets>dist</targets></hudson.tasks.Ant>",
                builders(s(r.merged())));
    }

    @Test
    void buildStepsOrderedDifferentlyOnTheTwoSidesKeepMineAndCount() {
        String base = BUILDERS.replace(
                "</hudson.tasks.Maven>",
                "</hudson.tasks.Maven>\n    <hudson.tasks.Shell><command>make</command></hudson.tasks.Shell>");
        String mine = BUILDERS.replace(
                "<hudson.tasks.Maven>",
                "<hudson.tasks.Shell><command>make</command></hudson.tasks.Shell>\n    <hudson.tasks.Maven>");
        String theirs = base.replace(">install<", ">deploy<");
        XmlMerge.Result r = XmlMerge.merge(b(base), b(mine), b(theirs));
        assertNotNull(r);
        assertEquals(1, r.conflicts(), "reordered here, changed there");
        assertEquals(builders(mine), builders(s(r.merged())));

        // both added a step at the same place: which runs first is not known
        String mine2 = BUILDERS.replace("</hudson.tasks.Maven>", "</hudson.tasks.Maven>\n    <hudson.tasks.Ant/>");
        String theirs2 = BUILDERS.replace("</hudson.tasks.Maven>", "</hudson.tasks.Maven>\n    <hudson.tasks.Shell/>");
        r = XmlMerge.merge(b(BUILDERS), b(mine2), b(theirs2));
        assertNotNull(r);
        assertEquals(1, r.conflicts());
        assertEquals(builders(mine2), builders(s(r.merged())));
    }

    @Test
    void attributeValuesReadBackUnchanged() throws Exception {
        String base = """
                <?xml version='1.1' encoding='UTF-8'?>
                <project note="a&#10;b&#9;c&#x1;&gt;">
                  <properties tag="d&#13;e&#x85;">
                    <one>1</one>
                    <two>2</two>
                  </properties>
                  <disabled>false</disabled>
                </project>""";
        String mine = base.replace("<one>1<", "<one>uno<");
        String theirs = base.replace("<two>2<", "<two>dos<").replace("<disabled>false<", "<disabled>true<");
        XmlMerge.Result r = XmlMerge.merge(b(base), b(mine), b(theirs));
        assertNotNull(r);
        assertEquals(0, r.conflicts());
        org.w3c.dom.Element root = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(r.merged()))
                .getDocumentElement();
        assertEquals("a\nb\tc\u0001>", root.getAttribute("note"), s(r.merged()));
        org.w3c.dom.Element properties =
                (org.w3c.dom.Element) root.getElementsByTagName("properties").item(0);
        assertEquals("d\re\u0085", properties.getAttribute("tag"), s(r.merged()));
        assertTrue(s(r.merged()).contains("<one>uno</one>") && s(r.merged()).contains("<two>dos</two>"), s(r.merged()));
    }

    @Test
    void notMergeable() {
        assertNull(XmlMerge.merge(b(BASE), b("not xml"), b(BASE)));
        assertNull(XmlMerge.merge(b(BASE), b(BASE), b("<other/>")), "different root");
        assertNull(XmlMerge.merge(b("<x>text<y/></x>"), b("<x>text<y/></x>"), b("<x>text<y/></x>")), "mixed content");
    }

    @Test
    void doctypeRefused() {
        assertNull(XmlMerge.merge(
                b("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><x>&e;</x>"), b("<x/>"), b("<x/>")));
    }
}
