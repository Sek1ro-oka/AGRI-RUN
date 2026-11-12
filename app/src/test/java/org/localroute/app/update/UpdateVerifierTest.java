package org.localroute.app.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class UpdateVerifierTest {

    @Test
    public void sha256MatchesKnownValue() throws IOException {
        File file = File.createTempFile("legion-update", ".apk");
        try {
            Files.write(file.toPath(), "abc".getBytes(StandardCharsets.US_ASCII));
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                    UpdateVerifier.sha256(file));
        } finally {
            if (!file.delete()) {
                file.deleteOnExit();
            }
        }
    }

    @Test
    public void sha256ReturnsNullForMissingFile() {
        assertNull(UpdateVerifier.sha256(new File("definitely-not-a-real-update.apk")));
    }
}
