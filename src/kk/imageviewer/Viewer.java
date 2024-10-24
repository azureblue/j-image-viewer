package kk.imageviewer;


import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.LogManager;

public class Viewer extends JFrame {

    private final ImageManager imageManager;
    private final AtomicReference<Integer> lastImageIdx = new AtomicReference<>(-1);
    private final AtomicReference<BufferedImage> imgRef = new AtomicReference<>();
    private int currentIdx = 0;
    private ImageManager.ImageFutureHandle currentResult;

    private final JPanel imagePanel = new JPanel(true) {
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            var img = imgRef.get();
            if (img == null)
                return;

            g.drawImage(img, (getWidth() - img.getWidth()) / 2, (getHeight() - img.getHeight()) / 2, null);
        }
    };

    public Viewer(Path directory) throws HeadlessException, IOException {
        super("ImageViewer");
        this.setDefaultCloseOperation(EXIT_ON_CLOSE);
        this.setSize(1000, 800);
        this.add(imagePanel);
        this.imagePanel.setBackground(Color.BLACK);
        this.setBackground(Color.BLACK);
        this.imageManager = new ImageManager(directory, 0, 4);
        this.currentIdx = 0;
        setupListeners();
    }

    public static void main(String[] args) throws UnsupportedLookAndFeelException, ClassNotFoundException, InstantiationException, IllegalAccessException, IOException {
        LogManager.getLogManager().readConfiguration(new FileInputStream("logging.properties"));
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        new Viewer(Path.of("/media/sshfs0/photo/")).setVisible(true);
    }

    private boolean isCurrentLoadingInProgress() {
        return currentResult != null && !currentResult.future().isDone();
    }

    private void setupListeners() {
        this.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                super.windowOpened(e);
                load(currentIdx);
            }
        });

        this.addComponentListener(new ComponentAdapter() {
            private final Timer reloadTimer = new Timer(150, (ignored) -> reload());

            {
                reloadTimer.setRepeats(false);
            }

            @Override
            public void componentResized(ComponentEvent e) {
                reloadTimer.restart();
                reloadTimer.start();
            }
        });

        this.addMouseWheelListener(new MouseAdapter() {
            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                if (isCurrentLoadingInProgress())
                    return;
                if (e.getWheelRotation() > 0)
                    loadNext();
                else if (e.getWheelRotation() < 0)
                    loadPrev();
            }
        });

        this.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (isCurrentLoadingInProgress())
                    return;
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_DOWN -> loadNext();
                    case KeyEvent.VK_UP -> loadPrev();
                    case KeyEvent.VK_DELETE -> {
                        if (!e.isShiftDown() || System.currentTimeMillis() - e.getWhen() > 100)
                            break;
                        if (currentIdx != lastImageIdx.get()) {
                            System.out.println("index doesn't match!");
                        } else {
                            System.out.println("deleting " + lastImageIdx);
                            imageManager.delete(currentResult.idx());
                            load(currentIdx);
                        }
                    }
                }
            }
        });
    }

    private void reload() {
        load(currentIdx);
    }

    private void load(int idx) {
        if (this.currentResult != null)
            currentResult.future().cancel(false);
        ImageManager.ImageFutureHandle result = imageManager.loadImage(idx, imagePanel.getWidth(), imagePanel.getHeight());
        currentResult = result;
        this.setTitle(result.fileName() + " loading...");
        int a = 213;
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
            } catch (InterruptedException | InvocationTargetException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void loadNext() {
        if (currentIdx >= imageManager.getNumberOfImages() - 1)
            return;
        load(++currentIdx);
    }

    private void loadPrev() {
        if (currentIdx <= 0)
            return;
        load(--currentIdx);
    }
}
