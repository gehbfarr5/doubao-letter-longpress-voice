package com.jin.doubaolongpressvoice;

import org.junit.Test;
import static org.junit.Assert.*;

public class SendTransactionTest {
    private SendTransaction ready() {
        SendTransaction t=new SendTransaction(7,"pkg",3,100);
        assertTrue(t.ready(7,"整理后的完整文本",200)); return t;
    }
    @Test public void waitsForFinalTextAndEnabledButtonWithoutFixedDelay() {
        SendTransaction t=ready();
        assertFalse(t.claimClick("pkg",3,true,"旧文本",1,true,201));
        assertFalse(t.claimClick("pkg",3,true,"整理后的完整文本",1,false,202));
        assertTrue(t.claimClick("pkg",3,true,"整理后的完整文本",1,true,203));
        assertFalse(t.claimClick("pkg",3,true,"整理后的完整文本",1,true,204));
        assertFalse(t.ready(7,"整理后的完整文本",205));
        assertTrue(t.observeCleared(true,""));
    }
    @Test public void cannotSendBeforeCompletionOrWithAmbiguousButtons() {
        SendTransaction t=new SendTransaction(7,"pkg",3,100);
        assertFalse(t.claimClick("pkg",3,true,"text",1,true,101));
        assertFalse(t.ready(8,"text",102));
        assertFalse(t.ready(7," ",102));
        assertTrue(t.ready(7,"text",102));
        assertFalse(t.claimClick("pkg",3,true,"text",2,true,103));
    }
    @Test public void navigationAndTimeoutPermanentlyAbort() {
        for(int mode=0;mode<4;mode++) {
            SendTransaction t=ready();
            assertFalse(t.claimClick(mode==0?"other":"pkg",mode==1?4:3,mode!=2,
                    "整理后的完整文本",1,true,mode==3?2200:201));
            assertEquals(SendTransaction.State.ABORTED,t.state());
            assertFalse(t.claimClick("pkg",3,true,"整理后的完整文本",1,true,2201));
        }
    }
    @Test public void clickAcknowledgementIsNotDeliveryAndNeverRetries() {
        SendTransaction t=ready();
        assertTrue(t.claimClick("pkg",3,true,"整理后的完整文本",1,true,201));
        assertFalse(t.observeCleared(false,""));
        assertFalse(t.observeCleared(true,"整理后的完整文本"));
        assertEquals(SendTransaction.State.ATTEMPTED,t.state());
        assertFalse(t.claimClick("pkg",3,true,"整理后的完整文本",1,true,202));
    }
    @Test public void profilesDoNotConfuseSearchOrOtherActionsWithChatSend() {
        assertFalse(SendTargets.allowedEditor("com.google.android.googlequicksearchbox","search_box"));
        assertTrue(SendTargets.allowedEditor("com.google.android.googlequicksearchbox",
                "com.google.android.googlequicksearchbox:id/assistant_robin_input_collapsed_text_half_sheet"));
        for(String label:new String[]{"发送文件","发送图片","resend","send feedback","stop","发送给"})
            assertFalse(label,SendTargets.sendLabel(label));
        assertTrue(SendTargets.sendLabel("发送消息"));
    }
    @Test public void museRequiresItsActualComposerAndSendControl() {
        String pkg="com.facebook.aura";
        assertTrue(SendTargets.allowedEditor(pkg,"hatch-message-input"));
        assertFalse(SendTargets.allowedEditor(pkg,"search"));
        assertFalse(SendTargets.allowedEditor(pkg,null));
        assertTrue(SendTargets.sendControl(pkg,"hatch-send-button","发消息","android.widget.Button",null));
        assertTrue(SendTargets.sendControl(pkg,"hatch-send-button","Send message","android.widget.Button",null));
        assertFalse(SendTargets.sendControl(pkg,"other-button","发消息","android.widget.Button",null));
        assertFalse(SendTargets.sendControl(pkg,"hatch-send-button","语音输入","android.widget.Button",null));
        assertFalse(SendTargets.sendControl("com.anthropic.claude","hatch-send-button","发消息","android.widget.Button",null));
        assertTrue(SendTargets.sendControl("com.anthropic.claude",null,"Send","android.view.View",null));
        assertTrue(SendTargets.sendControl("com.openai.chatgpt",null,"发送消息","android.view.View",null));
    }
    @Test public void accessibilityServiceListManipulationPreservesOtherServicesAndDeduplicates() {
        String gkd="li.songe.gkd/com.google.android.accessibility.selecttospeak.SelectToSpeakService";
        assertFalse(BootRestoreReceiver.containsOurService(null));
        assertFalse(BootRestoreReceiver.containsOurService(""));
        assertFalse(BootRestoreReceiver.containsOurService("null"));
        assertFalse(BootRestoreReceiver.containsOurService(gkd));
        assertTrue(BootRestoreReceiver.containsOurService(BootRestoreReceiver.COMP_SHORT));
        assertTrue(BootRestoreReceiver.containsOurService(gkd+":"+BootRestoreReceiver.COMP_FULL));
        assertEquals("",BootRestoreReceiver.withoutOurService(BootRestoreReceiver.COMP_FULL));
        assertEquals(gkd,BootRestoreReceiver.withoutOurService(
                gkd+":"+BootRestoreReceiver.COMP_SHORT+":"+BootRestoreReceiver.COMP_FULL));
        assertEquals(BootRestoreReceiver.COMP_FULL,BootRestoreReceiver.withOurService(null));
        assertEquals(BootRestoreReceiver.COMP_FULL,BootRestoreReceiver.withOurService("null"));
        assertEquals(gkd+":"+BootRestoreReceiver.COMP_FULL,
                BootRestoreReceiver.withOurService(gkd+":"+BootRestoreReceiver.COMP_SHORT));
    }
}
