package dev.granary.format.maven;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * A mutable in-memory {@code maven-metadata.xml}, covering the artifact level (versions), the snapshot
 * version level (snapshot build + snapshotVersions) and the group level (plugins). Parsed and written with
 * the JDK's XML APIs only.
 */
public final class MavenMetadata {

    public static final DateTimeFormatter LAST_UPDATED = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    public record SnapshotVersion(String classifier, String extension, String value, String updated) {
        public String key() {
            return (classifier == null ? "" : classifier) + ":" + extension;
        }
    }

    public record Plugin(String name, String prefix, String artifactId) {
    }

    private String groupId;
    private String artifactId;
    private String version;
    private String latest;
    private String release;
    private final List<String> versions = new ArrayList<>();
    private String lastUpdated;
    private String snapshotTimestamp;
    private String snapshotBuildNumber;
    private final List<SnapshotVersion> snapshotVersions = new ArrayList<>();
    private final List<Plugin> plugins = new ArrayList<>();

    public static MavenMetadata parse(byte[] xml) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xml));
            Element root = doc.getDocumentElement();
            MavenMetadata md = new MavenMetadata();
            md.groupId = text(root, "groupId");
            md.artifactId = text(root, "artifactId");
            md.version = text(root, "version");
            Element versioning = child(root, "versioning");
            if (versioning != null) {
                md.latest = text(versioning, "latest");
                md.release = text(versioning, "release");
                md.lastUpdated = text(versioning, "lastUpdated");
                Element versions = child(versioning, "versions");
                if (versions != null) {
                    for (Element v : children(versions, "version")) {
                        md.versions.add(v.getTextContent().trim());
                    }
                }
                Element snapshot = child(versioning, "snapshot");
                if (snapshot != null) {
                    md.snapshotTimestamp = text(snapshot, "timestamp");
                    md.snapshotBuildNumber = text(snapshot, "buildNumber");
                }
                Element snapshotVersions = child(versioning, "snapshotVersions");
                if (snapshotVersions != null) {
                    for (Element sv : children(snapshotVersions, "snapshotVersion")) {
                        md.snapshotVersions.add(new SnapshotVersion(text(sv, "classifier"), text(sv, "extension"),
                                text(sv, "value"), text(sv, "updated")));
                    }
                }
            }
            Element plugins = child(root, "plugins");
            if (plugins != null) {
                for (Element p : children(plugins, "plugin")) {
                    md.plugins.add(new Plugin(text(p, "name"), text(p, "prefix"), text(p, "artifactId")));
                }
            }
            return md;
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("invalid maven-metadata.xml: " + e.getMessage(), e);
        }
    }

    public String toXml() {
        try {
            StringWriter out = new StringWriter();
            XMLStreamWriter w = XMLOutputFactory.newInstance().createXMLStreamWriter(out);
            w.writeStartDocument("UTF-8", "1.0");
            w.writeCharacters("\n");
            w.writeStartElement("metadata");
            if (version != null) {
                w.writeAttribute("modelVersion", "1.1.0");
            }
            element(w, "groupId", groupId);
            element(w, "artifactId", artifactId);
            element(w, "version", version);
            if (latest != null || release != null || !versions.isEmpty() || lastUpdated != null
                    || snapshotTimestamp != null || !snapshotVersions.isEmpty()) {
                w.writeStartElement("versioning");
                element(w, "latest", latest);
                element(w, "release", release);
                if (snapshotTimestamp != null || snapshotBuildNumber != null) {
                    w.writeStartElement("snapshot");
                    element(w, "timestamp", snapshotTimestamp);
                    element(w, "buildNumber", snapshotBuildNumber);
                    w.writeEndElement();
                }
                if (!versions.isEmpty()) {
                    w.writeStartElement("versions");
                    for (String v : versions) {
                        element(w, "version", v);
                    }
                    w.writeEndElement();
                }
                element(w, "lastUpdated", lastUpdated);
                if (!snapshotVersions.isEmpty()) {
                    w.writeStartElement("snapshotVersions");
                    for (SnapshotVersion sv : snapshotVersions) {
                        w.writeStartElement("snapshotVersion");
                        element(w, "classifier", sv.classifier());
                        element(w, "extension", sv.extension());
                        element(w, "value", sv.value());
                        element(w, "updated", sv.updated());
                        w.writeEndElement();
                    }
                    w.writeEndElement();
                }
                w.writeEndElement();
            }
            if (!plugins.isEmpty()) {
                w.writeStartElement("plugins");
                for (Plugin p : plugins) {
                    w.writeStartElement("plugin");
                    element(w, "name", p.name());
                    element(w, "prefix", p.prefix());
                    element(w, "artifactId", p.artifactId());
                    w.writeEndElement();
                }
                w.writeEndElement();
            }
            w.writeEndElement();
            w.writeEndDocument();
            w.close();
            return out.toString();
        } catch (XMLStreamException e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] toBytes() {
        return toXml().getBytes(StandardCharsets.UTF_8);
    }

    public static String now() {
        return LAST_UPDATED.format(Instant.now());
    }

    // --- accessors ---

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public void setArtifactId(String artifactId) {
        this.artifactId = artifactId;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getLatest() {
        return latest;
    }

    public void setLatest(String latest) {
        this.latest = latest;
    }

    public String getRelease() {
        return release;
    }

    public void setRelease(String release) {
        this.release = release;
    }

    public List<String> getVersions() {
        return versions;
    }

    public String getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(String lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public String getSnapshotTimestamp() {
        return snapshotTimestamp;
    }

    public void setSnapshotTimestamp(String snapshotTimestamp) {
        this.snapshotTimestamp = snapshotTimestamp;
    }

    public String getSnapshotBuildNumber() {
        return snapshotBuildNumber;
    }

    public void setSnapshotBuildNumber(String snapshotBuildNumber) {
        this.snapshotBuildNumber = snapshotBuildNumber;
    }

    public List<SnapshotVersion> getSnapshotVersions() {
        return snapshotVersions;
    }

    public List<Plugin> getPlugins() {
        return plugins;
    }

    // --- xml helpers ---

    private static void element(XMLStreamWriter w, String name, String value) throws XMLStreamException {
        if (value == null) {
            return;
        }
        w.writeStartElement(name);
        w.writeCharacters(value);
        w.writeEndElement();
    }

    private static Element child(Element parent, String name) {
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n instanceof Element e && name.equals(e.getTagName())) {
                return e;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> list = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n instanceof Element e && name.equals(e.getTagName())) {
                list.add(e);
            }
        }
        return list;
    }

    private static String text(Element parent, String name) {
        Element e = child(parent, name);
        if (e == null) {
            return null;
        }
        String t = e.getTextContent();
        return t == null ? null : t.trim();
    }
}
