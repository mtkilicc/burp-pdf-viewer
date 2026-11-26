package pdfviewer;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

final class PdfViewerWindow extends JDialog {
    private byte[] pdfBytes;
    private PdfDocument document;
    private final int pageCount;
    private final JLabel image = new JLabel("Loading…", SwingConstants.CENTER);
    private final JLabel status = new JLabel();
    private final JButton previous = new JButton("‹ Previous");
    private final JButton next = new JButton("Next ›");
    private final JButton save = new JButton("Save PDF…");
    private final JSlider zoom = new JSlider(50, 250, 110);
    private int page;
    private boolean rendering;
    private boolean disposed;

    PdfViewerWindow(Frame owner, PdfDocument document, byte[] bytes, String source) {
        super(owner, "PDF Viewer — " + source, false);
        this.pdfBytes = bytes;
        this.document = document;
        this.pageCount = document.pageCount();

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());
        image.setVerticalAlignment(SwingConstants.TOP);
        image.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        add(new JScrollPane(image), BorderLayout.CENTER);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 6));
        zoom.setPreferredSize(new Dimension(140, 28));
        zoom.setToolTipText("Zoom level");
        controls.add(previous);
        controls.add(status);
        controls.add(next);
        controls.add(new JLabel("Zoom"));
        controls.add(zoom);
        controls.add(save);
        add(controls, BorderLayout.SOUTH);

        previous.addActionListener(e -> showPage(page - 1));
        next.addActionListener(e -> showPage(page + 1));
        zoom.addChangeListener(e -> { if (!zoom.getValueIsAdjusting()) showPage(page); });
        save.addActionListener(e -> savePdf());

        setSize(980, 820);
        setLocationRelativeTo(owner);
    }

    void showPage(int requestedPage) {
        if (disposed) return;
        page = Math.max(0, Math.min(requestedPage, pageCount - 1));
        previous.setEnabled(page > 0);
        next.setEnabled(page + 1 < pageCount);
        status.setText((page + 1) + " / " + pageCount);
        image.setIcon(null);
        image.setText("Rendering page…");
        if (!rendering) renderPage(page, zoom.getValue());
    }

    private void renderPage(int requestedPage, int requestedZoom) {
        rendering = true;
        PdfDocument currentDocument = document;
        new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() throws IOException {
                return currentDocument.render(requestedPage, requestedZoom);
            }

            @Override
            protected void done() {
                rendering = false;
                if (disposed) {
                    releaseDocument();
                    return;
                }
                if (page != requestedPage || zoom.getValue() != requestedZoom) {
                    renderPage(page, zoom.getValue());
                    return;
                }
                try {
                    image.setIcon(new ImageIcon(get()));
                    image.setText(null);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    image.setText("Could not display this page.");
                } catch (ExecutionException failure) {
                    image.setText(failure.getCause() instanceof PdfDocument.RenderSizeException
                            ? "This page is too large at the current zoom level. Zoom out to try again."
                            : "Could not display this page.");
                }
            }
        }.execute();
    }

    private void savePdf() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save PDF");
        chooser.setSelectedFile(new File("document.pdf"));
        chooser.setFileFilter(new FileNameExtensionFilter("PDF file", "pdf"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION || disposed) return;
        Path target = chooser.getSelectedFile().toPath().toAbsolutePath();
        if (!target.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            target = target.resolveSibling(target.getFileName() + ".pdf");
        }
        boolean replace = Files.exists(target);
        if (replace && JOptionPane.showConfirmDialog(this, "Replace the existing file?", "Confirm replacement",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        if (disposed) return;

        byte[] bytes = pdfBytes;
        Path output = target;
        save.setEnabled(false);
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws IOException {
                writePdf(output, bytes, replace);
                return null;
            }

            @Override
            protected void done() {
                if (disposed) return;
                save.setEnabled(true);
                try {
                    get();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException failure) {
                    JOptionPane.showMessageDialog(PdfViewerWindow.this, "Could not save the PDF.",
                            "Save failed", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private static void writePdf(Path target, byte[] bytes, boolean replace) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".pdf-viewer-", ".tmp");
        try {
            Files.write(temporary, bytes);
            if (replace) {
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                Files.move(temporary, target);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        pdfBytes = null;
        image.setIcon(null);
        if (!rendering) releaseDocument();
        super.dispose();
    }

    private void releaseDocument() {
        closeDocument(document);
        document = null;
    }

    static void closeDocument(PdfDocument document) {
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws IOException {
                document.close();
                return null;
            }
        }.execute();
    }
}
