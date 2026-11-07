# SulfurFrame — 임베딩 PoC

게임 창(다른 프로세스)을 IDE 패널 안에 넣는 방식이 실제로 버티는지 검증하는 PoC입니다.
IntelliJ 대신 Swing 창을 호스트로 쓰고, 마인크래프트 대신 같은 스택(LWJGL 3.4.3 + SDL3 + OpenGL)의
테스트 타깃을 씁니다.

| 파일 | 역할 |
|---|---|
| `Win32.java` | FFM(JDK 22+)으로 만든 user32 바인딩. JNA/JNI 없음 |
| `EmbedHost.java` | 호스트 = IntelliJ ToolWindow 대역. 왼쪽 에디터, 오른쪽 AWT `Canvas`(게임 창의 부모) |
| `SdlTarget.java` | 마인크래프트 26.x 대역. SDL3 창 + GL 렌더 루프 + stdin 명령(`stall <ms>`) |
| `SelfTest.java` | 자동 검증. `--robot`이면 실제 마우스/키보드로 포커스·멈춤 시나리오까지 확인 |

## 실행 (JDK 25, 빌드 불필요)

LWJGL jar는 로컬 Gradle 캐시(`~/.gradle/caches/.../org.lwjgl/*/3.4.3`)에서 찾습니다.

```bash
cd poc/embed
java --enable-native-access=ALL-UNNAMED EmbedHost.java                    # 수동
java --enable-native-access=ALL-UNNAMED EmbedHost.java --selftest         # 자동 (입력 장치 안 건드림)
java --enable-native-access=ALL-UNNAMED EmbedHost.java --selftest --robot # 자동 + 마우스/키보드 약 30초
java --enable-native-access=ALL-UNNAMED EmbedHost.java --attach <PID>     # 이미 떠 있는 창(예: 마인크래프트) 임베드
java --enable-native-access=ALL-UNNAMED EmbedHost.java --selftest --robot --attach <PID>   # 실제 게임에 자동 검증
java --enable-native-access=ALL-UNNAMED EmbedHost.java --attach <PID> --snapshot out.png   # 임베드 상태 스크린샷
./run-vanilla-mc.sh opengl   # 또는 vulkan: Loom 캐시의 바닐라 26.3을 별도 게임 폴더로 실행
```

## 결과 (2026-11-06, Windows 11, AMD Radeon 780M, GL 4.6)

| 항목 | REPARENT (`SetParent`+`WS_CHILD`) | OVERLAY (owned popup이 위치 추적) |
|---|---|---|
| 구조 (부모/소유자, 스타일) | PASS | PASS |
| 뷰포트 리사이즈·이동 추종 | PASS | PASS |
| 게임 멈춤 중 **동기** `SetWindowPos` | IDE 스레드 **약 2.2초 정지** | IDE 스레드 **약 2.2초 정지** |
| 게임 멈춤 중 **비동기** `SetWindowPos` | 0 ms | 0 ms |
| 게임 클릭 → 키가 게임으로 | PASS (*1) | PASS |
| 에디터 클릭 → 키가 에디터로 | PASS (*2) | PASS |
| **게임이 멈췄을 때 에디터 클릭+타이핑** | **PASS, 약 170 ms** (*3) | **FAIL, 3~3.9초 지연** |

REPARENT 결과는 두 번 연속 실행에서 동일했습니다.

### 알아낸 것

1. **창 크기·위치 변경은 항상 `SWP_ASYNCWINDOWPOS`로.** 다른 프로세스의 창에 동기 호출을 하면,
   그 창이 메시지를 처리할 때까지 IDE UI 스레드가 그대로 멈춥니다. 수치로 확인했습니다.
2. **(*1) 자식 창은 클릭만으로 키보드 포커스를 가져가지 않습니다.** 처음에는 게임 쪽 훅으로 해결했고,
   지금은 **호스트만으로 해결합니다** (아래 "모드 없는 click-to-focus" 참고). `SdlTarget`의 훅은 기본으로 꺼져 있습니다
   (`SF_CLICK_TO_FOCUS=1`로 켜서 비교 가능).
3. **(*2) 반대로, Swing 컴포넌트를 클릭해도 네이티브 포커스가 돌아오지 않습니다.** 호스트에서
   뷰포트 바깥 클릭을 감지해 `requestFocus(Cause.MOUSE_EVENT)`로 네이티브 포커스를 요청해야 합니다.
4. **(*3) `SetParent`는 두 프로세스의 입력 큐를 묶습니다.** 게임이 포커스를 가진 채 멈추면, 대책이 없을 땐
   에디터 입력이 약 6초 막혔습니다. 대책: 와치독이 `WM_NULL` 왕복(`SendMessageTimeout`, 200 ms)을 두 번 연속 놓치면
   `AttachThreadInput(false)`로 큐를 떼고, 게임이 회복하면 다시 붙입니다. 한 번만 놓친 걸로 판단하면
   포커스를 넘기는 순간의 짧은 대기를 멈춤으로 오인해서 디바운스가 필요했습니다.
5. **오버레이 방식은 멈춤에 더 약합니다.** 소유된(owned) 창이 멈추면 소유자 창의 활성화가 그 창을 기다리느라
   에디터 입력이 게임이 회복할 때까지 막혔습니다. 입력 큐를 묶지 않으니 더 안전할 거라던 처음 예상과 반대입니다.
   → **REPARENT 방식으로 결정.**

## 실제 마인크래프트 26.3 결과 (2026-11-07, 바닐라, NVIDIA 617.42)

`./run-vanilla-mc.sh opengl|vulkan`으로 Loom 캐시의 바닐라 26.3을 별도 게임 폴더에서 실행한 뒤
`--selftest --robot --attach <PID>`로 확인했습니다. 마인크래프트에는 stall 명령이 없어서, 멈춤은
**게임 창 스레드(렌더 스레드)를 `SuspendThread`로 정지**시켜 재현했습니다.
(`NtSuspendProcess`는 창이 임베드된 상태에서 status 1을 반환하며 실패했습니다.)

