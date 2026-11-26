package io.github.fernandoglatz.igpsmap.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.ProcessingInstruction;

/**
 * The Windows .msi built by package.ps1 uses packaging/windows/main.wxs (jpackage --resource-dir)
 * to offer a "Launch" checkbox on the installer's last page.
 */
class InstallerLaunchOptionTest {

    private static final String WIX_NS = "http://schemas.microsoft.com/wix/2006/wi";
    private static final Path MAIN_WXS = Path.of("packaging", "windows", "main.wxs");

    private static Document wxs;

    @BeforeAll
    static void parse() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        wxs = factory.newDocumentBuilder().parse(MAIN_WXS.toFile());
    }

    @Test
    void finishButtonLaunchesTheAppWhenTheCheckboxIsTicked() {
        Element publish = single(elements("Publish").stream()
                .filter(e -> "ExitDialog".equals(e.getAttribute("Dialog"))
                        && "Finish".equals(e.getAttribute("Control"))
                        && "DoAction".equals(e.getAttribute("Event")))
                .toList(), "ExitDialog Finish DoAction");
        String condition = publish.getTextContent().replaceAll("\\s+", " ").trim();
        assertTrue(condition.contains("WIXUI_EXITDIALOGOPTIONALCHECKBOX = 1"), condition);
        assertTrue(condition.contains("NOT Installed"), "must not launch on repair/uninstall: " + condition);

        String actionId = publish.getAttribute("Value");
        Element action = single(elements("CustomAction").stream()
                .filter(e -> actionId.equals(e.getAttribute("Id")))
                .toList(), "CustomAction " + actionId);
        // WixShellExec comes from WixUtilExtension, which jpackage (JDK 17) links into every .msi.
        assertEquals("WixCA", action.getAttribute("BinaryKey"));
        assertEquals("WixShellExec", action.getAttribute("DllEntry"));
        assertEquals("yes", action.getAttribute("Impersonate"), "the app must not run elevated");
    }

    @Test
    void checkboxIsShownTickedAndTargetsTheLauncher() {
        assertEquals("Launch $(var.JpAppName)", property("WIXUI_EXITDIALOGOPTIONALCHECKBOXTEXT"));
        assertEquals("1", property("WIXUI_EXITDIALOGOPTIONALCHECKBOX"));
        // jpackage installs the launcher as INSTALLDIR\<--name>.exe.
        assertEquals("[INSTALLDIR]$(var.JpAppName).exe", property("WixShellExecTarget"));
    }

    @Test
    void keepsTheJpackageTemplateWiring() {
        List<String> groups = elements("ComponentGroupRef").stream().map(e -> e.getAttribute("Id")).toList();
        assertEquals(List.of("Shortcuts", "Files", "FileAssociations"), groups);
        assertEquals("JpUI", single(elements("UIRef"), "UIRef").getAttribute("Id"));
        assertEquals("$(var.JpProductUpgradeCode)", single(elements("Product"), "Product").getAttribute("UpgradeCode"));
        assertTrue(processingInstructions().stream()
                        .anyMatch(pi -> pi.getData().contains("$(var.JpConfigDir)/overrides.wxi")),
                "overrides.wxi include");
        assertNotNull(single(elements("RemoveExistingProducts"), "RemoveExistingProducts"),
                "a new .msi must replace the installed version (in-app updates)");
    }

    @Test
    void windowsPackagingScriptUsesTheTemplate() throws Exception {
        String script = Files.readString(Path.of("package.ps1"));
        assertTrue(script.contains("\"--resource-dir\", \"$AppDir\\packaging\\windows\""), script);
    }

    private static String property(String id) {
        return single(elements("Property").stream()
                .filter(e -> id.equals(e.getAttribute("Id")))
                .toList(), "Property " + id).getAttribute("Value");
    }

    private static List<Element> elements(String name) {
        NodeList nodes = wxs.getElementsByTagNameNS(WIX_NS, name);
        List<Element> list = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            list.add((Element) nodes.item(i));
        }
        return list;
    }

    private static List<ProcessingInstruction> processingInstructions() {
        List<ProcessingInstruction> list = new ArrayList<>();
        collect(wxs, list);
        return list;
    }

    private static void collect(Node node, List<ProcessingInstruction> out) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof ProcessingInstruction pi) {
                out.add(pi);
            }
            collect(child, out);
        }
    }

    private static Element single(List<Element> found, String what) {
        assertEquals(1, found.size(), "expected exactly one " + what);
        return found.get(0);
    }
}
