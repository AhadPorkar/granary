package dev.granary.core.format;

import java.util.Collection;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.HtmlUtils;

/** A plain HTML index page for browsing a repository path, like the ones Maven Central serves. */
public final class DirectoryListing {

    private DirectoryListing() {
    }

    public static ResponseEntity<?> html(RepositoryRequest request, Collection<String> children) {
        String title = "/" + request.repository().getName() + "/" + request.path();
        StringBuilder sb = new StringBuilder(512 + children.size() * 64);
        sb.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>Index of ")
                .append(HtmlUtils.htmlEscape(title)).append("</title>")
                .append("<style>body{font-family:ui-monospace,monospace;margin:2rem}a{display:block;line-height:1.6}</style>")
                .append("</head><body><h1>Index of ").append(HtmlUtils.htmlEscape(title)).append("</h1><hr>");
        if (!request.path().isEmpty()) {
            sb.append("<a href=\"../\">../</a>");
        }
        for (String child : children) {
            String escaped = HtmlUtils.htmlEscape(child);
            sb.append("<a href=\"").append(escaped).append("\">").append(escaped).append("</a>");
        }
        sb.append("<hr></body></html>");
        return ContentResponses.utf8(sb.toString(), "text/html;charset=UTF-8");
    }
}