| 항목 | GL (REPARENT / OVERLAY) | Vulkan (REPARENT / OVERLAY) |
|---|---|---|
| 구조, 리사이즈·이동 추종 | PASS / PASS | PASS / PASS |
| 멈춤 중 동기 `SetWindowPos` | 약 2.2초 정지 / 약 2.2초 정지 | 약 2.2초 정지 / 약 2.2초 정지 |
| 멈춤 중 비동기 `SetWindowPos` | 0 ms / 0 ms | 0 ms / 0 ms |
| 게임 클릭 → 키가 게임으로 (호스트 처리 전) | FAIL / PASS | FAIL / PASS |
| 게임 클릭 → 키가 게임으로 (**호스트 click-to-focus 후**) | **PASS** / PASS | **PASS** / PASS |
| 에디터 클릭 → 키가 에디터로 | PASS / PASS | PASS / PASS |
| 게임이 멈췄을 때 에디터 타이핑 | **PASS 166~170 ms** / FAIL 3.3초 | **PASS 165~170 ms** / FAIL 3.2초 |

- **GL과 Vulkan 결과가 완전히 같습니다.** HWND 수준 임베딩은 렌더러와 무관하다는 게 확인됐습니다.
- **Vulkan 화면이 자식 창 안에서 정상적으로 그려집니다** (`snapshot-vulkan.png`). 리사이즈 순간에
  `Couldn't acquire next surface texture` 경고가 두 번 났지만, 스왑체인을 다시 만들고 계속 동작했습니다.
- 호스트 click-to-focus를 넣은 뒤로는 REPARENT 항목이 GL·Vulkan 모두 통과합니다. **단, 이 검사는 Win32 포커스만 본 것입니다. SDL이 키보드 포커스를 주지 않아 게임이 실제로는 반응하지 않습니다. 아래 "⚠ 중요" 절을 보세요.**

### 모드 없는 click-to-focus (호스트에서 처리)

1. 게임 창의 `WS_EX_NOPARENTNOTIFY`를 지워서, 게임 창 안의 마우스 다운이 부모(캔버스)에 `WM_PARENTNOTIFY`로 오게 합니다.
2. 캔버스의 `WNDPROC`를 FFM 업콜로 서브클래싱합니다(`installClickToFocus`). 클릭 알림을 받으면 바로 처리하지 않고
   `PostMessage`로 미룹니다. 같은 클릭에 대한 AWT 자체 처리(활성화, 포커스 프록시)가 먼저 끝나야 하기 때문입니다.
3. **`SetFocus(game)`은 AWT 툴킷 스레드에서 부르면 안 됩니다.** 성공을 반환하는데 AWT가 즉시 포커스를 되가져갑니다.
   대신 **AWT와 무관한 도우미 스레드**를 `AttachThreadInput`으로 게임 입력 큐에 잠깐 붙여서 거기서 `SetFocus`합니다.
   게임이 자기 스레드에서 포커스를 가져갈 때 AWT가 가만히 있던 것과 같은 조건입니다.
4. 게임이 멈춘 동안에는 하지 않습니다(`SetFocus`가 멈춘 게임을 기다리게 되므로). 와치독 상태와 100 ms `WM_NULL` 확인으로 거릅니다.

### 실행 환경 메모

- 일부 실행에서 마인크래프트가 리소스 로드 직후 exit 139로 종료됐습니다(hs_err 없음). 호스트를 붙이기 전에도
  일어났으므로 PoC와는 무관해 보입니다. 원인은 조사하지 않았습니다.
- 시작 도중 종료되면 다음 시작에서 마인크래프트가 그래픽 API를 한 번 "default"(GL)로 되돌립니다. 테스트 전에 로그의
  `Using graphics backend ...` 줄을 꼭 확인하세요. 손으로 쓴 `options.txt`(`version:` 줄 없음)도 같은 이유로 GL로 되돌아갑니다.

## 마인크래프트 26.3 스택 (로컬 Loom 캐시의 `mojang_minecraft_info.json`으로 확인)

- Java 25, LWJGL 3.4.3
- **창 라이브러리는 GLFW가 아니라 SDL3**(`lwjgl-sdl`). 명세의 `glfwGetWin32Window`는 26.x에서 쓸 수 없음
- **렌더러는 OpenGL + Vulkan 둘 다 포함**(`lwjgl-opengl`, `lwjgl-vulkan`, `shaderc`, `spvc`, `vma`)

그래서 호스트는 **PID로 최상위 창을 찾습니다**(`Win32.findMainWindowOfProcess`). 이 방식은 GLFW(1.20.5~1.21.x)와
SDL3(26.x), GL과 Vulkan 어느 조합에서도 똑같이 동작하고, 창을 붙이는 것 자체에는 모드가 필요 없습니다. 다만 키보드 입력은 게임 쪽 패치가 필요합니다("⚠ 중요" 절).
모드는 스로틀링(FPS 제한, 일시정지)처럼 게임 안에서만 할 수 있는 일만 맡으면 됩니다.

## 아직 검증하지 않은 것

- 월드 안에서 마우스 잠금(relative mode) 상태에서 ESC로 풀고 에디터로 돌아가기
- DPI가 다른 모니터 사이로 IDE를 옮길 때
- IntelliJ(JBR) 안에서 `SunAwtCanvas` HWND 찾기, 플러그인 환경의 FFM/JNA 선택

## 스로틀러 프로브 1차 결과 (2026-11-07, 바닐라 26.3, GL, 월드 안, G1 기본 설정, -Xmx2G)

`java EmbedHost.java --throttle-probe --attach <PID>` (`ThrottleProbe.java`). 모두 **모드 없이 호스트에서** 한 조작입니다.

| 상태 | CPU (1코어 기준) | 워킹셋 | 힙 사용/확보 |
|---|---|---|---|
| 일시정지 메뉴 (포커스 잃음) | 18% | 1640 MB | 803 / 948 MB |
| 숨김 (`ShowWindowAsync` 최소화 → 바닐라 10 FPS 제한) | 9% | 1641 MB | 837 / 948 MB |
| + `jcmd VM.set_flag MaxHeapFreeRatio 10` + `GC.run` | - | 1170 MB | 442 / **485 MB** |
| + `EmptyWorkingSet` | 7.6% | **65 MB** | 447 / 485 MB |
| 복원 4초 후 (비율 70/40으로 되돌림) | 18.5% | 613 MB | 471 / 729 MB |

