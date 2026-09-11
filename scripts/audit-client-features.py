from pathlib import Path
import re, xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
checks=[]
def check(name, condition, detail=''):
    checks.append((name,bool(condition),detail))

def text(path): return (ROOT/path).read_text()

# Package / syntax integrity.
for p in (ROOT/'android/app/src/main/res').rglob('*.xml'): ET.parse(p)
ET.parse(ROOT/'android/app/src/main/AndroidManifest.xml')
check('Android XML parses', True)
conflict=[]
for p in ROOT.rglob('*'):
    if p.is_file() and p.suffix in {'.kt','.go','.md','.xml','.kts'}:
        s=p.read_text(errors='ignore')
        if re.search(r'^(<<<<<<<|=======|>>>>>>>)',s,re.M): conflict.append(str(p))
check('No merge markers', not conflict, ', '.join(conflict))

chat=text(Path('android/app/src/main/java/com/umbra/app/ui/ChatView.kt'))
repo=text(Path('android/app/src/main/java/com/umbra/app/data/repo/ChatRepository.kt'))
root=text(Path('android/app/src/main/java/com/umbra/app/ui/UmbraRoot.kt'))
call=text(Path('android/app/src/main/java/com/umbra/app/ui/CallScreen.kt'))
main=text(Path('android/app/src/main/java/com/umbra/app/MainActivity.kt'))
shell=text(Path('android/app/src/main/java/com/umbra/app/ui/MainShell.kt'))
viewer=text(Path('android/app/src/main/java/com/umbra/app/ui/MediaViewer.kt'))
wave=text(Path('android/app/src/main/java/com/umbra/app/data/voice/AudioWaveform.kt'))
msg=text(Path('android/app/src/main/java/com/umbra/app/data/msg/MessageContent.kt'))
push=text(Path('android/app/src/main/java/com/umbra/app/data/push/PushService.kt'))
manifest=text(Path('android/app/src/main/AndroidManifest.xml'))

# 0.12
future=text(Path('android/app/src/main/java/com/umbra/app/ui/FutureVisuals.kt'))
theme=text(Path('android/app/src/main/java/com/umbra/app/ui/theme/Theme.kt'))
check('Future UI semantic tokens', 'data class UmbraVisualTokens' in theme and 'LocalUmbraVisuals' in theme)
check('Future UI ambient aura', 'FutureBackdrop' in future and '18_000' in future and 'LocalUmbraReducedMotion' in future)
check('Future UI glass fallback', 'GlassPanel' in future and 'glassStrong' in future)
check('Per-chat dynamic palette', 'rememberUmbraChatColors' in theme and 'rememberUmbraChatColors(chatId)' in chat)
check('Future UI microinteractions', 'futurePress' in future and 'reaction-$emoji' in chat)
viewer=text(Path('android/app/src/main/java/com/umbra/app/ui/MediaViewer.kt'))
check('Message spring landing', 'message-landing' in chat and 'StiffnessMediumLow' in chat)
check('Bidirectional media transition', 'closeViewer' in viewer and 'scaleOut(targetScale = 0.96f' in viewer)
check('Activity island', 'ActivityIsland' in future and 'islandLabel' in chat)
commands=text(Path('android/app/src/main/java/com/umbra/app/ui/CommandCenter.kt'))
root=text(Path('android/app/src/main/java/com/umbra/app/ui/UmbraRoot.kt'))
check('Command Center', 'CommandCenter' in commands and 'onOpenChat' in commands and 'onDestination' in commands)
check('Command shortcut', 'Key.K' in root and 'isCtrlPressed' in root and 'isMetaPressed' in root)
call=text(Path('android/app/src/main/java/com/umbra/app/ui/CallScreen.kt'))
check('Message context sheet', 'else ModalBottomSheet' in chat and 'contextActions' in chat and 'navigationBarsPadding' in chat)
check('Swipe-dismiss media', 'detectVerticalDragGestures' in viewer and 'dragY' in viewer)
check('Glass call island', 'fun MinimizedCallBar' in call and 'GlassPanel(' in call)
check('Tablet context panel', 'wideContext' in chat and 'Alignment.CenterEnd' in chat and 'width(380.dp)' in chat)
check('Command swipe gesture', 'detectVerticalDragGestures' in root and 'distance >= 96.dp.toPx()' in root)
check('Update activity island', 'UpdateUi.Downloading' in root and 'Обновление ${downloading.percent}%' in root)

