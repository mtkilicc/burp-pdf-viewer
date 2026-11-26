package pdfviewer;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

public final class PdfDataDecoderTest {
    private static final byte[] PDF = "%PDF-1.4\n%%EOF".getBytes(StandardCharsets.US_ASCII);

    public static void main(String[] args) throws Exception {
        acceptsRawPdf();
        acceptsPlainBase64();
        acceptsJsonValue();
        acceptsDataUriAndUrlSafe();
        acceptsWrappedAndEscapedData();
        excludesBytesOutsideRawPdf();
        rejectsTextualPdfMarker();
        rejectsOtherData();
        System.out.println("PdfDataDecoderTest: OK");
    }

    private static void acceptsRawPdf() throws Exception {
        assert startsPdf(PdfDataDecoder.decode(PDF).bytes());
    }

    private static void acceptsPlainBase64() throws Exception {
        assert startsPdf(PdfDataDecoder.decode(Base64.getEncoder().encode(PDF)).bytes());
    }

    private static void acceptsJsonValue() throws Exception {
        String json = "{\"label\":\"synthetic %PDF-1.4\",\"content\":\""
                + Base64.getEncoder().encodeToString(PDF) + "\"}";
        assert startsPdf(PdfDataDecoder.decode(json.getBytes(StandardCharsets.UTF_8)).bytes());
    }

    private static void acceptsDataUriAndUrlSafe() throws Exception {
        byte[] urlSafePdf = "%PDF-1.4\n%~~~~\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(urlSafePdf);
        assert encoded.contains("-") || encoded.contains("_");
        String uri = "data:application/pdf;base64," + encoded;
        PdfDataDecoder.Result result = decode(uri);
        assert Arrays.equals(urlSafePdf, result.bytes());
        assert result.description().equals("PDF data URI");
    }

    private static void acceptsWrappedAndEscapedData() throws Exception {
        String encoded = Base64.getEncoder().encodeToString(PDF);
        assert Arrays.equals(PDF, decode("content: [preview] " + encoded + "\" trailing").bytes());
        String escaped = "\\u004a" + encoded.substring(1, 8) + "\\n" + encoded.substring(8);
        assert Arrays.equals(PDF, decode("{\"pdf\":\"" + escaped + "\"}").bytes());
        String form = "other=sample%26value&pdf=" + encoded.replace("=", "%3D") + "&tail=demo";
        assert Arrays.equals(PDF, decode(form).bytes());
    }

    private static void excludesBytesOutsideRawPdf() throws Exception {
        byte[] expected = "%PDF-1.4\n%%EOF\r\n".getBytes(StandardCharsets.US_ASCII);
        String wrapped = "synthetic-prefix\r\n%PDF-1.4\n%%EOF\r\n--boundary\r\nsynthetic-field";
        assert Arrays.equals(expected, decode(wrapped).bytes());
    }

    private static void rejectsTextualPdfMarker() {
        assertRejected("{\"label\":\"a %PDF-1.4 file\"}");
    }

    private static void rejectsOtherData() {
        assertRejected("not a pdf");
        assertRejected("");
    }

    private static void assertRejected(String value) {
        try {
            decode(value);
            throw new AssertionError("Invalid data accepted");
        } catch (PdfDataDecoder.DecodeException expected) {}
    }

    private static PdfDataDecoder.Result decode(String value) throws PdfDataDecoder.DecodeException {
        return PdfDataDecoder.decode(value.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean startsPdf(byte[] bytes) {
        assert new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-");
        return true;
    }
}
