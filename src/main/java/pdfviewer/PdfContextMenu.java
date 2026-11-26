package pdfviewer;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.core.Range;
import burp.api.montoya.http.message.HttpMessage;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.ui.contextmenu.MessageEditorHttpRequestResponse;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.Component;
import java.awt.Frame;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;

final class PdfContextMenu implements ContextMenuItemsProvider {
    private final Frame owner;
    private final Set<PdfViewerWindow> windows = new HashSet<>();
    private boolean opening;
    private volatile boolean unloaded;

    PdfContextMenu(Frame owner) {
        this.owner = owner;
    }

    @Override
    public List<Component> provideMenuItems(ContextMenuEvent event) {
        if (unloaded) return List.of();
        Optional<MessageEditorHttpRequestResponse> editor = event.messageEditorRequestResponse();
        List<Input> inputs = new ArrayList<>();

        if (editor.isPresent()) {
            MessageEditorHttpRequestResponse view = editor.get();
            HttpRequestResponse exchange = view.requestResponse();
            HttpMessage message = view.selectionContext() == MessageEditorHttpRequestResponse.SelectionContext.REQUEST
                    ? exchange.request() : exchange.response();
            if (message != null) {
                Range selection = view.selectionOffsets().orElse(null);
                if (selection != null && selection.endIndexExclusive() > selection.startIndexInclusive()) {
                    inputs.add(new Input(message, selection, false, "selected data"));
                    inputs.add(new Input(message, selection, true, "selected body data"));
                } else {
                    inputs.add(new Input(message, null, true, "message body"));
                }
            }
        } else {
            for (HttpRequestResponse exchange : event.selectedRequestResponses()) {
                HttpMessage response = exchange.response();
                HttpMessage request = exchange.request();
                if (response != null) inputs.add(new Input(response, null, true, "response body"));
                if (request != null) inputs.add(new Input(request, null, true, "request body"));
            }
        }

        if (inputs.isEmpty()) return List.of();
        JMenuItem item = new JMenuItem("View as PDF");
        item.addActionListener(ignored -> openPdf(inputs));
        return List.of(item);
    }

    void unload() {
        unloaded = true;
        SwingUtilities.invokeLater(() -> {
            for (PdfViewerWindow window : List.copyOf(windows)) window.dispose();
        });
    }

    private void openPdf(List<Input> inputs) {
        if (unloaded) return;
        if (opening) {
            showError("A PDF is already opening. Try again when it finishes.");
            return;
        }
        opening = true;

        new SwingWorker<OpenedPdf, Void>() {
            @Override
            protected OpenedPdf doInBackground() throws IOException, PdfDataDecoder.DecodeException {
                return findPdf(inputs);
            }

            @Override
            protected void done() {
                opening = false;
                OpenedPdf opened = null;
                PdfViewerWindow window = null;
                try {
                    opened = get();
                    if (unloaded) {
                        PdfViewerWindow.closeDocument(opened.document());
                        return;
                    }
                    window = new PdfViewerWindow(owner, opened.document(), opened.bytes(), opened.source());
                    window.addWindowListener(new WindowAdapter() {
                        @Override
                        public void windowClosed(WindowEvent event) {
                            windows.remove(event.getWindow());
                        }
                    });
                    windows.add(window);
                    window.setVisible(true);
                    window.showPage(0);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException failure) {
                    if (!unloaded) showError(openError(failure.getCause()));
                } catch (RuntimeException failure) {
                    if (window != null) {
                        windows.remove(window);
                        window.dispose();
                    } else if (opened != null) {
                        PdfViewerWindow.closeDocument(opened.document());
                    }
                    if (!unloaded) showError("Could not open the PDF viewer window.");
                }
            }
        }.execute();
    }

    private static OpenedPdf findPdf(List<Input> inputs) throws IOException, PdfDataDecoder.DecodeException {
        IOException parseFailure = null;
        boolean oversized = false;
        for (Input input : inputs) {
            byte[] bytes;
            try {
                bytes = input.bytes();
            } catch (PdfDataDecoder.DecodeException tooLarge) {
                oversized = true;
                continue;
            }
            PdfDataDecoder.Result result;
            try {
                result = PdfDataDecoder.decode(bytes);
            } catch (PdfDataDecoder.DecodeException ignored) {
                continue;
            }
            try {
                return new OpenedPdf(PdfDocument.open(result.bytes()), result.bytes(),
                        input.source() + " · " + result.description());
            } catch (IOException failure) {
                if (parseFailure == null || failure instanceof InvalidPasswordException) parseFailure = failure;
            } catch (RuntimeException failure) {
                if (parseFailure == null) parseFailure = new IOException();
            }
        }
        if (parseFailure != null) throw parseFailure;
        if (oversized) throw PdfDataDecoder.tooLarge();
        throw new PdfDataDecoder.DecodeException("No PDF data found. Select a raw PDF or a Base64 value.");
    }

    private static String openError(Throwable failure) {
        if (failure instanceof PdfDataDecoder.DecodeException) return failure.getMessage();
        if (failure instanceof InvalidPasswordException) return "This PDF is password-protected.";
        return "Could not read the PDF. The document may be incomplete, empty, or damaged.";
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(owner, message, "Could not open PDF", JOptionPane.ERROR_MESSAGE);
    }

    private record OpenedPdf(PdfDocument document, byte[] bytes, String source) {}

    private record Input(HttpMessage message, Range selection, boolean fromBody, String source) {
        byte[] bytes() throws PdfDataDecoder.DecodeException {
            if (selection != null
                    && (long) selection.endIndexExclusive() - selection.startIndexInclusive() > PdfDataDecoder.MAX_INPUT_BYTES) {
                throw PdfDataDecoder.tooLarge();
            }
            ByteArray data = fromBody ? message.body() : message.toByteArray();
            if (selection == null) {
                if (data.length() > PdfDataDecoder.MAX_INPUT_BYTES) throw PdfDataDecoder.tooLarge();
                return data.getBytes();
            }
            if (selection.startIndexInclusive() < 0 || selection.endIndexExclusive() > data.length()) return null;
            return data.subArray(selection).getBytes();
        }
    }
}
