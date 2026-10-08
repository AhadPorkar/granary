package dev.granary.format.nuget;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Reads the {@code .nuspec} manifest out of a {@code .nupkg} (a zip with the manifest at its root) and
 * parses it with the JDK DOM parser.
 *
 * <p>The parser is locked down (no DOCTYPE, no external entities, no XInclude) because the manifest comes
 * straight from whoever holds a write token; a package upload must never be able to read server files or
 * stall the parser through entity expansion. Element names are matched by local name so the several
 * nuspec XML namespaces Microsoft has published over the years all work.
 */
public final class NuspecReader {

    /** Manifests are small; a nuspec bigger than this is not a manifest. */
    private static final long MAX_NUSPEC_BYTES = 16L * 1024 * 1024;

    private NuspecReader() {
    }

    /** Thrown when an upload is not a package the server can accept. Surfaces as HTTP 400. */
    public static final class InvalidPackageException extends RuntimeException {
        public InvalidPackageException(String message) {
            super(message);
        }

        public InvalidPackageException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * What {@link #readNupkg} found: the parsed manifest and its raw bytes, which are stored next to the
     * package so later requests never have to open the zip again.
     */
    public record NupkgContents(Nuspec nuspec, byte[] nuspecXml, String nuspecFileName) {
    }

    /** Scans a nupkg for its root-level {@code *.nuspec}. The caller closes {@code in}. */
    public static NupkgContents readNupkg(InputStream in) throws IOException {
        ZipInputStream zip = new ZipInputStream(in);
        ZipEntry entry;
        try {
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || name.contains("/") || name.contains("\\")
                        || !name.toLowerCase(Locale.ROOT).endsWith(".nuspec")) {
                    continue;
                }
                byte[] xml = readBounded(zip);
                return new NupkgContents(parse(xml), xml, name);
            }
        } catch (java.util.zip.ZipException e) {
            throw new InvalidPackageException("The upload is not a valid zip archive: " + e.getMessage(), e);
        }
        throw new InvalidPackageException("The package contains no .nuspec manifest at its root");
    }

    private static byte[] readBounded(InputStream in) throws IOException {
        var out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) >= 0) {
            total += read;
            if (total > MAX_NUSPEC_BYTES) {
                throw new InvalidPackageException("The .nuspec manifest is larger than " + MAX_NUSPEC_BYTES + " bytes");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    public static Nuspec parse(byte[] xml) {
        return parse(new ByteArrayInputStream(xml));
    }

    public static Nuspec parse(InputStream xml) {
        Document document;
        try {
            document = secureBuilder().parse(xml);
        } catch (SAXException | IOException e) {
            throw new InvalidPackageException("The .nuspec manifest is not well-formed XML: " + e.getMessage(), e);
        }
        Element root = document.getDocumentElement();
        if (root == null || !"package".equals(root.getLocalName() == null ? root.getTagName() : root.getLocalName())) {
            throw new InvalidPackageException("The .nuspec manifest has no <package> root element");
        }
        Element metadata = child(root, "metadata");
        if (metadata == null) {
            throw new InvalidPackageException("The .nuspec manifest has no <metadata> element");
        }
        String id = text(metadata, "id");
        String version = text(metadata, "version");
        if (id == null || id.isBlank()) {
            throw new InvalidPackageException("The .nuspec manifest has no <id>");
        }
        if (version == null || version.isBlank()) {
            throw new InvalidPackageException("The .nuspec manifest has no <version>");
        }
        String licenseExpression = null;
        Element licenseElement = child(metadata, "license");
        if (licenseElement != null && "expression".equalsIgnoreCase(licenseElement.getAttribute("type"))) {
            licenseExpression = licenseElement.getTextContent().trim();
        }
        return new Nuspec(id.trim(), version.trim(), text(metadata, "title"), text(metadata, "description"),
                text(metadata, "summary"), text(metadata, "authors"), text(metadata, "owners"), tags(text(metadata, "tags")),
                text(metadata, "licenseUrl"), licenseExpression, text(metadata, "projectUrl"), text(metadata, "iconUrl"),
                Boolean.parseBoolean(text(metadata, "requireLicenseAcceptance")), text(metadata, "copyright"),
                text(metadata, "releaseNotes"), dependencyGroups(child(metadata, "dependencies")),
                packageTypes(child(metadata, "packageTypes")));
    }

    private static List<Nuspec.DependencyGroup> dependencyGroups(Element dependencies) {
        if (dependencies == null) {
            return List.of();
        }
        List<Nuspec.DependencyGroup> groups = new ArrayList<>();
        List<Nuspec.Dependency> ungrouped = new ArrayList<>();
        for (Element e : children(dependencies)) {
            switch (localName(e)) {
                case "group" -> {
                    String tf = e.getAttribute("targetFramework");
                    groups.add(new Nuspec.DependencyGroup(tf == null || tf.isBlank() ? null : tf.trim(), dependenciesOf(e)));
                }
                case "dependency" -> dependency(e).ifPresent(ungrouped::add);
                default -> {
                    // ignore unknown children
                }
            }
        }
        if (!ungrouped.isEmpty()) {
            groups.add(0, new Nuspec.DependencyGroup(null, ungrouped));
        }
        return groups;
    }

    private static List<Nuspec.Dependency> dependenciesOf(Element group) {
        List<Nuspec.Dependency> list = new ArrayList<>();
        for (Element e : children(group)) {
            if ("dependency".equals(localName(e))) {
                dependency(e).ifPresent(list::add);
            }
        }
        return list;
    }

    private static java.util.Optional<Nuspec.Dependency> dependency(Element e) {
        String id = e.getAttribute("id");
        if (id == null || id.isBlank()) {
            return java.util.Optional.empty();
        }
        String version = e.getAttribute("version");
        return java.util.Optional.of(new Nuspec.Dependency(id.trim(), version == null || version.isBlank() ? null : version.trim()));
    }

    private static List<String> packageTypes(Element packageTypes) {
        if (packageTypes == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Element e : children(packageTypes)) {
            if ("packageType".equals(localName(e)) && !e.getAttribute("name").isBlank()) {
                names.add(e.getAttribute("name").trim());
            }
        }
        return names;
    }

    private static List<String> tags(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(text.trim().split("[\\s,]+")).filter(s -> !s.isEmpty()).toList();
    }

    private static String text(Element parent, String name) {
        Element child = child(parent, name);
        if (child == null) {
            return null;
        }
        String value = child.getTextContent();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Element child(Element parent, String name) {
        for (Element e : children(parent)) {
            if (name.equals(localName(e))) {
                return e;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent) {
        NodeList nodes = parent.getChildNodes();
        List<Element> elements = new ArrayList<>(nodes.getLength());
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                elements.add((Element) node);
            }
        }
        return elements;
    }

    private static String localName(Element e) {
        return e.getLocalName() == null ? e.getTagName() : e.getLocalName();
    }

    private static DocumentBuilder secureBuilder() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("JDK XML parser does not support secure processing", e);
        }
    }
}
