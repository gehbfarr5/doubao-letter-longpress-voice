package com.jin.doubaolongpressvoice;
import org.junit.Test;
import static org.junit.Assert.*;
public class NativeAsrTraceTest {
    @Test public void diagnosticsNeverCopyRecognizedText() {
        assertNull(NativeAsrTrace.summarize("recognized private words"));
        assertEquals("use organized result originalLength=20 organizedLength=18",
                NativeAsrTrace.summarize("[SmartOrganizeVoice] use organized result originalLength=20 organizedLength=18 private words"));
        assertEquals("action#invoke currentAllAsrBack=false mHaveVoiceText=true smartOrganizeSendWaitTimeout=true",
                NativeAsrTrace.summarize("DoAsrSend action#invoke currentAllAsrBack: false, mHaveVoiceText = true, smartOrganizeSendWaitTimeout = true"));
    }
}
