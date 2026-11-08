package tv.slink.app;

import java.net.URI;

record UpdateSource(URI uri, String repository) {
    static final String DEFAULT = "https://github.com/krabilousse/slinktv";
    static UpdateSource parse(String value) {
        URI uri;
        try { uri = URI.create(value.trim()); } catch (RuntimeException error) { throw new IllegalArgumentException("Enter a valid HTTPS URL."); }
        https(uri);
        if ("github.com".equalsIgnoreCase(uri.getHost())) {
            String path = uri.getPath().replaceAll("/+$", "").replaceAll("/releases$", "").replaceAll("\\.git$", "");
            if (!path.matches("/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") || uri.getQuery() != null)
                throw new IllegalArgumentException("Use https://github.com/owner/repository.");
            return new UpdateSource(URI.create("https://github.com" + path), path.substring(1));
        }
        return new UpdateSource(uri, "");
    }
    static void https(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("Updates require HTTPS without embedded credentials.");
    }
    String api() { return "https://api.github.com/repos/" + repository; }
    boolean authorize(URI target) {
        return !repository.isEmpty() && "https".equals(target.getScheme()) && "api.github.com".equals(target.getHost())
                && (target.getPort() == -1 || target.getPort() == 443) && target.getUserInfo() == null
                && target.getPath().startsWith("/repos/" + repository + "/") && !target.getPath().contains("..");
    }
}
