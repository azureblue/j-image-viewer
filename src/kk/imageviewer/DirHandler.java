package kk.imageviewer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public class DirHandler {
    private final File dir;
    private final Set<String> supportedFiles = new HashSet<>();
    private File[] files;

    {
        supportedFiles.add("jpeg");
        supportedFiles.add("jpg");
    }

    public DirHandler(File dir) {
        this.dir = dir;
        loadFiles();
    }

    private void loadFiles() {
        files = this.dir.listFiles((file, name) -> supportedFiles.contains(name.substring(name.lastIndexOf(".") + 1).toLowerCase()));
        Arrays.sort(files, File::compareTo);
    }

    public DirIterator iter() {
        return new DirIterator();
    }

    public int getN() {
        return files.length;
    }

    public File getFile(int n) {
        if (n < 0 || n >= files.length)
            return null;
        return files[n];
    }

    public void delete(int idx) {
        try {
            Files.delete(files[idx].toPath());
            loadFiles();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    class DirIterator {
        private int idx = -1;

        public Optional<File> next() {
            idx++;
            if (idx >= files.length)
                return Optional.empty();
            return Optional.ofNullable(files[idx]);
        }

        public Optional<File> prev() {
            idx--;
            if (idx < 0)
                return Optional.empty();
            return Optional.ofNullable(files[idx]);
        }
    }

}
