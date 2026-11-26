package pdfviewer;

import org.apache.commons.logging.LogFactory;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

import java.io.ByteArrayOutputStream;
import java.util.Base64;

public final class PdfRenderingTest {
    public static void main(String[] args) throws Exception {
        byte[] pdf;
        try (PDDocument created = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            created.addPage(new PDPage());
            created.addPage(new PDPage(new PDRectangle(4000, 4000)));
            created.save(output);
            pdf = output.toByteArray();
        }

        String json = "{\"content\":\"" + Base64.getEncoder().encodeToString(pdf) + "\"}";
        byte[] decoded = PdfDataDecoder.decode(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)).bytes();
        try (PdfDocument loaded = PdfDocument.open(decoded)) {
            assert loaded.pageCount() == 2;
            assert loaded.render(0, 100).getWidth() == 612;
            try {
                loaded.render(1, 250);
                throw new AssertionError("Oversized raster accepted");
            } catch (PdfDocument.RenderSizeException expected) {}
            assert loaded.render(1, 50).getWidth() == 2000;
        }
        assert !LogFactory.getLog(PdfDocument.class).isWarnEnabled() : "PDFBox logging is enabled";
        System.out.println("PdfRenderingTest: OK");
    }
}