- 복원 후 게임이 응답하기까지 221 ms.
- `GC.run`만 요청하면 힙이 줄지 않습니다(G1 기본 MaxHeapFreeRatio 70%). 실행 중 바꿀 수 있는 플래그라 호스트가 조였다가 되돌리면 됩니다.
- **주의: "PLAYING" 측정은 무효.** 스크린샷을 보니 ESC 이후에도 일시정지 메뉴가 그대로 열려 있었습니다. 실제 플레이 중 기준값은 아직 없습니다.
- 마인크래프트가 리소스 로드 직후 `0xC0000005`로 죽는 일이 잦았습니다(hs_err 없음, OpenAL은 26.3에서 필수라 끌 수 없음). 원인 미확인.

## ⚠ 중요: REPARENT 상태에서 마인크래프트가 키보드 포커스를 받지 못함 (2026-11-07)

앞선 "게임 클릭 → 키가 게임으로 PASS"는 **Win32 포커스(GetGUIThreadInfo)만 확인한 것**이었고, 게임이 실제로 반응하는지는 보지 않았습니다.
SDL 이벤트를 직접 찍어 보니(`SdlTarget` 로그):

| 모드 | 키 이벤트 windowID | SDL 키보드 포커스 | 마우스 이벤트 windowID |
|---|---|---|---|
| REPARENT (자식 창) | **0** | **없음** | 정상 |
| OVERLAY (최상위 창) | 정상 | 있음 | 정상 |

- SDL3는 Windows에서 포커스를 "포그라운드 창 == 자기 창"으로 판단하는 것으로 보입니다. 자식 창은 포그라운드가 될 수 없으니 SDL 키보드 포커스가 끝내 안 잡힙니다.
- 마인크래프트(`SDLEventHandler.getWindowHandle`)는 windowID가 자기 창이 아닌 입력을 버리고, 창이 포커스를 못 받았다고 보므로
  `pauseOnLostFocus`가 매 틱 일시정지 메뉴를 다시 엽니다. **임베드된 바닐라 마인크래프트는 사실상 조작할 수 없습니다.**
- 그래서 스로틀 프로브의 "PLAYING" 값은 여전히 일시정지 메뉴 상태입니다(저장 로그로 확인: 메뉴가 닫혔다가 다시 열린 흔적 없음).

해결 후보:
1. **아주 작은 게임 쪽 패치**: 임베드됐을 때 마인크래프트가 자기 창을 포커스됨으로 보고, windowID 0 입력을 자기 것으로 받게 합니다. 모드(Mixin) 또는
   호스트가 실행 인자로 붙이는 **javaagent**로 가능하고, javaagent면 모드 로더와 상관없이 바닐라에도 붙습니다.
2. **입력할 때만 OVERLAY**: 평소엔 REPARENT, 게임을 클릭하면 최상위 창으로 전환. 멈춤 내성이 떨어집니다(앞의 결과).
3. SDL 쪽 옵션(힌트/환경변수)이 있는지 조사. 아직 찾지 못함.

## 스로틀러 (`Throttler.java`) 2차 결과

포커스·가시성에 따라 ACTIVE / CODING / HIDDEN 상태를 전환합니다. HIDDEN에 들어가면 게임 창을 최소화하고(`ShowWindowAsync`, 바닐라 10 FPS),
5초 뒤에도 숨겨져 있으면 G1 반환 비율을 조이고 GC → `EmptyWorkingSet`을 실행합니다. 다시 보이면 비율(70/40)과 창을 복원합니다. 모드는 필요 없습니다.

| 상태 (바닐라 26.3, GL, 월드 안) | CPU (1코어 기준) | 워킹셋 | 힙 사용/확보 |
|---|---|---|---|
| 일시정지 메뉴, 게임 포커스 시도 | 64~68% | 700~800 MB | ~450 / 632 MB |
| CODING (일시정지 메뉴) | 65~72% | 700~820 MB | ~500 / 632 MB |
| HIDDEN (최소화 직후) | 6~9% | 변화 없음 | 변화 없음 |
| HIDDEN + 회수 | 17% (회수 직후) | **430~490 MB** | 380 / **420 MB** |
| 복원 4초 후 | 20~65% | 600~670 MB | ~480 / 630 MB |

- 복원 후 응답까지 59~104 ms. GC 플래그는 복원 후 70/40으로 돌아온 것을 `jcmd`로 확인했습니다.
- 일시정지 메뉴만 떠 있어도 CPU를 한 코어의 2/3쯤 씁니다(메뉴 뒤 월드를 계속 그리므로). CODING 상태에서 FPS를 낮추는 건
  바닐라만으로는 안 되고, 위의 게임 쪽 패치가 있으면 같이 할 수 있습니다.
- 마인크래프트가 시작 중 `0xC0000005`로 죽는 현상은 이번에도 3번 중 2번 일어났습니다(원인 미확인).

## 해결: SulfurFrame javaagent (`poc/agent`, 2026-11-07)

`./poc/agent/build.sh`로 `sf-agent.jar`를 만듭니다. JDK 25만 있으면 되고, JDK의 Class-File API를 써서 ASM이나 Gradle은 필요 없습니다.
마인크래프트 메서드 세 개에 대해 "리턴 직전에 훅 호출 하나 끼우기"만 합니다.

| 패치 위치 | 훅 | 하는 일 |
|---|---|---|
| `Window.isFocused()` (`Minecraft.isWindowActive()`도 사용) | `SfHooks.focused` | Win32 `GetFocus()`가 이 프로세스의 창이면 포커스로 봄. SetParent로 입력 큐가 묶여 있어서 에디터에 포커스가 있으면 "남의 창"으로 보임 |
| `SDLEventHandler.getWindowHandle(event)` | `SfHooks.windowHandle` | SDL이 windowID 0으로 보낸 키 이벤트를 게임 창의 것으로 받음 |
| `FramerateLimitTracker.getFramerateLimit()` | `SfHooks.fpsLimit` | 포커스가 없으면 FPS 상한 적용 (`codingFps=15`) |

