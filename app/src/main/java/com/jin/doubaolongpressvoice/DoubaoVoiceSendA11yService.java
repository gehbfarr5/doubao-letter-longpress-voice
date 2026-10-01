package com.jin.doubaolongpressvoice;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Native completion grants one click on the same focused editor, never a timed guess. */
public class DoubaoVoiceSendA11yService extends AccessibilityService {
    public static final String ACTION_A11Y_SEND = "com.jin.doubaolongpressvoice.ACTION_A11Y_SEND";
    public static final String EXTRA_TARGET_PKG = "target_pkg";
    static final String EXTRA_ID = "request_id", EXTRA_STAGE = "stage", EXTRA_TEXT = "final_text";
    static final String PREPARE = "prepare", READY = "ready", CANCEL = "cancel";
    private static final String TAG = "DoubaoVoiceSend";
    private static volatile boolean sBound;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BroadcastReceiver mReceiver;
    private boolean mReceiverRegistered;
    private SendTransaction pending;
    private long lastPreparedId;
    private AccessibilityNodeInfo editorIdentity;
    private final Runnable expire = () -> finish("deadline; no retry");

    static boolean isBound() { return sBound; }

    @Override protected void onServiceConnected() {
        super.onServiceConnected(); sBound = true; registerSendReceiver(); startKeepAliveForeground();
    }
    @Override public boolean onUnbind(Intent intent) {
        sBound = false; finish("service unbound"); stopForeground(true); unregisterSendReceiver();
        return super.onUnbind(intent);
    }
    @Override public void onDestroy() {
        sBound = false; finish("service destroyed"); stopForeground(true); unregisterSendReceiver(); super.onDestroy();
    }
    @Override public void onInterrupt() { finish("interrupted"); }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (pending == null || event == null) return;
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && event.getPackageName() != null
                && !pending.packageName.contentEquals(event.getPackageName())
                && !"com.bytedance.android.doubaoime".contentEquals(event.getPackageName())) {
            finish("foreground changed"); return;
        }
        checkTarget();
    }
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerSendReceiver() {
        if (mReceiverRegistered) return;
        mReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent == null || !ACTION_A11Y_SEND.equals(intent.getAction())) return;
                // Identity sharing is supported on Android 14+. Older hosts cannot authorize
                // this cross-process click protocol, so retain text instead of accepting a spoof.
                if (Build.VERSION.SDK_INT < 34 || !"com.bytedance.android.doubaoime".equals(getSentFromPackage())) {
                    Log.w(TAG,"request rejected: unverified sender"); return;
                }
                try { receive(intent); }
                catch (RuntimeException e) { Log.e(TAG,"send protocol failed",e); finish("protocol error"); }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_A11Y_SEND);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(mReceiver,filter,Context.RECEIVER_EXPORTED);
        else registerReceiver(mReceiver,filter);
        mReceiverRegistered = true;
        Log.i(TAG,"a11y send receiver registered");
    }
    private void receive(Intent intent) {
        long id = intent.getLongExtra(EXTRA_ID,0);
        String pkg = intent.getStringExtra(EXTRA_TARGET_PKG);
        String stage = intent.getStringExtra(EXTRA_STAGE);
        if (id <= 0 || !SendTargets.A11Y.contains(pkg)) return;
        if (PREPARE.equals(stage)) {
            if (id <= lastPreparedId || Math.abs(System.currentTimeMillis()-id)>30_000L) return;
            lastPreparedId=id;
            finish("superseded");
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) { Log.w(TAG,"prepare: no active window"); return; }
            try {
                if (!pkg.contentEquals(root.getPackageName())) { Log.w(TAG,"prepare: wrong foreground"); return; }
                AccessibilityNodeInfo editor = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (editor == null) { Log.w(TAG,"prepare: no focused editor"); return; }
                try {
                    if (!validEditor(editor,pkg)) { Log.w(TAG,"prepare: unsupported editor"); return; }
                    editorIdentity = AccessibilityNodeInfo.obtain(editor);
                    pending = new SendTransaction(id,pkg,root.getWindowId(),SystemClock.elapsedRealtime());
                    handler.postDelayed(expire,30_000L);
                    Log.i(TAG,"prepared id="+id+" pkg="+pkg+" window="+root.getWindowId());
                } finally { editor.recycle(); }
            } finally { root.recycle(); }
        } else if (pending != null && pending.id == id && pending.packageName.equals(pkg)) {
            if (CANCEL.equals(stage)) finish("IME cancelled");
            else if (READY.equals(stage) && pending.ready(id,intent.getStringExtra(EXTRA_TEXT),SystemClock.elapsedRealtime())) {
                handler.removeCallbacks(expire); handler.postDelayed(expire,2_000L);
                Log.i(TAG,"native ready id="+id+" elapsedMs="+(SystemClock.elapsedRealtime()-pending.startedAt));
                checkTarget();
            }
        }
    }
    private boolean validEditor(AccessibilityNodeInfo node, String pkg) {
        return pkg.contentEquals(node.getPackageName()) && node.isEditable() && node.isFocused()
                && node.isEnabled() && node.isVisibleToUser() && !node.isPassword()
                && SendTargets.allowedEditor(pkg,node.getViewIdResourceName());
    }
    private void checkTarget() {
        SendTransaction t=pending;
        if(t==null) return;
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null) { finish("window unavailable"); return; }
        try {
            if(!t.packageName.contentEquals(root.getPackageName()) || t.windowId!=root.getWindowId()) {
                finish("window changed"); return;
            }
            AccessibilityNodeInfo editor=root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if(editor==null) { finish("editor lost focus"); return; }
            try {
                boolean same=editorIdentity!=null && editorIdentity.equals(editor) && validEditor(editor,t.packageName);
                if(!same || t.expired(SystemClock.elapsedRealtime())) { finish("editor changed or expired"); return; }
                String text=editor.isShowingHintText()?"":String.valueOf(editor.getText()==null?"":editor.getText());
                if(t.observeCleared(true,text)) { finish("editor cleared after click; delivery not asserted"); return; }
                if(t.state()!=SendTransaction.State.READY) return;
                List<AccessibilityNodeInfo> buttons=findButtonsNearEditor(editor,t.packageName);
                try {
                    boolean enabled=buttons.size()==1 && buttons.get(0).isEnabled() && buttons.get(0).isVisibleToUser();
                    if(t.claimClick(t.packageName,root.getWindowId(),same,text,buttons.size(),enabled,SystemClock.elapsedRealtime())) {
                        boolean clicked=buttons.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        Log.i(TAG,"click attempted id="+t.id+" accepted="+clicked+" elapsedMs="+(SystemClock.elapsedRealtime()-t.startedAt));
                        // A false return can be an uncertain delivery; never issue a second click.
                        handler.removeCallbacks(expire); handler.postDelayed(expire,2_000L);
                    }
                } finally { for(AccessibilityNodeInfo n:buttons)n.recycle(); }
            } finally { editor.recycle(); }
        } catch(RuntimeException e) { Log.e(TAG,"target check failed",e); finish("node error"); }
        finally { root.recycle(); }
    }
    /** Find the smallest ancestor containing the editor and an unambiguous labelled send action. */
    private List<AccessibilityNodeInfo> findButtonsNearEditor(AccessibilityNodeInfo editor,String pkg) {
        AccessibilityNodeInfo container=editor.getParent();
        for(int level=0;container!=null && level<16;level++) {
            List<AccessibilityNodeInfo> found=new ArrayList<>();
            try {
                collect(container,container,pkg,found,new HashSet<>(),new int[]{0},0);
                if(!found.isEmpty()) return found;
                AccessibilityNodeInfo parent=container.getParent(); container.recycle(); container=parent;
            } catch(RuntimeException e) { for(AccessibilityNodeInfo n:found)n.recycle(); throw e; }
            finally { if(!found.isEmpty())container.recycle(); }
        }
        if(container!=null)container.recycle();
        return new ArrayList<>();
    }
    private void collect(AccessibilityNodeInfo node,AccessibilityNodeInfo boundary,String pkg,List<AccessibilityNodeInfo> out,
                         Set<AccessibilityNodeInfo> seen,int[] visited,int depth) {
        if(node==null || !pkg.contentEquals(node.getPackageName()))return;
        if(++visited[0]>600 || depth>50)throw new IllegalStateException("node traversal budget exceeded");
        boolean label=SendTargets.sendControl(pkg,node.getViewIdResourceName(),
                node.getContentDescription(),node.getClassName(),node.getText());
        if(label && node.isVisibleToUser()) {
            AccessibilityNodeInfo action=AccessibilityNodeInfo.obtain(node);
            // Compose Remote wraps the label in four non-clickable nodes.
            // Walk wrappers, but never promote the enclosing composer into a send action.
            for(int i=0;i<8 && action!=null && !action.equals(boundary);i++) {
                if(action.isClickable() || action.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)) {
                    if(seen.add(action))out.add(action); else action.recycle();
                    action=null; break;
                }
                AccessibilityNodeInfo parent=action.getParent(); action.recycle(); action=parent;
            }
            if(action!=null)action.recycle();
        }
        for(int i=0;i<node.getChildCount();i++) {
            AccessibilityNodeInfo child=node.getChild(i);
            if(child!=null)try { collect(child,boundary,pkg,out,seen,visited,depth+1); } finally { child.recycle(); }
        }
    }
    private void finish(String reason) {
        handler.removeCallbacks(expire);
        if(pending!=null) { Log.i(TAG,"send end id="+pending.id+" state="+pending.state()+" reason="+reason); pending.abort(); pending=null; }
        if(editorIdentity!=null) { editorIdentity.recycle(); editorIdentity=null; }
    }
    private void startKeepAliveForeground() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                        "doubao_voice_send",
                        "豆包语音发送",
                        NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                NotificationManager nm =
                        (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) {
                    nm.createNotificationChannel(ch);
                }
            }
            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(this, "doubao_voice_send");
            } else {
                builder = new Notification.Builder(this);
            }
            Notification n = builder
                    .setContentTitle("豆包语音发送助手运行中")
                    .setSmallIcon(android.R.drawable.ic_menu_send)
                    .setOngoing(true)
                    .build();
            startForeground(1, n);
            Log.i(TAG, "startForeground ok");
        } catch (Throwable t) {
            Log.w(TAG, "ERR startForeground: " + t.getClass().getSimpleName());
        }
    }

    private void unregisterSendReceiver() {
        try {
            if (!mReceiverRegistered || mReceiver == null) {
                return;
            }
            unregisterReceiver(mReceiver);
            mReceiverRegistered = false;
            Log.i(TAG, "a11y send receiver unregistered");
        } catch (Throwable t) {
            Log.w(TAG, "ERR unregister receiver: " + t.getClass().getSimpleName());
        }
    }

}
