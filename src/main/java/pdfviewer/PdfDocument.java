package pdfviewer;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.IOException;

final class PdfDocument implements AutoCloseable {
    private static final long MAX_RENDER_PIXELS = 16_000_000;
    private static final int MAX_RENDER_EDGE = 8192;

    private final PDDocument document;
    private final PDFRenderer renderer;
    private final int pageCount;

    static PdfDocument open(byte[] bytes) throws IOException {
        return withExtensionClassLoader(() -> {
            PDDocument document = Loader.loadPDF(bytes);
            try {
                int pageCount = document.getNumberOfPages();
                if (pageCount <= 0) throw new IOException("The PDF contains no pages.");
                return new PdfDocument(document, pageCount);
            } catch (IOException | RuntimeException failure) {
                try {
                    document.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            }
        });
    }

    private PdfDocument(PDDocument document, int pageCount) {
        this.document = document;
        this.pageCount = pageCount;
        this.renderer = new PDFRenderer(document);
        this.renderer.setSubsamplingAllowed(true);
    }

    int pageCount() {
        return pageCount;
    }

    BufferedImage render(int page, int zoom) throws IOException {
        return withExtensionClassLoader(() -> {
            float scale = zoom / 100f;
            PDRectangle box = document.getPage(page).getCropBox();
            float width = box.getWidth() * scale;
            float height = box.getHeight() * scale;
            if (!(width > 0 && width <= MAX_RENDER_EDGE && height > 0 && height <= MAX_RENDER_EDGE)
                    || (double) width * height > MAX_RENDER_PIXELS) throw new RenderSizeException();
            return renderer.renderImage(page, scale, ImageType.RGB);
        });
    }

    @Override
    public void close() throws IOException {
        document.close();
    }

    static final class RenderSizeException extends IOException {}

    private static <T> T withExtensionClassLoader(PdfOperation<T> operation) throws IOException {
        // Commons Logging selects its configuration by context class loader. Use the
        // extension's configuration only while PDFBox processes the document.
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(PdfDocument.class.getClassLoader());
        try {
            return operation.run();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @FunctionalInterface
    private interface PdfOperation<T> {
        T run() throws IOException;
    }
}