- **붙이는 방법:** 호스트가 실행 중인 게임에 Attach API로 붙이고(`EmbedHost --agent poc/agent/sf-agent.jar ...`) 이미 로드된 클래스를 재변환합니다.
  **게임 재시작이 필요 없습니다.** 시작할 때 붙이려면 `SF_AGENT=poc/agent/sf-agent.jar ./run-vanilla-mc.sh opengl`. 다만 시작 시 붙이는 경로는 아직 실행해 보지 않았습니다.
- 두 번 붙여도 설정만 갱신하고 패치를 중복으로 넣지 않습니다.
- 모드 로더(Fabric 등)와 상관없이 바닐라 클래스 이름 그대로 패치합니다. Fabric 환경에서는 검증하지 않았습니다.

### 결과 (바닐라 26.3, GL, 월드 안, 실행 중에 에이전트 attach)

- **실제 플레이 가능:** "Back to Game" 클릭 → 플레이 화면 복귀 확인(스크린샷). **E 키 → 인벤토리 열림**(키보드 입력 확인).
  에디터를 클릭하면 일시정지 메뉴가 뜨고 저장 로그가 남습니다.
- 에이전트를 붙인 상태에서 임베딩 셀프테스트의 REPARENT 항목은 전부 통과합니다(멈췄을 때 에디터 입력 169 ms).

| 상태 | CPU (1코어 기준) | 워킹셋 | 힙 사용/확보 |
|---|---|---|---|
| **PLAYING (실제 플레이)** | **92~111%** | 880~1380 MB | ~530 / 632~679 MB |
| **CODING (일시정지 + 15 FPS 상한)** | **9~10%** | 변화 없음 | 변화 없음 |
| HIDDEN (최소화) | 5~12% | 변화 없음 | 변화 없음 |
| HIDDEN + 회수 | - | **500 MB** | 384 / **420 MB** |
| 복원 직후 | 11~22% | 530~570 MB | ~380 / 422~632 MB |

에이전트가 없을 때 CODING은 65~72%였습니다. FPS 상한 덕분에 코딩 중 게임 CPU가 약 1/7로 줄었습니다.

## 전 버전 대응: 에이전트 v2 (LWJGL 수준 패치, Java 8)

앞의 에이전트 v1은 마인크래프트 클래스(`Window` 등)를 이름으로 패치했습니다. 26.x는 난독화가 없어서 되지만, 하위 버전은
환경마다(Mojmap / Yarn / intermediary) 이름이 다르고 구조도 바뀝니다. 그래서 v2는 **리매핑되지 않고 이름이 안정적인 LWJGL 메서드만**
패치합니다. 한 jar로 모든 버전과 로더를 커버하는 것이 목표입니다.

- **Java 8 바이트코드** (major 52). Java 8~25 어디서나 로드됩니다. 게임 쪽에서는 FFM이나 Class-File API를 쓰지 않습니다.
- **ASM 9.10.1을 jar 안의 jar(`sf-impl.jar`)에 넣고, 부모가 bootstrap인 클래스로더로 로드합니다.** Fabric·Forge·Mixin의 ASM과 충돌하지 않습니다.
- **Win32 포커스는 LWJGL 3의 JNI 호출기(`JNI.invokeP` 등, 3.1은 `callP`)로 읽습니다.** 렌더 스레드가 소유한 창에 포커스가 있으면 게임 포커스입니다.

| LWJGL 메서드 | 위치 | 하는 일 |
|---|---|---|
| `SDLEvents.SDL_GetWindowFromEvent` | 리턴 직전 | windowID 0 → 게임 창 (26.x 키 입력) |
| `SDLEvents.SDL_PollEvent` | 리턴 직전 | 큐가 비었고 포커스가 바뀌었으면 `WINDOW_FOCUS_GAINED/LOST`를 만들어 넣음 (26.x 포커스·일시정지) |
| `GLFW.glfwSwapBuffers`, `SDLVideo.SDL_GL_SwapWindow`, `KHRSwapchain.vkQueuePresentKHR` | 진입 | 포커스가 없으면 `codingFps`에 맞춰 잠깐 재움 (모든 버전) |
| `Display.isActive`, `Display.update` (LWJGL 2) | - | 자리만 마련. 포커스 소스와 동작은 미구현 |

### 버전별 결과 (같은 `sf-agent.jar`, 시작 시 `-javaagent`로 로드)

| | 26.3 (SDL3, Java 25) | 1.21.1 (GLFW, Java 21) 에이전트 없음 | 1.21.1 에이전트 있음 |
|---|---|---|---|
| 클릭·키보드 (Back to Game, E 인벤토리) | ✅ (에이전트 필수) | ✅ | ✅ |
| PLAYING CPU | 109% | 106% | 124% |
| CODING CPU | **24%** | 72% | **11%** |
| HIDDEN CPU | 9% | **72%** (1.21.1은 최소화해도 FPS를 안 낮춤) | **12%** |
| HIDDEN + 회수 후 워킹셋 | 549 MB | 554 MB | 585 MB |

### 이 단계에서 찾아서 고친 것
- **GLFW의 `GLFW_FOCUSED` 속성은 자식 창에서 항상 false입니다**(`GetActiveWindow()`와 비교하기 때문). 포커스는 Win32 스레드 기준으로 통일했습니다.
- **GLFW는 `WM_MOUSEACTIVATE`를 부모에게 넘깁니다.** AWT가 이걸 보면 클릭 직후 포커스를 프레임으로 가져갑니다. 캔버스 서브클래스에서
  `MA_ACTIVATE`로 처리하고 AWT에는 넘기지 않습니다. 고치기 전에는 1.21.1에서 게임에 복귀하자마자 일시정지가 반복됐습니다.
