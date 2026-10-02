package opendoja.probes;

import opendoja.host.OpenDoJaLaunchArgs;
import opendoja.host.OpenDoJaPaths;
import opendoja.host.storage.DoJaStorageHost;

import java.nio.file.Path;

public final class OpenDoJaPathsProbe {
    private OpenDoJaPathsProbe() {
    }

    public static void main(String[] args) {
        verifyHostDataRootIsArtifactAdjacent();
        verifyExt0RootLivesUnderHostDataRoot();
        verifyBridgeDirLivesUnderHostDataRoot();

        System.out.println("OpenDoJa paths probe OK");
    }

    private static void verifyHostDataRootIsArtifactAdjacent() {
        Path expected = OpenDoJaPaths.artifactDirectory().resolve(".opendoja").normalize();
        Path actual = OpenDoJaPaths.hostDataRoot();
        check(expected.equals(actual), ".opendoja root should resolve next to the runtime artifact");
    }

    private static void verifyExt0RootLivesUnderHostDataRoot() {
        Path expected = OpenDoJaPaths.hostDataRoot().resolve("storage").resolve("ext0").normalize();
        Path actual = DoJaStorageHost.deviceRoot();
        check(expected.equals(actual), "ext0 should live under the shared .opendoja host root");
    }

    private static void verifyBridgeDirLivesUnderHostDataRoot() {
        if (System.getProperty(OpenDoJaLaunchArgs.BRIDGE_ROOT) != null) {
            return; // explicitly relocated
        }
        Path expected = OpenDoJaPaths.hostDataRoot().resolve("bridge").resolve("ma7").normalize();
        check(expected.equals(OpenDoJaPaths.bridgeDir("ma7")), "bridge folders should live under .opendoja/bridge/<id>");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
