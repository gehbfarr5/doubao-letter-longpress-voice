package com.jin.doubaolongpressvoice;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.lang.reflect.*;
import org.luckypray.dexkit.wrap.*;

public class DoubaoCompatAdapterTest {
    static class Surface {}
    static class Manager {
        static Manager renamedSingleton = new Manager();
        int cancelled, committed, ordinal;
        public boolean renamedActive() { return true; }
        public void renamedCancel() { cancelled++; }
        public void renamedCommit() { committed++; }
        public void renamedDispatch(int i, long now, boolean enabled) { assertTrue(enabled); assertTrue("last audio must be submitted before dispatch", committed > 0); ordinal=i; }
        public void finalSend(int action,long time) {}
        public boolean U() { throw new AssertionError("old-name decoy must never be invoked"); }
    }
    interface Listener { void renamedEvent(Result result); }
    static class Result { public boolean renamedDone() { return true; } }
    static class Process { static Process instance=new Process(); public void setter(Listener l) {} }
    static class Panel { public void close(boolean b) {} public static Panel instance() { return new Panel(); } }
    static class Context { static Context instance=new Context(); static boolean completed=true; public boolean complete(){return completed;} public void done(int phase, boolean b) {} }
    static class Editor { static Editor instance=new Editor(); public int action() { return 3; } }
    static class Feedback { static Feedback instance=new Feedback(); public void vibrate() {} }
    static void m(Properties p, String key, Class<?> owner, String name, Class<?>... args) throws Exception {
        p.setProperty(key,new DexMethod(owner.getDeclaredMethod(name,args)).toString());
    }
    static void f(Properties p,String key,Class<?> owner,String name) throws Exception {
        p.setProperty(key,new DexField(owner.getDeclaredField(name)).toString());
    }
    static Properties mapping() throws Exception {
        Properties p=new Properties(); p.setProperty("rules",SemanticResolver.RULE_VERSION);
        m(p,"active",Manager.class,"renamedActive"); m(p,"cancel",Manager.class,"renamedCancel");
        m(p,"commit",Manager.class,"renamedCommit");
        m(p,"dispatch",Manager.class,"renamedDispatch",int.class,long.class,boolean.class);
        m(p,"sendFinal",Manager.class,"finalSend",int.class,long.class);
        m(p,"contextAllBack",Context.class,"complete"); f(p,"context",Context.class,"instance");
        m(p,"closePanel",Panel.class,"close",boolean.class); m(p,"inputView",Panel.class,"instance");
        m(p,"listenerSetter",Process.class,"setter",Listener.class); m(p,"callback",Listener.class,"renamedEvent",Result.class);
        m(p,"allBack",Result.class,"renamedDone"); m(p,"contextDone",Context.class,"done",int.class,boolean.class);
        m(p,"enterAction",Editor.class,"action"); m(p,"feedback",Feedback.class,"vibrate");
        f(p,"manager",Manager.class,"renamedSingleton"); f(p,"process",Process.class,"instance");
        f(p,"editor",Editor.class,"instance"); f(p,"feedbackManager",Feedback.class,"instance");
        return p;
    }
    static DoubaoCompatAdapter bind(Properties p) throws Exception {
        return new DoubaoCompatAdapter(p,DoubaoCompatAdapterTest.class.getClassLoader(),"test",Surface.class);
    }
    @Test public void renamedCapabilitiesPreserveCommitCancelAndDispatch() throws Exception {
        DoubaoCompatAdapter a=bind(mapping()); Manager manager=new Manager();
        assertTrue(a.isAsrActive(manager));
        a.stop(manager,false,"release"); a.cancel(manager); a.dispatch(manager,3,42);
        assertEquals(1,manager.committed); assertEquals(1,manager.cancelled); assertEquals(3,manager.ordinal);
        assertSame(Manager.renamedSingleton,a.managerInstance());
    }
    @Test public void unfinishedRecognitionCannotEnterHostSendTimeoutPath() throws Exception {
        DoubaoCompatAdapter a=bind(mapping()); Manager manager=new Manager();
        a.commit(manager); Context.completed=false;
        try { assertThrows(IllegalStateException.class,()->a.dispatch(manager,3,42)); }
        finally { Context.completed=true; }
        a.dispatch(manager,3,42);
        assertEquals(1,manager.committed);
        assertEquals(3,manager.ordinal);
    }
    @Test public void rejectsIncompleteOrWrongTypedCache() throws Exception {
        Properties missing=mapping(); missing.remove("allBack");
        assertThrows(IllegalStateException.class,()->bind(missing));
        Properties wrong=mapping(); wrong.setProperty("active",wrong.getProperty("cancel"));
        assertThrows(IllegalStateException.class,()->bind(wrong));
        Properties rules=mapping(); rules.setProperty("rules","obsolete");
        assertThrows(IllegalStateException.class,()->bind(rules));
    }
    @Test public void fingerprintChangesForContentVersionAndSplits() throws Exception {
        java.io.File file=java.io.File.createTempFile("doubao", ".apk");
        try {
            java.nio.file.Files.write(file.toPath(),new byte[]{1,2});
            String a=AdaptiveLoader.fingerprint(Arrays.asList(file.getPath()),"pkg",1);
            assertNotEquals(a,AdaptiveLoader.fingerprint(Arrays.asList(file.getPath()),"pkg",2));
            assertNotEquals(a,AdaptiveLoader.fingerprint(Arrays.asList(file.getPath(),file.getPath()),"pkg",1));
            java.nio.file.Files.write(file.toPath(),new byte[]{2,1});
            assertNotEquals(a,AdaptiveLoader.fingerprint(Arrays.asList(file.getPath()),"pkg",1));
        } finally { file.delete(); }
    }
}
