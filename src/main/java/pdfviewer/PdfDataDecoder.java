package pdfviewer;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

final class PdfDataDecoder {
    static final int MAX_INPUT_BYTES = 64 * 1024 * 1024;
    private static final String BASE64_PDF_PREFIX = "JVBERi0";
    private static final String PDF_DATA_URI = "data:application/pdf";
    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PDF_END = "%%EOF".getBytes(StandardCharsets.US_ASCII);

    record Result(byte[] bytes, String description) {}

    static final class DecodeException extends Exception {
        DecodeException(String message) { super(message); }
    }

    static Result decode(byte[] input) throws DecodeException {
        if (input == null || input.length == 0) throw new DecodeException("The selected data is empty.");
        if (input.length > MAX_INPUT_BYTES) throw tooLarge();

        Result raw = extractPdf(input, "raw PDF");
        if (raw != null) return raw;

        String text = new String(input, StandardCharsets.ISO_8859_1).trim();
        String normalized = decodeEscapes(text);
        Result result = decodeValue(normalized, "Base64 PDF");
        if (result == null) result = decodeQuotedValues(text);
        if (result == null) result = decodeParameters(text);
        if (result == null) result = scanForBase64Pdf(normalized);
        if (result != null) return result;

        throw new DecodeException("No PDF data found. Select a raw PDF or a Base64 value.");
    }

    static DecodeException tooLarge() {
        return new DecodeException("The selected data exceeds the 64 MiB limit.");
    }

    private static Result decodeQuotedValues(String text) {
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (start >= 0 && c == '\\') {
                i++;
            } else if (c == '"') {
                if (start < 0) {
                    start = i + 1;
                } else {
                    Result result = decodeValue(decodeEscapes(text.substring(start, i)), "Base64 PDF in JSON");
                    if (result != null) return result;
                    start = -1;
                }
            }
        }
        return null;
    }

    private static Result decodeParameters(String text) {
        int start = 0;
        int equals = -1;
        for (int i = 0; i <= text.length(); i++) {
            char c = i < text.length() ? text.charAt(i) : '&';
            if (c == '=' && equals < start) equals = i;
            if (c == '&' || c == ';') {
                if (equals >= start) {
                    Result result = decodeValue(decodeEscapes(text.substring(equals + 1, i)),
                            "Base64 PDF in a parameter");
                    if (result != null) return result;
                }
                start = i + 1;
            }
        }
        return null;
    }

    private static Result scanForBase64Pdf(String text) {
        int marker = text.indexOf(BASE64_PDF_PREFIX);
        while (marker >= 0) {
            int end = marker + BASE64_PDF_PREFIX.length();
            while (end < text.length() && (isBase64(text.charAt(end)) || isSpace(text.charAt(end)))) end++;
            // Padding marks the end of the value, even if more text follows.
            for (int count = 0; count < 2 && end < text.length() && text.charAt(end) == '='; count++) end++;
            Result result = decodeValue(text.substring(marker, end), "Base64 PDF in text");
            if (result != null) return result;
            marker = text.indexOf(BASE64_PDF_PREFIX, end);
        }
        return null;
    }

    private static Result decodeValue(String value, String description) {
        value = value.trim();
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            value = value.substring(1, value.length() - 1).trim();
        }
        if (value.regionMatches(true, 0, PDF_DATA_URI, 0, PDF_DATA_URI.length())) {
            int comma = value.indexOf(',');
            String flag = ";base64";
            if (comma < PDF_DATA_URI.length() + flag.length()
                    || value.charAt(PDF_DATA_URI.length()) != ';'
                    || !value.regionMatches(true, comma - flag.length(), flag, 0, flag.length())) return null;
            value = value.substring(comma + 1);
            description = "PDF data URI";
        }

        StringBuilder compact = null;
        boolean urlSafe = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (isSpace(c)) {
                if (compact == null) compact = new StringBuilder().append(value, 0, i);
            } else {
                if (c != '=' && !isBase64(c)) return null;
                urlSafe |= c == '-' || c == '_';
                if (compact != null) compact.append(c);
            }
        }
        String encoded = compact == null ? value : compact.toString();
        if (encoded.length() < 8) return null;
        try {
            Base64.Decoder decoder = urlSafe ? Base64.getUrlDecoder() : Base64.getDecoder();
            return extractPdf(decoder.decode(encoded), description);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String decodeEscapes(String text) {
        int start = 0;
        StringBuilder decoded = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int length = 0;
            int value = -1;
            if (c == '%' && i + 2 < text.length()) {
                value = hexValue(text, i + 1, 2);
                if (value >= 0) length = 3;
            } else if (c == '\\' && i + 1 < text.length()) {
                int escaped = switch (text.charAt(i + 1)) {
                    case '/' -> '/';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case 'u' -> i + 5 < text.length() ? hexValue(text, i + 2, 4) : -1;
                    default -> -1;
                };
                if (escaped >= 0) {
                    value = escaped;
                    length = text.charAt(i + 1) == 'u' ? 6 : 2;
                } else if (text.charAt(i + 1) == '\\' || text.charAt(i + 1) == '"') {
                    i++;
                }
            }
            if (length == 0) continue;
            if (decoded == null) decoded = new StringBuilder();
            decoded.append(text, start, i).append((char) value);
            i += length - 1;
            start = i + 1;
        }
        return decoded == null ? text : decoded.append(text, start, text.length()).toString();
    }

    private static int hexValue(String text, int start, int length) {
        int value = 0;
        for (int i = start; i < start + length; i++) {
            int digit = Character.digit(text.charAt(i), 16);
            if (digit < 0) return -1;
            value = value * 16 + digit;
        }
        return value;
    }

    private static boolean isBase64(char c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
                || c == '+' || c == '/' || c == '-' || c == '_';
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\f' || c == '\u000b';
    }

    private static Result extractPdf(byte[] bytes, String description) {
        for (int start = 0; start + 8 < bytes.length; start++) {
            if (bytes[start] != '%' || !matches(bytes, start, PDF_MAGIC)) continue;
            if (!isDigit(bytes[start + 5]) || bytes[start + 6] != '.' || !isDigit(bytes[start + 7])) continue;
            if (bytes[start + 8] != '\r' && bytes[start + 8] != '\n') continue;

            int end = bytes.length;
            for (int i = bytes.length - PDF_END.length; i > start; i--) {
                if (bytes[i] != '%' || (bytes[i - 1] != '\r' && bytes[i - 1] != '\n')
                        || !matches(bytes, i, PDF_END)) continue;
                int boundary = i + PDF_END.length;
                while (boundary < bytes.length && (bytes[boundary] == ' ' || bytes[boundary] == '\t')) boundary++;
                if (boundary < bytes.length && bytes[boundary] != '\r' && bytes[boundary] != '\n') continue;
                if (boundary < bytes.length && bytes[boundary] == '\r') boundary++;
                if (boundary < bytes.length && bytes[boundary] == '\n') boundary++;
                end = boundary;
                break;
            }
            byte[] pdf = start == 0 && end == bytes.length ? bytes : Arrays.copyOfRange(bytes, start, end);
            return new Result(pdf, description);
        }
        return null;
    }

    private static boolean isDigit(byte value) {
        return value >= '0' && value <= '9';
    }

    private static boolean matches(byte[] bytes, int offset, byte[] pattern) {
        for (int i = 0; i < pattern.length; i++) {
            if (bytes[offset + i] != pattern[i]) return false;
        }
        return true;
    }
}