- **호스트가 detach 없이 죽으면 게임 창이 같이 파괴됩니다**(자식 창은 부모와 함께 사라짐). 마인크래프트 프로세스는 남는데 창이 없어집니다.
  셧다운 훅과 테스트 모드의 finally에서 detach하도록 했지만, **IDE 크래시나 강제 종료에는 대책이 없습니다.** 실제 플러그인에서
  풀어야 할 과제입니다(예: 에이전트가 부모 소실을 감지해 창을 최상위로 되돌리기).
- `run-vanilla-mc.sh`는 이제 버전을 고를 수 있습니다(`SF_MC_VERSION`). 버전에 맞는 Java를 고르고, 정확한 버전이 없는 라이브러리는
  ForgeGradle 캐시나 같은 minor 버전으로 대체합니다.

### 아직 검증하지 못한 범위
- ~~LWJGL 2 시대, 1.13~1.20~~ → 아래 "전 버전 결과"에서 1.12.2, 1.16.5로 검증했습니다. 1.7.10과 LWJGL 3.1(1.13~1.14)은 아직 실행해 보지 않았습니다.
- **모드 로더:** Fabric, Forge, NeoForge. 특히 NeoForge·Forge 1.17+는 LWJGL이 이름 있는 모듈 레이어에 있어서, 시스템 클래스패스의
  `SfHooks`를 못 볼 수 있습니다(모듈 reads 추가가 필요할 수 있음).
- 26.3에서 동적 attach(실행 중 붙이기)는 v1으로만 확인했습니다. v2는 시작 시 로드만 확인했습니다.

## 전 버전 결과: 1.12.2 / 1.16.5 / 1.21.1 / 26.3 (같은 `sf-agent.jar`)

로컬 캐시에 없는 버전은 `McFetch.java`로 Mojang 서버에서 받았습니다(SHA-1 검증, 사운드 에셋은 제외해서 두 버전 합계 106MB).
`java McFetch.java fetch|launch <version> [--agent <jar>=codingFps=15]`

| 버전 | 창 / LWJGL / Java | 에이전트 없이 입력 | PLAYING | CODING 없음 → 있음 | HIDDEN 없음 → 있음 | 회수 후 워킹셋 |
|---|---|---|---|---|---|---|
| 26.3 | SDL3 / 3.4.3 / 25 | ❌ (키 windowID 0, 일시정지 반복) | 109% | 65~72% → **24%** | 9% → 9% | 549 MB |
| 1.21.1 | GLFW / 3.3.3 / 21 | ✅ | 124% | 72% → **11%** | 72% → **12%** | 585 MB |
| 1.16.5 | GLFW / 3.2.2 / 8 | ✅ | 80% | 75% → **10%** | 58% → **9%** | 110 MB |
| 1.12.2 | LWJGL 2 `Display` / 2.9.4 / 8 | ✅ | 137% | 84% → **10%** | 63% → **9%** | 44 MB |

CPU는 1코어 기준입니다. 모든 버전에서 클릭(Back to Game)과 키보드(E 인벤토리)를 스크린샷으로 확인했습니다.
에이전트를 붙인 방식은 다음과 같습니다.
- 26.3, 1.21.1, 1.16.5: 게임 시작 시 `-javaagent`로 로드
- 1.12.2: 실행 중인 게임에 attach

- **입력에 에이전트가 꼭 필요한 건 SDL3(26.x)뿐입니다.** GLFW와 LWJGL 2는 `WM_SETFOCUS`로 포커스를 판단해서 자식 창에서도 정상입니다.
  하위 버전에서 에이전트의 몫은 코딩 중과 숨김 상태의 프레임 제한입니다. 이 버전들은 최소화해도 스스로 FPS를 낮추지 않습니다.
- **LWJGL 2:** `Display.update(Z)V` 진입에서 프레임을 제한하고, 포커스는 LWJGL 2 자신의 `Display.isActive()`를 읽습니다.
- **Java 8의 힙 회수:** 기본 GC가 Parallel이라 `MaxHeapFreeRatio`를 조여도 확보된 힙이 거의 줄지 않습니다. 워킹셋 트림은 효과가 큽니다.

### 이 단계에서 찾아서 고친 것
- **Java 8은 경로에 비 ASCII 문자가 있으면 `-javaagent` jar를 못 읽습니다.** `ClassNotFoundException: dev.sulfurframe.agent.SfAgent`가 나고,
  이 레포 경로에는 "아무거나"가 들어 있습니다. ASCII 경로로 복사해서 쓰세요(예: `%TEMP%\sf-agent.jar`). 실제 플러그인은 에이전트를
  ASCII 경로에 풀어 놓고 써야 합니다. 동적 attach의 `loadAgent` 경로도 같습니다.
- **처음 임베드할 때 크기가 우연히 같으면 `WM_SIZE`가 오지 않습니다.** 1.16.5는 그 상태로 예전 프레임버퍼와 GUI 크기를 계속 써서
  화면이 어긋나고 클릭 위치도 틀어졌습니다. 그래서 "버그 신고" 버튼이 눌려 외부 링크 확인 창이 떴고, 링크는 열지 않고 ESC로 닫았습니다.
  임베드 직후 1px 줄였다가 되돌리도록 고쳤습니다.
- **JDK 25 호스트가 Java 8 게임에 attach하면** 에이전트가 정상 로드돼도 `AgentLoadException: ... 0`이 납니다(응답 형식 차이).
  호스트에서 이 경우를 구분해서 처리합니다.
- 버전마다 메뉴 배치가 달라서 프로브의 "Back to Game" 위치를 `SF_BTG_X`/`SF_BTG_Y`로, 1.12.2의 월드 목록 버튼을
  `SF_CREATE_LIST_BTN`으로 지정할 수 있게 했습니다. `InputCheck`에 `--open-world`와 `--esc-resize`를 추가했습니다.

## 남은 버전과 모드 로더 (1.7.10, 1.13.2, Fabric)

