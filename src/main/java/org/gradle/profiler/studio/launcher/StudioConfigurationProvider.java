package org.gradle.profiler.studio.launcher;

import com.dd.plist.NSDictionary;
import com.dd.plist.NSObject;
import com.dd.plist.NSString;
import com.dd.plist.PropertyListParser;
import com.google.common.collect.ImmutableMap;
import org.gradle.profiler.OperatingSystem;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class StudioConfigurationProvider {

    public static StudioConfiguration getLaunchConfiguration(Path studioInstallDir) {
        if (OperatingSystem.isMacOS()) {
            return getMacOSConfiguration(studioInstallDir);
        } else {
            return getWindowsOrLinuxConfiguration(studioInstallDir);
        }
    }

    private static StudioConfiguration getWindowsOrLinuxConfiguration(Path studioInstallDir) {
        String mainClass = "com.intellij.idea.Main";
        List<Path> classPath = Stream.of("lib/bootstrap.jar", "lib/util.jar", "lib/jdom.jar", "lib/log4j.jar", "lib/jna.jar")
            .map(studioInstallDir::resolve)
            .collect(Collectors.toList());
        Path javaCommand = studioInstallDir.resolve("jre/bin/java");
        Map<String, String> systemProperties = ImmutableMap.<String, String>builder()
            .put("idea.vendor.name", "Google")
            .put("idea.executable", "studio")
            .put("idea.platform.prefix", "AndroidStudio")
            .build();
        return new StudioConfiguration(mainClass, studioInstallDir, javaCommand, classPath, systemProperties);
    }

    private static StudioConfiguration getMacOSConfiguration(Path studioInstallDir) {
        Dict entries = parse(studioInstallDir.resolve("Contents/Info.plist"));
        Path actualInstallDir;
        if ("jetbrains-toolbox-launcher".equals(entries.string("CFBundleExecutable"))) {
            actualInstallDir = Paths.get(entries.string("JetBrainsToolboxApp"));
            entries = parse(actualInstallDir.resolve("Contents/Info.plist"));
        } else {
            actualInstallDir = studioInstallDir;
        }

        Dict jvmOptions = entries.dict("JVMOptions");
        List<Path> classPath = Arrays.stream(jvmOptions.string("ClassPath").split(":"))
            .map(s -> FileSystems.getDefault().getPath(s.replace("$APP_PACKAGE", actualInstallDir.toString())))
            .collect(Collectors.toList());
        Map<String, String> systemProperties = mapValues(jvmOptions.dict("Properties").toMap(), v -> v.replace("$APP_PACKAGE", actualInstallDir.toString()));
        String mainClass = jvmOptions.string("MainClass");
        Path javaCommand = actualInstallDir.resolve("Contents/jre/Contents/Home/bin/java");
        return new StudioConfiguration(mainClass, actualInstallDir, javaCommand, classPath, systemProperties);
    }

    private static Dict parse(Path infoFile) {
        try {
            return new Dict((NSDictionary) PropertyListParser.parse(infoFile.toFile()));
        } catch (Exception e) {
            throw new RuntimeException(String.format("Could not parse '%s'.", infoFile), e);
        }
    }

    private static <T, S> Map<String, S> mapValues(Map<String, T> map, Function<T, S> mapper) {
        Map<String, S> result = new LinkedHashMap<>();
        for (Map.Entry<String, T> entry : map.entrySet()) {
            result.put(entry.getKey(), mapper.apply(entry.getValue()));
        }
        return result;
    }

    private static class Dict {
        private final NSDictionary contents;

        public Dict(NSDictionary contents) {
            this.contents = contents;
        }

        Dict dict(String key) {
            return new Dict((NSDictionary) getEntry(key));
        }

        String string(String key) {
            return ((NSString) getEntry(key)).getContent();
        }

        Map<String, String> toMap() {
            return mapValues(contents, v -> ((NSString) v).getContent());
        }

        private NSObject getEntry(String key) {
            NSObject value = contents.get(key);
            if (value == null) {
                throw new IllegalArgumentException(String.format("Dictionary does not contain entry '%s'.", key));
            }
            return value;
        }
    }
}
