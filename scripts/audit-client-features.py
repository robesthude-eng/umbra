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
check('Bidirectional media transition', 'media-source-transition' in viewer and 'sourceBounds' in viewer and 'closeViewer' in viewer)
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
motion=text(Path('android/app/src/main/java/com/umbra/app/ui/theme/Theme.kt'))
check('Motion tokens', 'data class UmbraMotionTokens' in motion and 'LocalUmbraMotion' in motion)
check('Velocity-aware dismiss', 'VelocityTracker' in viewer and 'motion.dismissVelocity' in viewer)
check('Spring gesture rollback', 'Animatable(start).animateTo' in viewer and 'motion.springDamping' in viewer)
check('Bounded media pan', 'limitX' in viewer and 'limitY' in viewer and 'coerceIn(-limitX, limitX)' in viewer)
check('Island state morph', 'AnimatedContent' in future and 'activity-island-morph' in future)
check('Root media overlay', 'AttachmentViewerOverlay' in chat and 'AttachmentViewerDialog' not in viewer and 'zIndex(50f)' in viewer)
check('Real source bounds', 'boundsInWindow' in chat and 'sourceInRoot' in chat and 'source.center.x' in viewer)
check('Predictive media back', 'PredictiveBackHandler' in viewer and 'event.progress' in viewer and 'enableOnBackInvokedCallback="true"' in manifest)
check('Zoom dismiss arbitration', 'contentZoomed' in viewer and '!contentZoomed' in viewer and 'onZoomChanged' in viewer)
check('Spatial corner morph', '24f * (1f - visualProgress)' in viewer)
check('Media loading skeleton', 'MediaLoadingSkeleton' in viewer and 'media-skeleton-alpha' in viewer)

check('Smooth media enter', 'transitionScale' in viewer and 'visualProgress' in viewer and 'LocalUmbraReducedMotion' in viewer)
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


# 0.16.7 merged network diagnostics
ws=text(Path('android/app/src/main/java/com/umbra/app/data/ws/WebSocketClient.kt'))
diag=text(Path('android/app/src/main/java/com/umbra/app/data/diag/DiagLog.kt'))
settings=text(Path('android/app/src/main/java/com/umbra/app/ui/SettingsTab.kt'))
isotime=text(Path('android/app/src/main/java/com/umbra/app/data/repo/IsoTime.kt'))
check('Tolerant server time', 'OffsetDateTime.parse' in isotime and 'Instant.parse' in isotime)
check('Independent outbox flush', 'Отправка не должна зависеть от приёма' in repo and 'flushOutbox()' in repo)
check('Privacy-safe diagnostics log', 'MAX_LINES' in diag and 'Содержимое сообщений' in diag)
check('Structured WebSocket diagnostics', 'data class Diagnostics' in ws and 'retryInMs' in ws and 'httpCode' in ws)
check('WebSocket failure logging', 'ws-failure' in ws and 'ws-closed' in ws and 'ws-connect' in ws)
check('REST sync stages', 'SyncStepException' in repo and 'список чатов' in repo and 'загрузка истории' in repo)
check('Precise sync errors', 'HTTP 401' in repo and 'ошибка DNS' in repo and 'TLS' in repo)
check('Last successful sync', 'lastSuccessfulSyncAtMillis' in repo and 'Последняя успешная синхронизация' in settings)
check('Settings realtime status', 'realtimeDiagnostics' in settings and 'Онлайн-канал' in settings)
check('Merged release version', 'versionCode = 33' in text(Path('android/app/build.gradle.kts')) and 'versionName = "0.16.10"' in text(Path('android/app/build.gradle.kts')))


# 0.16.8 Network Recovery
settings=text(Path('android/app/src/main/java/com/umbra/app/ui/SettingsTab.kt'))
check('Connectivity monitor', 'registerDefaultNetworkCallback' in repo and 'NetworkCapabilities' in repo)
check('Automatic route recovery', 'network-recovery' in repo and 'ws.reconnect(token)' in repo)
check('Jittered WS backoff', 'Random.nextLong' in ws and 'retryDelay' in ws)
check('Manual reconnect', 'suspend fun reconnectNow' in repo and 'Переподключиться' in settings)
check('Built-in network check', 'runNetworkCheck' in repo and 'Проверить сеть' in settings)
check('Network mode model', 'enum class NetworkMode' in repo and 'медленный REST' in settings and 'только локально' in settings)
check('Safe network report', 'NetworkCheckReport' in repo and 'asText()' in repo and 'Отправить отчёт' in settings)
check('Clear diagnostics', 'fun clear()' in diag and 'Очистить журнал' in settings)
check('Network recovery version', 'versionCode = 33' in text(Path('android/app/build.gradle.kts')) and 'versionName = "0.16.10"' in text(Path('android/app/build.gradle.kts')))


