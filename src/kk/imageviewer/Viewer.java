package kk.imageviewer;


import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.LogManager;

public class Viewer extends JFrame {

    private final DirHandler dirHandler;
    private final ImageCache cache;

    private final Map<File, BufferedImage> imageCache = new HashMap<>();
    private final Map<File, BufferedImage> renderedCache = new HashMap<>();
    AtomicReference<BufferedImage> imgRef = new AtomicReference<>();
    AtomicReference<Integer> lastImageIdx = new AtomicReference<>(-1);
    private final JPanel imagePanel = new JPanel(true) {
        {
            setBackground(Color.BLACK);
            addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {

                }
            });
        }


        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            BufferedImage img = imgRef.get();
            if (img == null)
                return;

            var sw = img.getWidth();
            var sh = img.getHeight();
            var tw = getWidth();
            var th = getHeight();

            g.drawImage(img, (tw - sw) / 2, (th - sh) / 2, null);
        }

        @Override
        protected void paintChildren(Graphics g) {
        }
    };
    private int currentIdx = 0;
    private ImageCache.ImageFutureHandle currentResult;

    public Viewer(String dirPath) throws HeadlessException {
        super("ImageViewer");
        this.setDefaultCloseOperation(EXIT_ON_CLOSE);
        this.setSize(1000, 800);
        this.add(imagePanel);
        setBackground(Color.BLACK);
        this.dirHandler = new DirHandler(new File(dirPath));
        this.cache = new ImageCache(dirHandler, 0, 4);
        this.currentIdx = 0;
        this.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_DOWN) {
                    if (currentResult != null && !currentResult.future().isDone())
                        return;
                    loadNext();
                }
                else if (e.getKeyCode() == KeyEvent.VK_UP) {
                    if (currentResult != null && !currentResult.future().isDone())
                        return;
                    loadPrev();
                }
                else if (e.getKeyCode() == KeyEvent.VK_DELETE) {
                    if (currentResult != null && !currentResult.future().isDone())
                        return;
                    if (e.isShiftDown()) {
                        if (System.currentTimeMillis() - e.getWhen() < 100) {
                            if (currentIdx != lastImageIdx.get()) {
                                System.out.println("index doesn't match!");
                            } else {
                                System.out.println("deleting " + lastImageIdx);
                                cache.delete(currentResult.idx());
                                load(currentIdx);
                            }
                        }
                    }
                }
            }
        });
        load(currentIdx);
    }



    public static void main(String[] args) throws UnsupportedLookAndFeelException, ClassNotFoundException, InstantiationException, IllegalAccessException, IOException {
        LogManager.getLogManager().readConfiguration(new FileInputStream("logging.properties"));
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        new Viewer("/media/sshfs0/photo/").setVisible(true);
    }

    private void load(int idx) {
        if (this.currentResult != null)
            currentResult.future().cancel(false);
        ImageCache.ImageFutureHandle result = cache.loadImage(idx, 1000, 800);
        currentResult = result;
        this.setTitle(result.fileName() + " loading...");
        result.future().thenAcceptAsync(res -> {
            if (!Objects.equals(res.fileName(), currentResult.fileName())) {
                System.out.println("filenames not equal");
                return;
            }
            imgRef.set(res.image());
            lastImageIdx.set(res.indexInDir());
            this.setTitle(res.fileName());
            try {
                SwingUtilities.invokeAndWait(imagePanel::repaint);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } catch (InvocationTargetException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void loadNext() {
        if (currentIdx >= dirHandler.getN() - 1)
            return;
        load(++currentIdx);
    }

    private void loadPrev(){
        if (currentIdx <= 0)
            return;
        load(--currentIdx);
    }

    private void loadImage(File file) {

        imgRef.set(imageCache.computeIfAbsent(file, f -> {
            try {
                return ImageIO.read(f);
            } catch (IOException ignored) {
            }
            return null;
        }));
        SwingUtilities.invokeLater(imagePanel::repaint);
    }
}
