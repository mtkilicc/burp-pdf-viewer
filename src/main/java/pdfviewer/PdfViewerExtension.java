package pdfviewer;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;

public final class PdfViewerExtension implements BurpExtension {
    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("Flexible PDF Viewer");
        PdfContextMenu menu = new PdfContextMenu();
        api.userInterface().registerContextMenuItemsProvider(menu);
        api.extension().registerUnloadingHandler(menu::unload);
        api.logging().logToOutput("Flexible PDF Viewer loaded. Right-click a message and choose View as PDF.");
    }
}