# 0.16.9 Alien Interface
prefs=text(Path('android/app/src/main/java/com/umbra/app/data/session/UiPreferences.kt'))
theme=text(Path('android/app/src/main/java/com/umbra/app/ui/theme/Theme.kt'))
root=text(Path('android/app/src/main/java/com/umbra/app/MainActivity.kt'))
main_shell=text(Path('android/app/src/main/java/com/umbra/app/ui/MainShell.kt'))
visuals=text(Path('android/app/src/main/java/com/umbra/app/ui/FutureVisuals.kt'))
check('Alien preference persisted', 'alienInterface' in prefs and 'alien_interface' in prefs and 'setAlienInterface' in prefs)
check('Alien theme provider', 'LocalUmbraAlienMode' in theme and 'alienIntensity = appearance.alienIntensity' in root)
check('Quantum alien backdrop', 'QuantumBackdrop' in visuals and 'alien-orbit' in visuals and 'Stroke' in visuals)
check('Holographic glass edge', 'BorderStroke' in visuals and 'auraSecondary.copy(alpha = 0.54f)' in visuals)
check('Orbital navigation', 'OrbitalNavIcon' in visuals and main_shell.count('OrbitalNavIcon') >= 2)
check('Alien settings control', 'Alien Interface' in settings and 'preferences::setAlienIntensity' in settings)
check('Alien live preview', 'QuantumBackdrop' in settings and 'AlienSignalMeter' in settings)
check('Alien chat palette', 'Color(0xFF4B38FF)' in theme and 'Color(0xFFE957FF)' in theme)
check('Reduced alien motion', 'selected && !reduced' in visuals)
check('Appearance repository wiring', 'AppearanceSettings(container.uiPreferences, repo)' in settings and 'preferences: UiPreferences, repo: ChatRepository' in settings)
check('Alien release version', 'versionCode = 33' in text(Path('android/app/build.gradle.kts')) and 'versionName = "0.16.10"' in text(Path('android/app/build.gradle.kts')))

# 0.16.10 Alien Interface II
alien=text(Path('android/app/src/main/java/com/umbra/app/ui/AlienVisuals.kt'))
chat_kit=text(Path('android/app/src/main/java/com/umbra/app/ui/ChatKit.kt'))
components=text(Path('android/app/src/main/java/com/umbra/app/ui/UmbraComponents.kt'))
command=text(Path('android/app/src/main/java/com/umbra/app/ui/CommandCenter.kt'))
avatar=text(Path('android/app/src/main/java/com/umbra/app/ui/Avatar.kt'))
check('Three alien intensities', 'enum class AlienIntensity { OFF, CALM, FULL }' in prefs and 'setAlienIntensity' in prefs and 'AlienIntensity.CALM' in theme)
check('Alien token table', 'data class AlienTokens(' in theme and 'LocalUmbraAlienTokens' in theme and 'fun alienTokens(' in theme and 'starCount' in theme)
check('Alien visual library', 'fun QuantumBackdrop' in alien and 'drawAlienNebula' in alien and 'drawAlienRings' in alien and 'drawAlienGrid' in alien and 'drawAlienStarfield' in alien and 'drawAlienComet' in alien)
check('Alien modifiers', 'fun Modifier.holoEdge' in alien and 'fun Modifier.alienGlow' in alien and 'fun Modifier.holoScanlines' in alien and 'fun Modifier.alienTopEdge' in alien and 'fun Modifier.alienOrbitRing' in alien)
check('Alien HUD widgets', 'fun AlienHudLabel' in alien and 'fun AlienAwareLabel' in alien and 'fun AlienSignalMeter' in alien and 'fun AlienDivider' in alien and 'fun AlienActivationOverlay' in alien)
check('Deterministic starfield', 'fun alienStars(' in alien and 'fun rememberAlienStars(' in alien)
check('Alien motion freeze', 'if (LocalUmbraReducedMotion.current) return frozen' in alien)
check('Alien activation overlay wired', 'AlienActivationOverlay' in main)
check('Alien chat surfaces', 'drawAlienStarfield' in chat_kit and 'holoScanlines' in chat and 'AlienSignalMeter' in chat and 'holoEdge' in chat)
check('Alien navigation chrome', 'alienTopEdge' in main_shell and 'AlienAwareLabel' in main_shell and 'AlienSignalMeter' in main_shell)
check('Alien shared components', 'alienOrbitRing' in components and 'holoEdge' in components and 'AlienHudLabel' in components and 'alienOrbitRing' in avatar)
check('Alien command center', 'AlienHudLabel' in command and 'AlienDivider' in command and 'holoEdge' in command)
check('Alien intensity settings', 'Спокойный' in settings and 'Полный' in settings and 'CompositionLocalProvider' in settings)
check('Alien docs updated', 'CALM' in text(Path('ALIEN_INTERFACE.md')) and '0.16.10' in text(Path('CHANGES.md')))

failed=[name for name,ok,_ in checks if not ok]
for name,ok,detail in checks:
    print(('PASS ' if ok else 'FAIL ')+name+(f' — {detail}' if detail else ''))
print(f'\n{sum(ok for _,ok,_ in checks)}/{len(checks)} checks passed')
raise SystemExit(1 if failed else 0)