| 대상 | 창 / LWJGL / Java | PLAYING | CODING | HIDDEN | 회수 후 워킹셋 | 입력 확인 |
|---|---|---|---|---|---|---|
| 1.7.10 | LWJGL 2.9.1 / 8 | 102% | **10.5%** | **9.5%** | 52 MB | F2 스크린샷 저장, "Taking Inventory" 업적 (E) |
| 1.13.2 | GLFW / LWJGL 3.1.6 / 8 | 85% | **14%** | **12%** | 52 MB | E 인벤토리 |
| 1.21.1 + **Fabric Loader 0.16.10** | GLFW / 3.3.3 / 21 | 107% | **13%** | **12%** | 530 MB | E 인벤토리 |
| 26.3 (재측정, 새 훅 방식) | SDL3 / 3.4.3 / 25 | 129% | **9.9%** | **8.3%** | 558 MB | E 인벤토리 |
| 1.16.5 (재측정, 새 훅 방식) | GLFW / 3.2.2 / 8 | 91% | **13%** | **15%** | 128 MB | - |

이제 **1.7.10부터 26.3까지 바닐라 6개 버전**과 **Fabric**에서 같은 `sf-agent.jar`가 동작합니다.
- 1.7.10의 창은 화면 캡처에 갱신되지 않습니다. 그래서 게임 자신의 스크린샷(F2)과 로그로 확인했습니다.
- `run-vanilla-mc.sh`에 `SF_LOADER=fabric`을 추가했습니다. 캐시에 있는 Fabric Loader, intermediary, Mixin, ASM으로 Knot을 실행합니다.

### 이 단계에서 찾아서 고친 것
- **LWJGL 3.1.x는 JNI 호출기의 인자 순서가 반대입니다**(함수 주소가 첫 번째). 3.2식으로 호출하다가 **1.13.2가 크래시**했습니다
  (창 핸들을 함수로 호출함). `org.lwjgl.Version`을 읽어서 순서를 맞춥니다.
- **Fabric의 Knot은 "게임에 노출되지 않은" 클래스패스 항목(에이전트 jar)의 클래스를 로드하지 않습니다.** Fabric에서는 LWJGL도 Knot이
  로드하므로, 패치된 코드가 `SfHooks`를 직접 부르면 `NoClassDefFoundError`로 크래시합니다. bootstrap 경로에 추가해도 소용이 없었습니다(출처 검사).
  → **패치 코드가 에이전트 클래스를 아예 참조하지 않게 바꿨습니다.** 훅은 JDK 함수형 인터페이스 객체(`LongUnaryOperator`,
  `BiFunction`, `LongConsumer`, `Runnable`)로 `System.getProperties()`의 `dev.sulfurframe.hooks`(Object[])에 등록되고,
  패치 코드는 이걸 꺼내 인터페이스로 호출합니다. JDK 타입은 어떤 클래스로더나 모듈에서도 보이므로, NeoForge·Forge의 모듈 레이어에서도
  같은 이유로 막히지 않을 것으로 예상합니다(미검증).
  - 주의: 시스템 프로퍼티에 문자열이 아닌 값이 하나 들어갑니다. 모든 값을 String으로 캐스팅하는 코드가 있으면 문제가 될 수 있습니다.

### Forge / NeoForge: → 아래 "모드 로더 결과"에서 검증 완료

## 모드 로더 결과 (같은 `sf-agent.jar`, 시작 시 `-javaagent`)

공식 Maven에서 받은 인스톨러(SHA-1 확인)를 헤드리스로 설치하고(`--install-client` / `--installClient`),
`java McFetch.java launch-installed <설치 폴더> <버전 id> --agent <jar>=codingFps=15`로 실행했습니다.

| 로더 | 실행 구조 | PLAYING | CODING | HIDDEN | 회수 후 워킹셋 | 입력 |
|---|---|---|---|---|---|---|
| Fabric 0.16.10 (1.21.1) | Knot | 107% | **13%** | **12%** | 530 MB | E 인벤토리 |
| NeoForge 21.1.265 (1.21.1) | BootstrapLauncher + 모듈 레이어 | 127% | **31%** | **25%** | 591 MB | E 인벤토리 |
| Forge 47.4.28 (1.20.1) | BootstrapLauncher + 모듈 레이어 | 89% | **16%** | **14%** | 624 MB | E 인벤토리 |
| Forge 14.23.5.2860 (1.12.2) | LaunchWrapper, Java 8 | 83% | **14%** | **15%** | 75 MB | E 인벤토리 |

- **모듈 레이어(NeoForge/Forge 1.17+)에서도 그대로 동작합니다.** 훅을 JDK 인터페이스로 넘기는 방식 덕분입니다.
- ~~NeoForge가 바닐라보다 높게 나오는 건 로더 쪽 백그라운드 작업 때문으로 보입니다.~~ → **틀린 해석이었습니다(2026-11-08 재측정).** 위 NeoForge 31%/25%는 월드에 들어간 직후, 청크 로딩·생성이 아직 돌 때 잰 값으로 보입니다.
  같은 월드를 연 NeoForge 21.1.265와 Fabric 1.21.1을 자리 잡은 뒤 번갈아 두 번씩 재면 차이가 없습니다(코어 하나 기준, `--throttle-probe`):

  | | CODING | HIDDEN |
  |---|---|---|
  | NeoForge 21.1.265 | 12.6%, 13.7% | 11.0%, 8.3% |
  | Fabric 0.16.10 | 10.7%, 13.1% | 14.9%, 12.3% |

  반대로 Fabric도 들어간 지 20초 만에 재면 CODING 26.5%가 나왔습니다. JFR(`thread-cpu-load`)로 보면 15fps로 묶인 상태에서 남는 CPU는 두 로더 모두 대부분 JIT 컴파일러 스레드(C2)이고, NeoForge만의 바쁜 스레드는 없었습니다(NeoForge 렌더 스레드는 오히려 Fabric보다 낮음).
  **측정할 때는 월드에 들어가고 1~2분 기다린 뒤 재야 합니다.**
- `launch-installed`는 공식 런처가 하는 일을 대신합니다.
  - `inheritsFrom` 병합, 인자 규칙(Windows x64, feature 제외), `${...}` 치환
  - 인스톨러가 받지 않는 바닐라 라이브러리, LWJGL 2 네이티브, 부모 json을 Mojang에서 받아 채움
  - 최신 Forge·NeoForge는 버전 jar를 클래스패스에 넣지 않고, LaunchWrapper만 넣음

