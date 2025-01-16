package kk.imageviewer;

import org.imgscalr.Scalr;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public class ImageManager {

    private static final int STATUS_DONE = 2;
    private static final int STATUS_WAIT = 0;
    private static final int STATUS_WORK = 1;


    private static final Logger LOG = Logger.getLogger("ImageCache");
    public final int threads;

    private final DirectoryHandler dir;
    private final Map<Integer, ImageProcessing> cache = new HashMap<>();
    private final LinkedBlockingDeque<Integer> workQueue = new LinkedBlockingDeque<>(10);
    private final ImageReaderThread[] readerThreads;
    private final Object updateLock = new Object();
    private final AtomicInteger lastRequest = new AtomicInteger(0);

    public ImageManager(Path directoryPath, int fileCacheSize, int threads) throws IOException {
        this.dir = new DirectoryHandler(directoryPath);
        this.threads = threads;
        readerThreads = new ImageReaderThread[this.threads];
        for (int i = 0; i < threads; i++) {
            readerThreads[i] = new ImageReaderThread(i);
            new Thread(readerThreads[i]).start();
        }

        new Thread(new CleanerThread()).start();
    }

    public ImageFutureHandle loadImage(int requestedFileIdx, int frameWidth, int frameHeight) {
        String name = dir.getFile(requestedFileIdx).getFileName().toString();
        Size size = new Size(frameWidth, frameHeight);
        lastRequest.set(requestedFileIdx);
        var scheduled = new HashSet<Integer>(15);
        ImageFutureHandle res;
        synchronized (updateLock) {
            for (int i = requestedFileIdx - 5; i <= requestedFileIdx + 5; i++) {
                if (schedule(i, size))
                    scheduled.add(i);
            }
            ImageProcessing imageProcessing = cache.get(requestedFileIdx);
            if (imageProcessing != null) {
                if (imageProcessing.status == STATUS_DONE) {
                    res = new ImageFutureHandle(requestedFileIdx, name,
                            CompletableFuture.completedFuture(new ImageResult(requestedFileIdx, imageProcessing.fileName, imageProcessing.img)));
                } else {
                    CompletableFuture<ImageResult> future = new CompletableFuture<>();
                    if (imageProcessing.future != null)
                        imageProcessing.future.cancel(true);
                    imageProcessing.future = future;

                    res = new ImageFutureHandle(requestedFileIdx, name, future);
                }
            } else {
                throw new IllegalStateException("no mapping in cache");
            }
        }
        boolean requestedIdxScheduled = scheduled.remove(requestedFileIdx);
        if (requestedIdxScheduled) {
            try {
                workQueue.putFirst(requestedFileIdx);
            } catch (InterruptedException e) {
                res.future.cancel(true);
                return res;
            }
        }
        for (Integer idx : scheduled) {
            try {
                workQueue.putLast(idx);
            } catch (InterruptedException ignored) {
            }
        }

        return res;
    }

    private boolean schedule(int idx, Size size) {
        if (idx < 0 || idx >= dir.getN())
            return false;
        String name = dir.getFile(idx).getFileName().toString();
        ImageProcessing imageProcessing = cache.get(idx);
        if (imageProcessing != null) {
            if (imageProcessing.fileName.equals(name) && imageProcessing.outputSize.equals(size))
                return false;
            LOG.info("wrong filename or frame size at idx " + idx + " | " + imageProcessing.fileName);
        }
        cache.put(idx, new ImageProcessing(name, size));
        return true;
    }

    public void delete(int idx) {
        synchronized (updateLock) {
            Iterator<Map.Entry<Integer, ImageProcessing>> it = cache.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Integer, ImageProcessing> next = it.next();
                if (next.getKey() >= idx) {
                    CompletableFuture<ImageResult> future = next.getValue().future;
                    if (future != null)
                        future.cancel(true);
                }
                it.remove();
            }
            dir.delete(idx);
        }
    }

    public int getNumberOfImages() {
        return dir.getN();
    }

    private Size fitImageIntoFrame(Size img, Size frame) {
        var sw = img.width;
        var sh = img.height;

        double sratio = (double) sw / sh;

        var tw = frame.width;
        var th = frame.height;

        double tratio = (double) tw / th;

        int rw, rh;
        if (sratio > tratio) {
            rw = tw;
            rh = (int) (tw / sratio);
        } else {
            rh = th;
            rw = (int) (th * sratio);
        }

        return new Size(rw, rh);
    }

    public record ImageResult(int indexInDir, String fileName, BufferedImage image) {
    }

    public record ImageFutureHandle(int idx, String fileName, CompletableFuture<ImageResult> future) {
    }

    private static class ImageProcessing {
        final String fileName;
        final Size outputSize;
        BufferedImage img = null;
        CompletableFuture<ImageResult> future = null;
        int status = 0;

        public ImageProcessing(String fileName, Size outputSize) {
            this.fileName = fileName;
            this.outputSize = outputSize;
        }

        @Override
        public String toString() {
            return "ImageProcessing{" +
                    "fileName='" + fileName + '\'' +
                    ", outputSize=" + outputSize +
                    ", status=" + status +
                    '}';
        }
    }

    private record Size(int width, int height) {
    }

    private class ImageReaderThread implements Runnable {
        final int id;
        final Logger log;
        private final String name;

        ImageReaderThread(int id) {
            this.id = id;
            this.name = "ImageReaderThread-" + id;
            log = Logger.getLogger(name);
        }

        @Override
        public void run() {
            Thread.currentThread().setName(name);
            while (true) {
                try {
                    int idx = workQueue.take();
                    ImageProcessing imageProcessing;
                    synchronized (updateLock) {

                        imageProcessing = cache.get(idx);

                        if (imageProcessing == null) {
                            log.info("image request is null");
                            continue;
                        }

                        if (Math.abs(lastRequest.get() - idx) > 10) {
                            log.info("image request is too old, request idx is" + idx + ", last requested is " + lastRequest.get());
                            if (imageProcessing.future != null)
                                imageProcessing.future.cancel(true);
                            cache.remove(idx);
                            continue;
                        }

                        if (imageProcessing.status != STATUS_WAIT) {
                            log.info("request is already being handled");
                            continue;
                        }
                        imageProcessing.status = STATUS_WORK;
                    }
                    long loadingStart = System.currentTimeMillis();
                    Path file = dir.getFile(idx);
                    log.info("loading start " + idx + " (" + file.getFileName().toString() + ")");
                    BufferedImage img = ImageIO.read(Files.newInputStream(file));
                    log.info("loading done " + idx + " (" + file.getFileName().toString() + ")" + " in " + (System.currentTimeMillis() - loadingStart) + "ms");
                    loadingStart = System.currentTimeMillis();
                    Size targetImageSize = fitImageIntoFrame(new Size(img.getWidth(), img.getHeight()), imageProcessing.outputSize);
                    img = Scalr.scaleImageIncrementally(img, targetImageSize.width, targetImageSize.height, Scalr.Method.QUALITY, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    log.info("scaling done " + idx + " (" + file.getFileName().toString() + ")" + " in " + (System.currentTimeMillis() - loadingStart) + "ms");
                    synchronized (updateLock) {
                        imageProcessing.status = STATUS_DONE;
                        imageProcessing.img = img;
                        CompletableFuture<ImageResult> future = imageProcessing.future;
                        if (future != null) {
                            future.complete(new ImageResult(idx, imageProcessing.fileName, img));
                        }
                        imageProcessing.future = null;
                    }
                } catch (IOException | InterruptedException e) {
                    e.printStackTrace();
                }

            }
        }
    }

    private class CleanerThread implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    Thread.sleep(4);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                synchronized (updateLock) {
                    cache.keySet().removeIf(idx -> Math.abs(idx - lastRequest.get()) > 15);
                }
            }
        }
    }
}
