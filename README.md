# Flexible PDF Viewer for Burp Suite

Select data in a Burp message editor, right-click, and choose **Extensions > Flexible PDF Viewer > View as PDF**. With no selection, the extension checks the whole request or response body. When you open a PDF from a row in Proxy history, it checks the response body first, then the request body.

## Supported formats

- Raw PDF bytes (`%PDF-...`)
- Standard or URL-safe Base64
- A PDF data URI such as `data:application/pdf;base64,...`
- Base64 strings in a JSON value or an entire JSON message
- Base64 in form or query parameters
- URL-encoded Base64 and Base64 containing whitespace or line breaks

The input limit is 64 MiB. Reduce the zoom level if a page is too large to render. The extension previews documents in memory. Choose **Save PDF…** to write a copy to disk.

## Installation

In Burp Suite, open **Extensions > Installed > Add**, choose **Java**, and select `dist/burp-pdf-viewer.jar`. The JAR includes its runtime dependencies.

## Build

Run the build script from the repository root with Java 17 or later.

```sh
./build.sh
```

The script downloads the Montoya API and Apache PDFBox to `.deps`. After the decoder and rendering tests pass, it writes the self-contained JAR to `dist/burp-pdf-viewer.jar`.
