package kk.imageviewer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.StreamSupport;

public class DirectoryHandler {
    private final Set<String> supportedFiles = new HashSet<>();
    private final Path path;
    private Path[] files;

    {
        supportedFiles.add("jpeg");
        supportedFiles.add("jpg");
    }

    public DirectoryHandler(Path path) throws IOException {
        this.path = path;
        loadFiles();
    }

    private boolean filterFileName(Path file) {
        String fileName = file.toString();
        return supportedFiles.contains(fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase());
    }

    private void loadFiles() throws IOException {
        try (var directoryStream = Files.newDirectoryStream(path, this::filterFileName)) {
            files = StreamSupport.stream(directoryStream.spliterator(), false).toArray(Path[]::new);
        }
        Arrays.sort(files);
    }

    public int getN() {
        return files.length;
    }

    public Path getFile(int n) {
        if (n < 0 || n >= files.length)
            return null;
        return files[n];
    }

    public void delete(int idx) {
        try {
            Files.delete(files[idx]);
            loadFiles();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