## 전체 커버리지 요약

같은 jar 하나로 아래 전부에서 **임베드 상태로 플레이할 수 있고**, 코딩 중이나 숨김 상태에서는 FPS를 제한합니다.
- 바닐라: 1.7.10, 1.12.2, 1.13.2, 1.16.5, 1.21.1, 26.3
- 로더: Fabric, NeoForge, Forge(모던·레거시)

| 창 / LWJGL | 버전 | 에이전트 없이 입력 | 에이전트의 역할 |
|---|---|---|---|
| LWJGL 2 `Display` | 1.7.10~1.12.2 | ✅ | FPS 제한 (`Display.update`) |
| GLFW, LWJGL 3.1~3.3 | 1.13~1.21.x | ✅ | FPS 제한 (`glfwSwapBuffers`), Win32 포커스 |
| SDL3, LWJGL 3.4 | 26.x | ❌ | windowID 0 보정, 포커스 이벤트 합성, FPS 제한 |

남은 것: 1.8~1.11, 1.14~1.15, 1.17~1.20(바닐라), Quilt, macOS. (IntelliJ 플러그인 환경은 `sf-idea-plugin`에서 검증, Linux X11은 아래 PoC)

## Linux / X11 PoC (2026-11-07, WSL2 Ubuntu 24.04 + WSLg, 바닐라 1.21.1)

`X11Host.java`는 `EmbedHost`의 REPARENT 모드를 X11로 옮긴 것입니다. Swing 창(XToolkit)의 Canvas X 창 아래로 게임 창을 `XReparentWindow`로 옮기고, libX11은 FFM으로 부릅니다.
`McFetch`는 OS를 구분하게 고쳤습니다(라이브러리·인자 규칙, `natives-linux`, Loom 캐시가 없으면 Mojang manifest, `/usr/lib/jvm/java-N-openjdk-amd64`).

```bash
./run-x11-poc.sh 1.21.1 --hold 30          # 게임 실행(없으면) → 임베드 → 30초 뒤 분리
./run-x11-poc.sh 1.21.1 --hold 30 --halt   # 30초 뒤 호스트가 Runtime.halt로 "크래시"
```
WSLg에서는 `WAYLAND_DISPLAY`를 지워야 GLFW 3.4가 X11(XWayland)을 씁니다. 렌더러는 llvmpipe(GL 4.5)였습니다.

```
RESULT PASS game window found
RESULT PASS canvas X window                       (XToolkit 피어의 getWindow)
RESULT PASS reparented under the canvas (and stays)
RESULT PASS game sized to the canvas
RESULT PASS game follows a host resize
RESULT PASS detached back to the root             (분리 후 창 관리자 프레임이 다시 붙은 일반 창, IsViewable)
```
- **마우스:** 임베드 상태에서 클릭이 게임에 그대로 갑니다(온보딩 Continue → 타이틀, Options 열기). 포인터 아래 가장 깊은 X 창이 게임 창이라 Windows 같은 포커스 처리가 필요 없습니다.
- **키보드:** `XSetInputFocus(game)` 후 ESC로 Options가 닫혔습니다.
- 스크린샷: `snapshot-x11.png` (파노라마가 분홍·검정인 것은 PoC가 에셋 객체를 받지 않아서입니다)

### 알아낸 것
- **창 관리자에게서 창 빼앗기:** 매핑된 최상위 창은 창 관리자가 프레임에 넣어 관리합니다. 그냥 `XReparentWindow`하면 WSLg의 Weston WM이 바로 되가져가서, 자기 숨김 창 "Weston WM" 아래에 붙잡아 둡니다.
  순서: `XWithdrawWindow` → `override_redirect` 켜기(창 관리자가 손대지 않는 창이 됨) → 창 관리자가 프레임에서 뺄 때까지 대기 → Canvas 아래로 옮기고 매핑. 그래도 되가져가면 다시 시도합니다(보통 1~2번).
  분리할 때는 반대로 `override_redirect`를 끄고 루트에 매핑하면, 창 관리자가 다시 프레임을 씌웁니다.
- **크래시 때는 X11도 게임 창을 지킬 수 없음:** `XAddToSaveSet`은 Canvas를 만든 연결(AWT의 XToolkit 연결)에 등록해야 합니다. 그런데 그렇게 해도 서버가 게임 창을 옮겨 주는 곳은 "죽은 클라이언트가 만들지 않은 가장 가까운 조상"인 **창 관리자의 프레임**이고, 창 관리자는 호스트 창이 사라지면 그 프레임을 지웁니다. 게임 창도 함께 지워지고, GLFW가 지워진 창에 `XGetGeometry`를 보내 `BadDrawable`이 나면 Xlib 기본 오류 처리기가 게임 프로세스를 끝냅니다(월드 저장 없음).
  프레임을 씌우는 창 관리자(Weston, Mutter, KWin 등)에서는 호스트 쪽 처리만으로 막을 수 없습니다. → 아래 에이전트의 X11 구명보트로 해결(창은 잃지만 월드는 저장).

## 에이전트의 Linux/X11 부분 (`X11Lifeboat.java`, 같은 `sf-agent.jar`)

