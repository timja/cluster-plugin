package io.jenkins.plugins.cnj.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SavePointsTest {

    @Test
    void aSavePointCarriesWhatStepsLeftInTheBuildDirectory(@TempDir Path build) throws Exception {
        for (String f : new String[] {"program.dat", "build.xml", "log", "log-index", "workflow/3.xml", "stashes/out.tar.gz",
                "archive/report.txt", "partial.tmp"}) {
            Files.createDirectories(build.resolve(f).getParent());
            Files.writeString(build.resolve(f), f);
        }
        Set<String> carried = new TreeSet<>();
        for (Path p : SavePoints.otherFiles(build).keySet()) {
            carried.add(build.relativize(p).toString().replace('\\', '/'));
        }
        // program.dat, the record and the flow nodes are save points of their own; the log is shipped by its storage
        assertEquals(Set.of("archive/report.txt", "stashes/out.tar.gz"), carried);
    }
}
