#!/usr/bin/env python3
"""Build original synthetic DEX fixtures; never modifies or redistributes Doubao APKs.
Usage: python3 scripts/build-semantic-fixtures.py OUTPUT_DIR /path/to/d8
"""
import pathlib, subprocess, sys
out=pathlib.Path(sys.argv[1]).resolve(); d8=sys.argv[2]
for variant in ('alpha','renamed','ambiguous','missing'):
    root=out/variant; src=root/'src'; classes=root/'classes'; dex=root/'dex'
    src.mkdir(parents=True,exist_ok=True); classes.mkdir(exist_ok=True); dex.mkdir(exist_ok=True)
    suffix='Again' if variant=='renamed' else ''
    def n(name): return name+suffix
    def write(pkg,name,body):
        file=src/pathlib.Path(*pkg.split('.'))/(name+'.java'); file.parent.mkdir(parents=True,exist_ok=True)
        file.write_text('package '+pkg+';\n'+body)
    speech='com.bytedance.android.input.speech'; keyboard='com.bytedance.android.input.keyboard'
    write(speech,'Result','public class Result {public boolean '+n('done')+'(){return true;}}')
    write(speech,'Listener','public interface Listener {void '+n('event')+'(Result result);}')
    write(speech,'Process','public class Process {private Listener listener; public void '+n('listen')+'(Listener l){listener=l;}}')
    active='public boolean '+n('active')+'(){System.out.print("isAsrSpeechingStatus");return state==State.KTryStart||state==State.KStart;}'
    if variant=='ambiguous': active+=active.replace(n('active'),n('duplicate'))
    if variant=='missing': active=active.replace('isAsrSpeechingStatus','removed feature')
    write(speech,'AsrManager',f'''public class AsrManager {{
        public static AsrManager {n('instance')}=new AsrManager();
        static Process {n('process')}=new Process();
        enum State {{KTryStart,KStart,Error}}; State state;
        {active}
        public void {n('vad')}(){{System.out.print("forceVad");}}
        public void {n('undo')}(){{System.out.print("doUndo mHavePreEdit");}}
        public void {n('stop')}(){{System.out.print("LongPressStop");}}
        public void {n('decoy')}(){{System.out.print("LongPressStop");}}
        public void {n('send')}(int action,long time,boolean enable){{
            System.out.print("DoAsrSend currentAllAsrBack"); AsrContext.{n("instance")}.{n("complete")}(); {n('process')}.{n('listen')}(new Callback());
        }}
        public void {n('finalSend')}(int action,long time){{
            System.out.print("DoAsrSend sendFinish costTime"); System.out.print("asr_real_do_send");
            new EditorConnection().performEditorAction(action);
        }}
        static class Callback implements Listener {{public void {n('event')}(Result result){{
            System.out.print("asrCallBackInfo");if(result.{n('done')}())System.out.print("complete");
        }}}}
    }}''')
    write(speech,'EditorConnection','public class EditorConnection {public boolean performEditorAction(int action){return true;}}')
    write(speech,'AsrContext','public class AsrContext {static AsrContext '+n('instance')+'=new AsrContext(); public boolean '+n('complete')+'(){System.out.print("allAsrBack, mAsrContentList isEmpty");return true;} public void '+n('done')+'(int phase,boolean done){}}')
    write(keyboard,'InputView','public class InputView {public void '+n('close')+'(boolean v){} public static InputView '+n('get')+'(){return new InputView();}}')
    write(speech+'.view','AsrLongPressView',f'''import {speech}.AsrManager; import {keyboard}.InputView;
    public class AsrLongPressView {{public void release(){{
        InputView.{n('get')}().{n('close')}(false); AsrManager.{n('instance')}.{n('stop')}();System.out.print("loosen");
    }}}}''')
    write(speech+'.view','Editor',f'''public class Editor {{static Editor {n('instance')}=new Editor(); int value;
        public void {n('set')}(int a){{System.out.print("setEnterActionType");value=a;}}
        public int {n('get')}(){{return value;}}
    }}''')
    write('com.bytedance.android.input.common','VibrationController','public class VibrationController {public enum VibrationType {SPEECH_START,CONFIRM}}')
    write(keyboard,'UserInteractiveManagerNext',f'''import com.bytedance.android.input.common.VibrationController;
    public class UserInteractiveManagerNext {{static UserInteractiveManagerNext {n('instance')}=new UserInteractiveManagerNext();
        public enum KeySound {{KEYBOARD}} public enum KeyVibrate {{STANDARD}}
        public void {n('feedback')}(KeySound sound,KeyVibrate vibrate,VibrationController.VibrationType type,boolean flag){{}}
    }}''')
    subprocess.run(['javac','--release','8','-d',str(classes),*[str(p) for p in src.rglob('*.java')]],check=True)
    jar=root/'fixture.jar'; subprocess.run(['jar','cf',str(jar),'-C',str(classes),'.'],check=True)
    subprocess.run([d8,'--min-api','26','--output',str(dex),str(jar)],check=True)
    print(variant,dex/'classes.dex')
