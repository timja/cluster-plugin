package io.jenkins.plugins.cluster.config;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/**
 * Three-way merge of XML documents saved concurrently by two controllers, such as {@code config.xml} with one controller
 * changing the quiet period and another the system message.
 *
 * <p>The unit of merging is a direct child of the root element, by name (all children of one name together, so lists
 * merge as wholes): a unit only one side changed takes that side's version; a unit both changed takes mine and counts as a
 * conflict, unless it is a single element with the same attributes on every side whose children are all distinctly
 * named, and the sides agree on their order (neither reordered the children both have, nor added any where the other
 * did): those children are then merged the same way, recursively, in that order. The root's attributes are one more unit.
 */
@Restricted(NoExternalUse.class)
public final class XmlMerge {

    private XmlMerge() {}

    /** The outcome of a merge. */
    public record Result(@NonNull byte[] merged, int conflicts) {}

    /**
     * @return the merged document, or null if the documents cannot be merged this way (not XML, different roots, text
     *     directly in the root)
     */
    @CheckForNull
    public static Result merge(@NonNull byte[] base, @NonNull byte[] mine, @NonNull byte[] theirs) {
        try {
            Element b = parse(base);
            Element m = parse(mine);
            Element t = parse(theirs);
            if (b == null
                    || m == null
                    || t == null
                    || !b.getTagName().equals(m.getTagName())
                    || !m.getTagName().equals(t.getTagName())) {
                return null;
            }
            int[] conflicts = {0};
            String inner = mergeChildren(b, m, t, "  ", conflicts);
            if (inner == null) {
                return null;
            }
            String root = m.getTagName();
            String attrs = mergeAttributes(b, m, t, conflicts);
            String doc = declaration(mine) + "\n<" + root + attrs + ">\n" + inner + "</" + root + ">";
            return new Result(doc.getBytes(StandardCharsets.UTF_8), conflicts[0]);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Merges the children of three versions of an element, by name; one line per child at {@code indent}.
     *
     * @return null if an element has text of its own among its children (mixed content)
     */
    @CheckForNull
    private static String mergeChildren(Element b, Element m, Element t, String indent, int[] conflicts)
            throws Exception {
        Map<String, List<Element>> bu = children(b);
        Map<String, List<Element>> mu = children(m);
        Map<String, List<Element>> tu = children(t);
        if (bu == null || mu == null || tu == null) {
            return null;
        }
        List<String> names = order(List.copyOf(bu.keySet()), List.copyOf(mu.keySet()), List.copyOf(tu.keySet()));
        if (names == null) {
            // the sides ordered the children differently; only reached for the root, whose children are fields (any
            // order)
            names = new ArrayList<>(mu.keySet());
            for (String name : tu.keySet()) {
                if (!names.contains(name)) {
                    names.add(name);
                }
            }
        }
        for (String name : bu.keySet()) {
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        StringBuilder out = new StringBuilder();
        for (String name : names) {
            List<Element> be = bu.get(name);
            List<Element> me = mu.get(name);
            List<Element> te = tu.get(name);
            String bv = serialise(be);
            String mv = serialise(me);
            String tv = serialise(te);
            List<String> chosen;
            if (Objects.equals(mv, bv)) {
                chosen = verbatim(te);
            } else if (Objects.equals(tv, bv) || Objects.equals(mv, tv)) {
                chosen = verbatim(me);
            } else if (single(be)
                    && single(me)
                    && single(te)
                    && sameAttributes(be.get(0), me.get(0), te.get(0))
                    && structured(be.get(0))
                    && structured(me.get(0))
                    && structured(te.get(0))
                    && order(names(be.get(0)), names(me.get(0)), names(te.get(0))) != null) {
                // changed on both sides, but made of distinctly named parts (e.g. <properties>, or <builders> of
                // distinct
                // kinds, whose order matters): merge those, keeping each side's order
                Element me0 = me.get(0);
                String inner = mergeChildren(be.get(0), me0, te.get(0), indent + "  ", conflicts);
                String open = "<" + me0.getTagName() + attributes(me0);
                chosen = List.of(
                        inner == null || inner.isEmpty()
                                ? open + "/>"
                                : open + ">\n" + inner + indent + "</" + me0.getTagName() + ">");
            } else {
                chosen = verbatim(me);
                conflicts[0]++;
            }
            for (String element : chosen) {
                out.append(indent).append(element).append('\n'); // verbatim: text content keeps its whitespace
            }
        }
        return out.toString();
    }

    /**
     * The top-level parts of an XML document: each child element name of the root, with the serialised elements of that
     * name; null if it cannot be split so (unparseable, or text among the children). Used to reload only what changed
     * field by field, as an item changed on another controller is.
     */
    @CheckForNull
    static Map<String, String> parts(@NonNull byte[] xml) {
        try {
            Element root = parse(xml);
            Map<String, List<Element>> children = root == null ? null : children(root);
            if (children == null) {
                return null;
            }
            Map<String, String> result = new LinkedHashMap<>();
            for (Map.Entry<String, List<Element>> e : children.entrySet()) {
                result.put(e.getKey(), serialise(e.getValue()));
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    /** Child elements by name, in order; null if there is non-blank text among them. */
    @CheckForNull
    private static Map<String, List<Element>> children(Element e) {
        Map<String, List<Element>> result = new LinkedHashMap<>();
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            switch (n.getNodeType()) {
                case Node.ELEMENT_NODE ->
                    result.computeIfAbsent(n.getNodeName(), k -> new ArrayList<>())
                            .add((Element) n);
                case Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> {
                    if (!n.getNodeValue().isBlank()) {
                        return null;
                    }
                }
                case Node.COMMENT_NODE -> {}
                default -> {
                    return null;
                }
            }
        }
        return result;
    }

    private static List<String> names(Element e) {
        Map<String, List<Element>> children = children(e);
        return children == null ? List.of() : List.copyOf(children.keySet()); // only asked of structured elements
    }

    /**
     * The order of the merged children, by name, keeping each side's: mine's, with the names only theirs added inserted
     * after the name they follow there.
     *
     * @return null if the two sides do not agree on it: they ordered the names both have differently, or both added
     *     names at the same place
     */
    @CheckForNull
    static List<String> order(List<String> base, List<String> mine, List<String> theirs) {
        if (!mine.stream()
                .filter(theirs::contains)
                .toList()
                .equals(theirs.stream().filter(mine::contains).toList())) {
            return null;
        }
        List<String> merged = new ArrayList<>(mine);
        String previous = null; // the last of theirs placed so far
        for (int i = 0; i < theirs.size(); i++) {
            String name = theirs.get(i);
            if (merged.contains(name)) {
                previous = name;
                continue;
            }
            if (base.contains(name)) {
                continue; // mine removed it: nothing of it is written
            }
            int from = previous == null ? 0 : merged.indexOf(previous) + 1;
            int to = merged.size();
            for (int j = i + 1; j < theirs.size(); j++) {
                if (mine.contains(theirs.get(j))) {
                    to = merged.indexOf(theirs.get(j));
                    break;
                }
            }
            for (String between : merged.subList(from, to)) {
                if (!base.contains(between) && !theirs.contains(between)) {
                    return null; // mine added something there too: which comes first is not known
                }
            }
            merged.add(from, name);
            previous = name;
        }
        return merged;
    }

    private static boolean single(@CheckForNull List<Element> elements) {
        return elements != null && elements.size() == 1;
    }

    /** Whether an element is made only of child elements with distinct names (so they can be merged one by one). */
    private static boolean structured(Element e) {
        Map<String, List<Element>> c = children(e);
        return c != null && !c.isEmpty() && c.values().stream().allMatch(l -> l.size() == 1);
    }

    private static boolean sameAttributes(Element b, Element m, Element t) {
        return attributes(b).equals(attributes(m)) && attributes(m).equals(attributes(t));
    }

    private static String mergeAttributes(Element b, Element m, Element t, int[] conflicts) {
        String ba = attributes(b);
        String ma = attributes(m);
        String ta = attributes(t);
        if (ma.equals(ba)) {
            return ta;
        }
        if (!ta.equals(ba) && !ta.equals(ma)) {
            conflicts[0]++;
        }
        return ma;
    }

    private static String attributes(Element e) {
        StringBuilder attrs = new StringBuilder();
        NamedNodeMap a = e.getAttributes();
        for (int i = 0; i < a.getLength(); i++) {
            Node n = a.item(i);
            attrs.append(' ')
                    .append(n.getNodeName())
                    .append("=\"")
                    .append(escapeAttribute(n.getNodeValue()))
                    .append('"');
        }
        return attrs.toString();
    }

    @CheckForNull
    private static String serialise(@CheckForNull List<Element> elements) throws Exception {
        if (elements == null) {
            return null;
        }
        return String.join(SEPARATOR, verbatim(elements));
    }

    private static List<String> verbatim(@CheckForNull List<Element> elements) throws Exception {
        List<String> result = new ArrayList<>();
        if (elements != null) {
            for (Element e : elements) {
                result.add(serialise(e));
            }
        }
        return result;
    }

    /** Between the serialised elements of one unit (cannot occur in XML). */
    private static final String SEPARATOR = "\u0000";

    @CheckForNull
    private static Element parse(byte[] xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setExpandEntityReferences(false);
        DocumentBuilder db = f.newDocumentBuilder();
        db.setErrorHandler(null);
        Document d = db.parse(new ByteArrayInputStream(xml));
        return d.getDocumentElement();
    }

    /** Made once: looking the implementation up costs more than a merge. */
    private static final TransformerFactory TRANSFORMERS = TransformerFactory.newInstance();

    private static String serialise(Node n) throws Exception {
        Transformer tr;
        synchronized (TRANSFORMERS) { // a factory is not specified to be thread-safe; its transformers are used by one
            tr = TRANSFORMERS.newTransformer();
        }
        tr.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        // as Jenkins writes its files: control characters (in a description, say) are then character references, which
        // XML 1.0 output refuses (and the merge with it)
        tr.setOutputProperty(OutputKeys.VERSION, "1.1");
        StringWriter w = new StringWriter();
        tr.transform(new DOMSource(n), new StreamResult(w));
        return w.toString();
    }

    /**
     * Escapes an attribute's value, as read (decoded), so that it reads back the same: tabs and line breaks too, which
     * reading would turn into spaces, and control characters, which XML 1.1 only takes as character references.
     */
    private static String escapeAttribute(String v) {
        StringBuilder b = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                default -> {
                    if (c < 0x20 || c >= 0x7F && c <= 0x9F) {
                        b.append("&#x").append(Integer.toHexString(c)).append(';');
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.toString();
    }

    private static String declaration(byte[] xml) {
        String s = new String(xml, 0, Math.min(xml.length, 200), StandardCharsets.UTF_8);
        if (s.startsWith("<?xml")) {
            int end = s.indexOf("?>");
            if (end > 0) {
                // the merge is written in UTF-8, whatever mine said
                return s.substring(0, end + 2).replaceFirst("encoding\\s*=\\s*(['\"])[^'\"]*\\1", "encoding='UTF-8'");
            }
        }
        return "<?xml version='1.1' encoding='UTF-8'?>";
    }
}