- **포커스(FPS 제한):** X11에서는 `GLFW_FOCUSED`가 `XGetInputFocus() == 게임 창`이라 임베드된 자식 창에도 맞습니다(호스트가 클릭 때 `XSetInputFocus`). Linux에서는 이것을 씁니다.
- **임베드 감지:** 호스트가 창 관리자에게서 창을 빼올 때 켜는 `override_redirect`를 250ms마다 확인합니다.
- **임베드 중에는 X 오류가 치명적이지 않게:** `XSetErrorHandler`에 인자를 무시하는 C 함수(libc `getpid`)를 넣습니다(Xlib은 처리기 반환값을 쓰지 않음). GLFW는 오류가 필요한 호출 앞뒤로 자기 처리기를 잠깐 걸었다가 우리 것으로 되돌립니다. 분리되면 원래 처리기로 복구합니다.
- **창이 사라지면:** `glfwSwapBuffers`를 건너뛰고(패치를 "훅이 true면 바로 return"으로 바꿈), `glfwSetWindowShouldClose`로 정상 종료를 요청합니다. 스왑을 건너뛰지 않으면 Mesa llvmpipe가 지워진 창에 화면을 내보내다 래스터라이저 스레드에서 SIGSEGV로 JVM이 죽습니다("Stopping!" 직후, 저장 전).
- **창을 구하려는 시도(이벤트 스레드):** save-set이 게임 창을 창 관리자 프레임에 옮기는 ReparentNotify를 `XNextEvent`로 기다렸다가 조회 없이 즉시 루트로 옮깁니다. WSLg의 Weston은 매번 더 빨라서 창은 잃었지만, 같은 스레드가 임베드 도중 창 관리자가 창을 되가져가는 경우를 바로 돌려놓아 호스트가 첫 시도에 임베드합니다. 이전 부모(Canvas)가 아직 있으면 되가져간 것이고, 없으면 호스트가 죽은 것입니다.

검증(WSLg, 바닐라 1.21.1, 월드 안, `run-x11-poc.sh ... --focus-test <pid> --halt`):
```
RESULT PASS reparented under the canvas (and stays)   [1 attempt(s)]
RESULT PASS agent caps the game while the editor has focus  [render + llvmpipe CPU 365% with focus, 190% while coding]  (codingFps=3)
[host] halt
[SF-Agent] x11: host gone and the window manager was faster: window lost
[SF-Agent] game window destroyed while embedded (IDE gone?): asking the game to quit so the world is saved
Stopping! → Saving worlds → All dimensions are saved → 2초 뒤 종료, level.dat 갱신
```
- 정상 분리(`--halt` 없이): X 오류 처리기 복구, 게임은 프레임이 붙은 일반 창으로 계속 실행
- FPS 제한은 llvmpipe가 월드를 원래 15fps 안팎으로 그려서 15로는 차이가 안 보였고, 3으로 시험했습니다. CPU는 서버·청크 스레드가 섞이지 않게 렌더 스레드와 llvmpipe 스레드만 셉니다.
- Windows 회귀 확인: `glfwSwapBuffers` 패치가 분기를 갖게 돼서 1.21.1(LWJGL 3.3, 시작 시 로드와 실행 중 attach)과 1.13.2(LWJGL 3.1)를 다시 실행 → 패치·구명보트·포커스 정상, VerifyError 없음

### SDL3(26.x)와 LWJGL 2(1.12.2)도 X11에서 (같은 `sf-agent.jar`)

| | 26.3 (SDL3, LWJGL 3.4) | 1.12.2 (LWJGL 2.9.4, Java 8) | 1.21.1 (GLFW) |
|---|---|---|---|
| 임베드·크기·창 크기 따라가기 | PASS | PASS | PASS |
| FPS 제한 (렌더+llvmpipe CPU, 포커스 → 코딩, codingFps=3) | 724% → 81% | 324% → 32% | 365% → 190% |
| 호스트 크래시 | 창 구조 성공, 게임 계속 | 창 구조 성공, 게임 계속 | 창 관리자가 이김(이번에 2/2, 앞서 3/3) → 저장하고 종료 |

- **창 찾기:** SDL3는 `WM_NAME`을 `UTF8_STRING` 타입으로 넣어서 `XFetchName`이 못 읽습니다. 이름을 `XGetWindowProperty`로 바이트째 읽고, 같은 이름으로 시작하는 숨은 보조 창("RenderPearl OpenGL Hidden Utility Window")을 피하려고 화면에 보이는(`IsViewable`) 창만 고릅니다. 플러그인 `X11.name`도 같은 방식입니다.
- **포커스:** SDL3와 LWJGL 2는 호스트가 `XSetInputFocus`로 준 포커스를 알아채지 못합니다(LWJGL 2의 `Display.isActive()`가 계속 false). 그래서 에이전트가 직접 `XGetInputFocus() == 게임 창`으로 판단합니다(렌더 스레드 전용 연결). GLFW는 원래대로 `GLFW_FOCUSED`.
- **창 번호:** SDL3는 창 속성 `SDL.window.x11.window`, LWJGL 2는 `LinuxDisplay.getWindow()`.
- **LWJGL 2에는 LWJGL 3의 JNI가 없어서** X11 호출을 JNA(마인크래프트 1.8+에 포함)로 합니다. `X11Lifeboat`의 네이티브 층이 둘 중 있는 쪽을 씁니다.
- **창이 사라지면 화면 내보내기 건너뛰기:** `SDL_GL_SwapWindow`는 true를 돌려주며 건너뛰고, LWJGL 2는 `Display.update` 전체를 건너뜁니다(`isCloseRequested`는 그대로 동작). LWJGL 2의 `Display`는 Java 5 바이트코드라 분기 지점에 스택 맵 프레임을 넣지 않습니다(넣으면 ASM이 거부).
- **26.x는 에셋 객체가 없으면 시작하자마자 죽습니다**(타이틀 파노라마). `McFetch`가 소리(.ogg)를 뺀 에셋 객체를 받도록 바꿨습니다.
- 26.3과 1.12.2는 월드 없이 타이틀 화면에서 시험했습니다(WSL 재시작으로 `/tmp`의 시험 월드가 사라짐). 창을 구했으니 저장 경로는 타지 않았습니다. 1.21.1의 "저장하고 종료"는 앞의 월드 안 시험에서 확인했습니다.
- Windows 회귀: 1.12.2(McFetch), 1.21.1, 26.3(run-vanilla) 모두 패치·구명보트·포커스 정상. 26.3은 한 번 리소스 로딩 중 0xC0000005로 죽었고, 다시 3번은 모두 정상이었습니다(원래 있던 26.3 간헐 시작 크래시로 봅니다).

### 아직 안 한 것
- 실제 Linux 데스크톱(GNOME/KDE, Wayland 세션의 XWayland)과 GPU 드라이버에서 확인
- GLFW에서만 창 구조가 지는 이유