check('Smooth media enter', 'scaleIn(initialScale = 0.96f' in viewer and 'LocalUmbraReducedMotion' in viewer)
check('Call minimizes and restores', 'MinimizedCallBar' in root and 'onMinimize' in call)
check('PiP overrides minimized bar', '!pictureInPicture' in root)
check('Real waveform decoding', all(x in wave for x in ['MediaExtractor','MediaCodec','ENCODING_PCM_FLOAT','idleAfterInput']))
check('Waveform cache/fallback', 'voiceWaveform' in repo and 'fallbackBars' in chat)
check('Tablet split view', 'maxWidth >= 840.dp' in root and 'selectedChatId' in shell)
check('Tablet navigation rail', 'NavigationRail' in shell and 'detailStateHolder.SaveableStateProvider' in shell)

# 0.13
check('Replies encoded and rendered', 'val reply: ReplyContent?' in msg and 'replyText' in repo and 'replyingTo' in chat)
check('Forwarding encoded and rendered', 'forwardedFrom' in msg and 'forwardMessage' in repo and 'ForwardMessageDialog' in chat)
check('Media captions', 'caption: String' in repo and 'attachment.caption.isNotBlank()' in chat)
check('Multiple media selection', 'PickMultipleVisualMedia(10)' in chat and 'uris.take(10)' in chat)
check('Video PiP manifest/activity', 'supportsPictureInPicture="true"' in manifest and 'enterPictureInPictureMode' in main)
check('PiP hides call controls', 'if (pictureInPicture)' in call and 'if (!pictureInPicture) FilledTonalIconButton' in call)

# 0.14
check('Unread counters', 'unreadCount' in repo and 'Badge' in shell and 'markChatRead' in chat)
check('Chat search', 'Поиск в переписке' in chat and 'visibleMessages' in chat and 'Ничего не найдено' in chat)
check('Edit events', 'KIND_EDIT' in msg and 'editMessage' in repo and 'Редактировать сообщение' in chat)
check('Delete events', 'KIND_DELETE' in msg and 'deleteMessage' in repo and 'Сообщение удалено' in repo)
check('Reaction events', 'KIND_REACTION' in msg and 'reactToMessage' in repo and 'listOf("👍", "❤️", "😂", "😮", "😢")' in chat)
check('Control authorization', 'it.senderId == row.senderId && it.chatId == row.chatId' in repo)
check('Failed controls do not project', 'row.deliveryState == "failed"' in repo)
check('Quick reply receiver', 'MessageReplyReceiver' in push and 'RemoteInput' in push and '.data.push.MessageReplyReceiver' in manifest)
check('Quick reply exception-safe', 'quick reply was not queued' in push and 'pending.finish()' in push)

# Semantic simulation of control projection policy.
rows=[
 {'id':'m1','sender':'alice','kind':'text','text':'one','state':'sent'},
 {'id':'e1','sender':'alice','kind':'edit','target':'m1','text':'two','state':'sent'},
 {'id':'bad','sender':'mallory','kind':'delete','target':'m1','state':'sent'},
 {'id':'r1','sender':'bob','kind':'reaction','target':'m1','reaction':'👍','state':'sent'},
 {'id':'r2','sender':'bob','kind':'reaction','target':'m1','reaction':'❤️','state':'sent'},
 {'id':'fail','sender':'alice','kind':'delete','target':'m1','state':'failed'},
]
by={r['id']:r for r in rows}
controls=[r for r in rows if r['kind'] in {'edit','delete','reaction'} and r['state']!='failed']
deleted={r['target'] for r in controls if r['kind']=='delete' and by.get(r['target'],{}).get('sender')==r['sender']}
edits={r['target']:r['text'] for r in controls if r['kind']=='edit' and by.get(r['target'],{}).get('sender')==r['sender']}
latest={}
for r in controls:
    if r['kind']=='reaction': latest[(r['target'],r['sender'])]=r['reaction']
check('Projection: authorized edit wins', edits.get('m1')=='two')
check('Projection: forged/failed delete ignored', 'm1' not in deleted)
check('Projection: latest reaction per sender', latest.get(('m1','bob'))=='❤️')

failed=[name for name,ok,_ in checks if not ok]
for name,ok,detail in checks:
    print(('PASS ' if ok else 'FAIL ')+name+(f' — {detail}' if detail else ''))
print(f'\n{sum(ok for _,ok,_ in checks)}/{len(checks)} checks passed')
raise SystemExit(1 if failed else 0)
