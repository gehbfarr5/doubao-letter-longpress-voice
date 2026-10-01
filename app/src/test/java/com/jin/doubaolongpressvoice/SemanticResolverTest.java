package com.jin.doubaolongpressvoice;

import org.junit.Test;
import org.junit.Assume;
import static org.junit.Assert.*;
import java.util.*;
import org.luckypray.dexkit.DexKitBridge;

public class SemanticResolverTest {
    @Test public void rejectsMissingAndAmbiguousCandidates() {
        for (List<String> candidates : Arrays.asList(Collections.<String>emptyList(), Arrays.asList("a", "b"))) {
            assertThrows(IllegalStateException.class, () -> SemanticResolver.one("active", candidates, x -> true));
        }
        assertEquals("renamed", SemanticResolver.one("active", Arrays.asList("renamed"), x -> true));
    }
    @Test public void renamedDexAndNegativeFixtures() throws Exception {
        String library = System.getenv("DEXKIT_HOST_LIBRARY");
        String root = System.getenv("DOUBAO_SYNTHETIC_FIXTURES");
        Assume.assumeTrue(library != null && root != null);
        System.load(library);
        Properties original = null;
        for (String name : Arrays.asList("alpha", "renamed", "ambiguous", "missing")) {
            byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(root, name, "dex", "classes.dex"));
            try (DexKitBridge bridge = DexKitBridge.create(new byte[][]{bytes})) {
                if (name.equals("ambiguous") || name.equals("missing")) {
                    IllegalStateException e = assertThrows(IllegalStateException.class,
                            () -> new SemanticResolver(bridge).resolve());
                    assertTrue(e.getMessage(), e.getMessage().startsWith("active: expected 1 candidate"));
                } else {
                    Properties mapping = new SemanticResolver(bridge).resolve();
                    if (original == null) original = mapping;
                    else for (String key : DoubaoCompatAdapter.METHOD_KEYS)
                        assertNotEquals(key, original.getProperty(key), mapping.getProperty(key));
                }
                System.out.println("synthetic " + name + " PASS");
            }
        }
    }
    @Test public void realApksUseIdenticalRules() throws Exception {
        String library = System.getenv("DEXKIT_HOST_LIBRARY");
        String apks = System.getenv("DOUBAO_APK_FIXTURES");
        Assume.assumeTrue(library != null && apks != null);
        System.load(library);
        for (String apk : apks.split(":")) {
            long start = System.nanoTime();
            try (DexKitBridge bridge = DexKitBridge.create(apk)) {
                Properties mapping = new SemanticResolver(bridge).resolve();
                assertEquals(SemanticResolver.RULE_VERSION, mapping.getProperty("rules"));
                System.out.println("APK=" + apk + " ms=" + (System.nanoTime()-start)/1000000);
                mapping.stringPropertyNames().stream().sorted().forEach(k ->
                        System.out.println(k + "=" + mapping.getProperty(k)));
            }
        }
    }
}
